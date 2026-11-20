package io.washy.sm8750.tuner

import android.os.Process
import java.util.Random
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.Inflater
import kotlin.concurrent.thread

internal const val GAME_SECONDS = 240
internal const val GAME_ROUNDS = 40
internal const val GAME_CALIBRATION_MS = 20_000L
private const val RECOVERY_AT = 220_000L
private const val ROUND_MS = (RECOVERY_AT - GAME_CALIBRATION_MS) / GAME_ROUNDS
private const val SECOND_BURST_MS = 1350L

internal enum class GameOutcome(val label: String) {
    RUNNING("运行中"), COMPLETED("已完成"), FAILED("失败"), STOPPED("已停止"),
    REBOOTED("测试中发生重启"), INTERRUPTED("测试中断"),
}

internal data class GameResult(
    val outcome: GameOutcome,
    val elapsed: Int = 0,
    val rounds: Int = 0,
    val coverage: GameCoverage? = null,
    val detail: String? = null,
    val legacyScenario: Boolean = false,
)

internal fun gameRounds(ms: Long): Int =
    ((ms - GAME_CALIBRATION_MS) / ROUND_MS).toInt().coerceIn(0, GAME_ROUNDS)

internal data class GameBurst(
    val round: Int,
    val cpuUntilMs: Long,
    val cpuWorkers: Int,
)

internal fun gameBurst(ms: Long, workers: Int): GameBurst? {
    if (ms !in GAME_CALIBRATION_MS until RECOVERY_AT) return null
    val round = gameRounds(ms)
    val withinRound = (ms - GAME_CALIBRATION_MS) % ROUND_MS
    val withinBurst = if (withinRound < SECOND_BURST_MS) withinRound else withinRound - SECOND_BURST_MS
    val duration = if (round % 2 == 0) 300L else 400L
    if (withinBurst >= duration) return null
    return GameBurst(
        round = round,
        cpuUntilMs = ms - withinBurst + duration,
        cpuWorkers = if (round % 3 != 1) workers else maxOf(1, workers - 2),
    )
}

internal class GameSession {
    val workerCount = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)
    val failure = AtomicReference<String?>(null)
    @Volatile var beginNs = 0L
    @Volatile var lastFrameNs = 0L
    @Volatile var stopped = false
    private val gpu = mutableListOf<GameSpan>()

    fun elapsedMs(nowNs: Long = System.nanoTime()): Long {
        val start = beginNs
        return if (start == 0L) 0 else ((nowNs - start) / 1_000_000).coerceIn(0, GAME_SECONDS * 1000L)
    }

    @Synchronized
    fun recordGpu(span: GameSpan) {
        gpu += span
    }

    @Synchronized
    fun gpuSpans(): List<GameSpan> = gpu.toList()
}

internal class GameCpuWorkers(private val session: GameSession) : AutoCloseable {
    private val ready = CountDownLatch(session.workerCount)
    private val tids = IntArray(session.workerCount)
    private val raw = ByteArray(512 * 1024).apply {
        val page = ByteArray(16 * 1024).also { Random(8750).nextBytes(it) }
        for (offset in indices step page.size) {
            page.copyInto(this, offset)
            this[offset] = (offset / page.size).toByte()
        }
    }
    private val expected = CRC32().apply { update(raw) }.value
    private val packed = Deflater(1).let { deflater ->
        try {
            deflater.setInput(raw)
            deflater.finish()
            val output = ByteArray(raw.size * 2)
            val size = deflater.deflate(output)
            check(deflater.finished()) { "无法准备 CPU 校验负载" }
            output.copyOf(size)
        } finally {
            deflater.end()
        }
    }
    private val queues = List(session.workerCount) { ArrayBlockingQueue<Long>(1) }
    private val workers = queues.mapIndexed { index, queue ->
        thread(name = "GameCPU-$index", isDaemon = true) {
            val inflater = Inflater()
            val scratch = ByteArray(raw.size)
            val crc = CRC32()
            try {
                tids[index] = Process.myTid()
                ready.countDown()
                while (!session.stopped) {
                    val deadline = queue.take()
                    while (!session.stopped && System.nanoTime() < deadline) {
                        inflater.reset()
                        inflater.setInput(packed)
                        val bytes = inflater.inflate(scratch)
                        crc.reset()
                        crc.update(scratch, 0, bytes)
                        check(inflater.finished() && bytes == raw.size && crc.value == expected) {
                            "CPU 解压校验失败"
                        }
                    }
                }
            } catch (_: InterruptedException) {
                // Test ended while this worker was parked.
            } catch (e: Exception) {
                session.failure.compareAndSet(null, e.message ?: "CPU 计算失败")
            } finally {
                inflater.end()
            }
        }
    }
    private var lastDeadline = 0L

    fun awaitTids(): Set<Int> {
        check(ready.await(2, TimeUnit.SECONDS)) { "CPU 测试线程未能启动" }
        return tids.toSet()
    }

    fun pulse(burst: GameBurst) {
        if (burst.cpuUntilMs <= lastDeadline) return
        lastDeadline = burst.cpuUntilMs
        val deadline = session.beginNs + burst.cpuUntilMs * 1_000_000
        repeat(burst.cpuWorkers) { index -> queues[index].offer(deadline) }
    }

    override fun close() {
        workers.forEach(Thread::interrupt)
        workers.forEach { it.join(500) }
    }
}
