package io.washy.sm8750.tuner

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal enum class CrashKind(val label: String) {
    GPU("GPU 驱动相关"),
    CPU("CPU 异常相关"),
    DISPLAY("显示链路相关"),
    WATCHDOG("看门狗复位 · 原因未明"),
    UNKNOWN("内核崩溃 · 原因未明"),
}

internal data class CrashEvidence(
    val label: String,
    val text: String,
    val deleteArgs: List<String> = emptyList(),
)

internal data class CrashEntry(
    val id: String,
    val time: Long,
    val kind: CrashKind,
    val source: String,
    val thread: String? = null,
    val frame: String? = null,
    val cpu: Int? = null,
    val detail: String? = null,
    val bootId: String? = null,
    val evidence: List<CrashEvidence> = emptyList(),
)

internal data class CrashScan(
    val bootStartedAt: Long,
    val entries: List<CrashEntry>,
    val kernelEvidence: List<CrashEvidence> = emptyList(),
)

internal const val CRASH_LOG_LIMIT = 40
internal const val KERNEL_LOG_ID_PREFIX = "kernel:"

private val bootIdPattern = Regex("[0-9a-fA-F-]{36}")
private val bootFailure = Regex(
    """kernel[_ -]+(?:crash|panic)|watchdog|\bwdog\b|\bminidump\b""", RegexOption.IGNORE_CASE,
)
private val panicAnchor = Regex(
    """kernel panic|internal error:\s*(?:oops|arm64)|watchdog.{0,50}(?:lockup|bite)""", RegexOption.IGNORE_CASE,
)
private val gpuDriver = Regex(
    """\b(?:kgsl|adreno|gmu|mali|kbase|gen\d+_hwsched)(?=[_:\W]|$)""", RegexOption.IGNORE_CASE,
)
private val gpuFault = Regex(
    """\bGPU[\s_]+(?:hang|fault|timeout|page fault|reset failed)\b""", RegexOption.IGNORE_CASE,
)
private val displayDriver = Regex(
    """\b(?:crtc_commit|sde_crtc|sde_encoder|mdss_drm|dpu_crtc|dsi_display)(?=[_:\W]|$)""", RegexOption.IGNORE_CASE,
)
private val cpuFault = Regex(
    """\bmachine check\b|\bCPU\s*[:#]?\s*\d+\b.{0,40}\b(?:stuck|lockup|failed to come online)\b|\b(?:hard|soft) lockup\b.{0,50}\bCPU\s*[:#]?\s*\d+\b""",
    RegexOption.IGNORE_CASE,
)
private val watchdog = Regex("""watchdog|\bwdog\b|(?:hard|soft) lockup""", RegexOption.IGNORE_CASE)
private val cpuNumber = Regex("""\bCPU\s*[:#]?\s*(\d{1,3})\b""", RegexOption.IGNORE_CASE)
private val swapperCpu = Regex("""^swapper/(\d{1,3})$""")
private val kernelThread = Regex("""\bComm:\s*(\S+)""")
private val kernelFrame = Regex("""\bpc\s*:\s*([\w.$+-]+(?:/0x[\da-f]+)?)""", RegexOption.IGNORE_CASE)
private val bootDate = Regex("""\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}""")
private val dateFormat = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss")
private val fileDateFormat = DateTimeFormatter.ofPattern("uuuuMMdd-HHmmss")
private val sha256Pattern = Regex("[0-9a-f]{64}")

internal fun CrashEntry.formattedTime(): String =
    dateFormat.format(Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()))

internal fun CrashEntry.downloadName(): String =
    "SunTuner-Crash-${fileDateFormat.format(Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()))}.txt"

internal fun CrashEntry.reportText(): String = buildString {
    appendLine("SunTuner 崩溃日志")
    appendLine("${formattedTime()} · ${kind.label}")
    appendLine("来源 ${source}")
    thread?.let { appendLine("线程 $it") }
    cpu?.let { appendLine("执行上下文 CPU$it") }
    frame?.let { appendLine("栈顶 $it") }
    detail?.let { appendLine(it) }
    for (record in evidence) {
        appendLine()
        appendLine("=== ${record.label} ===")
        appendLine(record.text)
    }
}
private val kernelTime = Regex("""\[\s*(\d+\.\d+)\]""")

internal fun parseCrashLogs(
    raw: String,
    bootStart: Long,
    zone: ZoneId = ZoneId.systemDefault(),
): CrashScan {
    val lines = raw.lineSequence().map { it.replace("\u0000", "").removeSuffix("\r") }.toList()
    val boot = lines.firstOrNull { it.startsWith("CRASH_BOOT ") }?.substringAfter(' ')
        ?.takeIf(bootIdPattern::matches) ?: error("无法确认系统启动标识")
    val bootLines = section(lines, "reason")
    val reason = bootLines.firstOrNull(bootFailure::containsMatchIn)
    val archive = parseCritical(section(lines, "critical"), zone)
    val kernels = lines.filter { it.startsWith("BEGIN kernel:") }.distinct().mapNotNull { header ->
        val name = header.removePrefix("BEGIN ")
        parseKernel(section(lines, name), name.substringAfter(':'), bootStart)
    }
    val kernel = kernels.maxByOrNull {
        (if (it.kind == CrashKind.UNKNOWN) 0 else 4) +
            (if (it.frame != null) 2 else 0) + (if (it.thread != null) 1 else 0)
    }

    if (reason == null && kernel == null) return CrashScan(bootStart, archive.take(CRASH_LOG_LIMIT))

    // OPlus archives can be written several minutes after the new boot starts.
    val reportedAt = reason?.let { parseTime(it, zone) }
    val archived = archive.singleOrNull { it.time >= bootStart - 10_000 }
    val current = kernel ?: archived ?: CrashEntry(
        id = "boot:$boot", time = bootStart, kind = kindFor(reason.orEmpty()), source = "系统重启原因",
    )
    val latest = current.copy(
        id = archived?.id ?: current.id,
        time = reportedAt ?: archived?.time ?: bootStart,
        kind = listOfNotNull(current.kind, archived?.kind, kindFor(reason.orEmpty()))
            .firstOrNull { it != CrashKind.UNKNOWN } ?: CrashKind.UNKNOWN,
        source = listOfNotNull(kernel?.source, archived?.source, "系统重启原因".takeIf { reason != null })
            .distinct().joinToString(" · ").ifBlank { current.source },
        thread = current.thread ?: archived?.thread,
        frame = current.frame ?: archived?.frame,
        cpu = current.cpu ?: archived?.cpu,
        detail = current.detail ?: archived?.detail ?: reason?.take(240),
        bootId = boot,
        evidence = archived?.evidence.orEmpty() +
            kernels.filter { it.id == kernel?.id }.flatMap { it.evidence } +
            listOfNotNull(reason?.let { CrashEvidence("系统重启原因", bootLines.filter(String::isNotBlank).joinToString("\n")) }),
    )
    val entries = (archive.filterNot { it.id == latest.id } + latest)
        .sortedByDescending { it.time }.take(CRASH_LOG_LIMIT)
    return CrashScan(bootStart, entries, kernels.flatMap { it.evidence })
}

private fun parseCritical(lines: List<String>, zone: ZoneId): List<CrashEntry> {
    val entries = mutableListOf<CrashEntry>()
    var start = -1
    for ((index, line) in lines.withIndex()) {
        if (line.startsWith("<kernel-panic-begin>")) start = index
        if (!line.startsWith("<kernel-panic-end>") || start < 0) continue
        val block = lines.subList(start, index + 1)
        start = -1
        val date = block.firstOrNull { it.startsWith("log-time:") }?.let { bootDate.find(it)?.value } ?: continue
        val time = parseTime(date, zone) ?: continue
        val marker = block.firstOrNull { it.startsWith("kernel_panic") } ?: continue
        val thread = marker.substringAfter("COMM=", "").substringBefore('|').substringBefore("@,@").nonBlank(90)
        val frame = marker.substringAfter("FST_FRAME=", "").substringBefore('|').substringBefore("@,@").nonBlank(180)
        val detail = marker.substringAfter("KEYINFO=", "").substringBefore("@,@").nonBlank(240)
        val hash = marker.substringAfter("stackhash@:@", "").substringBefore("@,@").nonBlank(64)
        val source = "OPlus critical.log"
        entries += CrashEntry(
            id = "oplus:$time:${hash ?: marker.hashCode()}", time = time,
            kind = kindFor(detail.orEmpty(), thread, frame), source = source,
            thread = thread, frame = frame, cpu = crashCpu(marker, thread), detail = detail,
            evidence = listOf(CrashEvidence(
                source, block.joinToString("\n").take(16_384),
                if (marker.length <= 8192) listOf("delete_critical", date, marker) else emptyList(),
            )),
        )
    }
    return entries.distinctBy { it.id }.sortedByDescending { it.time }
}

private fun parseKernel(lines: List<String>, source: String, time: Long): CrashEntry? {
    val hash = lines.firstOrNull()?.removePrefix("CRASH_HASH ")?.takeIf(sha256Pattern::matches)
    val content = if (lines.firstOrNull()?.startsWith("CRASH_HASH ") == true) lines.drop(1) else lines
    val anchor = content.indexOfLast(panicAnchor::containsMatchIn)
    if (anchor < 0) return null
    val anchorTime = kernelTime.find(content[anchor])?.groupValues?.get(1)?.toDoubleOrNull()
    val context = content.subList(maxOf(0, anchor - 80), minOf(content.size, anchor + 21)).filter { line ->
        val at = kernelTime.find(line)?.groupValues?.get(1)?.toDoubleOrNull()
        anchorTime == null || at == null || at in (anchorTime - 2)..(anchorTime + 1)
    }
    val commLine = context.lastOrNull(kernelThread::containsMatchIn).orEmpty()
    val thread = kernelThread.find(commLine)?.groupValues?.get(1)?.nonBlank(90)
    val frame = context.lastOrNull(kernelFrame::containsMatchIn)
        ?.let { kernelFrame.find(it)?.groupValues?.get(1)?.nonBlank(180) }
    val fault = context.lastOrNull { cpuFault.containsMatchIn(it) }
    val detail = listOfNotNull(fault, content[anchor]).distinct().joinToString("\n")
    // Include the kernel timestamps so the same retained dump keeps its identity across boots.
    val signature = listOfNotNull(commLine, frame, detail).joinToString("\n").hashCode().toUInt().toString(16)
    return CrashEntry(
        id = "$KERNEL_LOG_ID_PREFIX$signature", time = time, kind = kindFor(detail, thread, frame), source = source,
        thread = thread, frame = frame, cpu = crashCpu(fault ?: "$commLine $detail", thread),
        detail = detail.take(320),
        evidence = listOf(CrashEvidence(
            source, content.joinToString("\n").takeLast(98_304),
            if (hash != null) listOf("delete_kernel", source, hash) else emptyList(),
        )),
    )
}

private fun kindFor(detail: String, thread: String? = null, frame: String? = null): CrashKind {
    val location = listOfNotNull(thread, frame).joinToString(" ")
    return when {
        cpuFault.containsMatchIn(detail) -> CrashKind.CPU
        gpuFault.containsMatchIn(detail) || gpuDriver.containsMatchIn(location) -> CrashKind.GPU
        displayDriver.containsMatchIn(location) -> CrashKind.DISPLAY
        gpuDriver.containsMatchIn(detail) && detail.contains("kernel BUG", ignoreCase = true) -> CrashKind.GPU
        watchdog.containsMatchIn(detail) -> CrashKind.WATCHDOG
        else -> CrashKind.UNKNOWN
    }
}

private fun crashCpu(line: String, thread: String?): Int? =
    cpuNumber.find(line)?.groupValues?.get(1)?.toIntOrNull()
        ?: thread?.let { swapperCpu.matchEntire(it)?.groupValues?.get(1)?.toIntOrNull() }

private fun section(lines: List<String>, name: String): List<String> =
    lines.dropWhile { it != "BEGIN $name" }.drop(1).takeWhile { it != "END $name" }

private fun String.nonBlank(limit: Int): String? = trim().take(limit).takeIf { it.isNotEmpty() }
private fun parseTime(text: String, zone: ZoneId): Long? =
    bootDate.find(text)?.value?.let {
        runCatching { LocalDateTime.parse(it, dateFormat).atZone(zone).toInstant().toEpochMilli() }.getOrNull()
    }
