package io.washy.sm8750.tuner

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

internal data class GameFrequency(
    val policy: String,
    val up: Int,
    val down: Int,
    val fastPairs: Int,
    val lowKhz: Int?,
    val highKhz: Int?,
)

internal data class GameCoverage(
    val frequencies: List<GameFrequency>? = null,
    val cpuMoves: Int? = null,
    val clusterMoves: Int? = null,
    val gpuTimedRounds: Int = 0,
    val overlapRounds: Int? = null,
    val incompleteTrace: Boolean = false,
)

internal data class GameSpan(val begin: Long, val end: Long, val round: Int = -1)

internal class GameTrace(tids: Set<Int>, start: () -> Process) : AutoCloseable {
    private val ready = CountDownLatch(1)
    private val results = GameTraceEvents(tids)
    private val process = runCatching(start).getOrNull()
    private var closed = false
    private val reader = process?.let { child ->
        thread(name = "GameTrace", isDaemon = true) {
            try {
                child.inputStream.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        results.record(line)
                        if (line.startsWith("READY ")) ready.countDown()
                    }
                }
            } catch (_: java.io.IOException) {
                // A disconnected observer leaves affected measurements unavailable.
            } finally {
                ready.countDown()
            }
        }
    }

    fun awaitReady() {
        if (process != null) ready.await(2500, TimeUnit.MILLISECONDS)
    }

    override fun close() {
        if (closed) return
        closed = true
        val child = process ?: return
        runCatching { child.outputStream.close() }
        if (!child.waitFor(1500, TimeUnit.MILLISECONDS)) child.destroy()
        reader?.join(700)
        if (reader?.isAlive == true) child.destroyForcibly()
    }

    fun coverage(begin: Long, end: Long, timedGpu: List<GameSpan>): GameCoverage =
        if (reader?.isAlive == true) GameCoverage(incompleteTrace = true)
        else results.coverage(begin, end, timedGpu)
}

internal class GameTraceEvents(private val tids: Set<Int>) {
    private data class FrequencyEvent(val at: Long, val cpu: Int, val khz: Int)
    private data class SwitchEvent(val at: Long, val cpu: Int, val prev: Int, val next: Int)
    private data class Policy(val name: String, val initial: Int)
    private val policies = mutableMapOf<Int, Policy>()
    private val frequencies = mutableListOf<FrequencyEvent>()
    private val switches = mutableListOf<SwitchEvent>()
    private var sawReady = false
    private var frequencyAvailable = false
    private var schedulerAvailable = false
    private var finished = false
    private var lost = false

    fun record(line: String) {
        when {
            line.startsWith("POLICY ") -> {
                val parts = line.trim().split(whitespace)
                val policy = Policy(parts[1], parts[2].toIntOrNull() ?: 0)
                parts.drop(3).mapNotNull(String::toIntOrNull).forEach { policies[it] = policy }
            }
            line.startsWith("READY ") -> {
                sawReady = true
                schedulerAvailable = line[6] == '1'
                frequencyAvailable = line[8] == '1'
            }
            line.startsWith("LOST ") -> {
                finished = true
                lost = lost || line.substringAfter(' ').toIntOrNull() != 0
            }
            line.contains("LOST ") && line.contains("EVENTS") -> lost = true
            else -> {
                val match = traceLine.find(line) ?: return
                val (cpu, seconds, fraction, event, fields) = match.destructured
                val time = seconds.toLong() * 1_000_000_000 + fraction.padEnd(9, '0').toLong()
                when (event) {
                    "cpu_frequency" -> freqFields.find(fields)?.destructured?.let { (khz, id) ->
                        frequencies += FrequencyEvent(time, id.toInt(), khz.toInt())
                    }
                    "sched_switch" -> switchFields.find(fields)?.destructured?.let { (prev, next) ->
                        switches += SwitchEvent(time, cpu.toInt(), prev.toInt(), next.toInt())
                    }
                }
            }
        }
    }

    fun coverage(begin: Long, end: Long, timedGpu: List<GameSpan>): GameCoverage {
        val timedRounds = timedGpu.map { it.round }.toSet().size
        if (!sawReady || !finished || lost) {
            return GameCoverage(gpuTimedRounds = timedRounds, incompleteTrace = sawReady)
        }
        val freq = if (frequencyAvailable && policies.isNotEmpty()) {
            policies.values.distinctBy { it.name }.sortedBy { it.name.removePrefix("policy").toIntOrNull() }
                .map { policy -> frequencyResult(policy, begin, end) }
        } else null
        if (!schedulerAvailable) return GameCoverage(frequencies = freq, gpuTimedRounds = timedRounds)

        val lastCore = mutableMapOf<Int, Int>()
        val running = mutableMapOf<Int, SwitchEvent>()
        val cpuSpans = mutableListOf<GameSpan>()
        var moves = 0
        var clusterMoves = 0
        for (event in switches.sortedBy { it.at }) {
            if (event.at > end) break
            running.remove(event.prev)?.let { start ->
                if (start.cpu == event.cpu && event.at > begin) {
                    cpuSpans += GameSpan(maxOf(begin, start.at), event.at)
                }
            }
            if (event.next in tids) {
                val previous = lastCore.put(event.next, event.cpu)
                if (event.at >= begin && previous != null && previous != event.cpu) {
                    moves++
                    if (policies[previous] != policies[event.cpu]) clusterMoves++
                }
                running[event.next] = event
            }
        }
        running.values.filter { it.at < end }.forEach { cpuSpans += GameSpan(maxOf(begin, it.at), end) }
        val orderedCpu = cpuSpans.sortedBy { it.begin }
        var cpuIndex = 0
        val overlapped = mutableSetOf<Int>()
        for (gpu in timedGpu.sortedBy { it.begin }) {
            while (cpuIndex < orderedCpu.size && orderedCpu[cpuIndex].end <= gpu.begin) cpuIndex++
            if (cpuIndex < orderedCpu.size && orderedCpu[cpuIndex].begin < gpu.end) overlapped += gpu.round
        }
        return GameCoverage(
            frequencies = freq,
            cpuMoves = moves,
            clusterMoves = clusterMoves.takeIf { policies.isNotEmpty() },
            gpuTimedRounds = timedRounds,
            overlapRounds = overlapped.size.takeIf { timedRounds > 0 },
        )
    }

    private fun frequencyResult(policy: Policy, begin: Long, end: Long): GameFrequency {
        var previous = policy.initial
        var lastUp = false
        var lastChange = Long.MIN_VALUE
        var up = 0
        var down = 0
        var fast = 0
        var low = Int.MAX_VALUE
        var high = 0
        for (event in frequencies.filter { policies[it.cpu] == policy }.sortedBy { it.at }) {
            if (event.at > end) break
            if (event.at >= begin && previous > 0 && event.khz > 0 && event.khz != previous) {
                val rising = event.khz > previous
                if (rising) up++ else down++
                if (lastChange >= begin && lastUp != rising && event.at - lastChange <= 250_000_000) fast++
                lastChange = event.at
                lastUp = rising
                low = minOf(low, previous, event.khz)
                high = maxOf(high, previous, event.khz)
            }
            previous = event.khz
        }
        return GameFrequency(policy.name, up, down, fast, low.takeIf { high > 0 }, high.takeIf { it > 0 })
    }

    private companion object {
        val whitespace = Regex("\\s+")
        val traceLine = Regex("""\[(\d+)]\s+\S+\s+(\d+)\.(\d{1,9}):\s+(sched_switch|cpu_frequency):\s+(.*)""")
        val freqFields = Regex("""state=(\d+)\s+cpu_id=(\d+)""")
        val switchFields = Regex("""prev_pid=(\d+).*next_pid=(\d+)""")
    }
}
