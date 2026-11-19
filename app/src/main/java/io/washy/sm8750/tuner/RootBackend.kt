package io.washy.sm8750.tuner

import android.content.Context
import android.net.Uri
import android.os.Build
import android.system.Os
import android.util.AtomicFile
import java.io.File
import java.io.OutputStream
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class LoadControl {
    private var stopRequested = false
    private var stdin: OutputStream? = null

    @Synchronized
    fun attach(stream: OutputStream) {
        stdin = stream
        if (stopRequested) signal()
    }

    @Synchronized
    fun stop() {
        stopRequested = true
        signal()
    }

    @Synchronized
    fun detach() {
        stdin = null
    }

    private fun signal() {
        val stream = stdin ?: return
        runCatching { stream.write(10); stream.flush() }
        runCatching { stream.close() }
        stdin = null
    }
}

internal class MissingCdtBackupException(message: String) : IllegalStateException(message)
internal enum class CdtOutcome { VERIFIED, UNCHANGED, ROLLED_BACK, UNKNOWN }

internal data class CdtPartition(val bootId: String, val disk: String, val backupStatus: String) {
    val canRestore: Boolean
        get() = disk == "slt_enabled" || backupStatus in setOf("match", "enabled_match", "mismatch")
}

internal data class CdtOperationResult(
    val outcome: CdtOutcome,
    val partition: CdtPartition?,
    val plan: DevicePlan?,
    val message: String?,
    val logSaved: Boolean,
)

private class CdtCommandTrace(val output: String, val exitCode: Int?, val failure: Exception?) {
    fun field(name: String, delimiter: String = "="): String? =
        output.lineSequence().singleOrNull { it.startsWith("$name$delimiter") }?.substringAfter(delimiter)

    fun hasMarker(marker: String): Boolean = output.lineSequence().any { it == marker }
}

internal class RootBackend(private val context: Context) {
    private val cdtFailureLog = AtomicFile(File(context.filesDir, "cdt-last-failure.txt"))
    val hasCdtFailureLog: Boolean get() = cdtFailureLog.baseFile.isFile
    private val bundle by lazy {
        File(context.filesDir, "backend").apply {
            check(isDirectory || mkdirs()) { "无法准备 Root 后端" }
            val assets = context.assets
            val names = checkNotNull(assets.list("backend")).toSet()
            listFiles()?.forEach { existing ->
                if (existing.name !in names) existing.deleteRecursively()
            }
            for (name in names) {
                val temporary = File.createTempFile("tuner-", ".tmp", this)
                try {
                    assets.open("backend/$name").use { input ->
                        temporary.outputStream().use { output -> input.copyTo(output) }
                    }
                    check(temporary.renameTo(File(this, name))) { "无法准备后端文件 $name" }
                } finally {
                    temporary.delete()
                }
            }
        }
    }

    suspend fun read(): DevicePlan = parsePlan(command(listOf("read")))

    suspend fun exportDiagnostics(destination: Uri): Boolean = withContext(Dispatchers.IO) {
        val rootReport = try {
            command(listOf("diagnostics"))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        val output = context.contentResolver.openOutputStream(destination, "wt")
            ?: error("无法打开诊断报告")
        output.bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.appendLine("SunTuner compatibility report v1")
            writer.appendLine("MODEL=${Build.MODEL}")
            writer.appendLine("MANUFACTURER=${Build.MANUFACTURER}")
            writer.appendLine("DEVICE=${Build.DEVICE}")
            writer.appendLine("PRODUCT=${Build.PRODUCT}")
            writer.appendLine("BOARD=${Build.BOARD}")
            writer.appendLine("ANDROID_RELEASE=${Build.VERSION.RELEASE}")
            writer.appendLine("FIRMWARE_FINGERPRINT=${Build.FINGERPRINT}")
            writer.appendLine("KERNEL=${Os.uname().release}")
            writer.appendLine("ABIS=${Build.SUPPORTED_ABIS.joinToString(",")}")
            writer.appendLine()
            if (rootReport != null) {
                writer.append(rootReport)
            } else {
                writer.appendLine("ROOT_PROBE=unavailable (check Root authorization and retry)")
            }
            val previousFailure = readCdtFailureLog()
            if (previousFailure != null) {
                writer.appendLine()
                writer.appendLine("[last CDT operation failure]")
                writer.append(previousFailure)
            }
        }
        rootReport != null
    }

    suspend fun enableCdt(bootId: String): CdtOperationResult =
        cdtCommand(listOf("cdt_enable", bootId))

    suspend fun restoreCdt(bootId: String, fallbackClean: Boolean = false): CdtOperationResult {
        val args = listOf("cdt_restore", bootId) + if (fallbackClean) listOf("--fallback-clean") else emptyList()
        return cdtCommand(args)
    }

    suspend fun readCdtPartition(): CdtPartition =
        parsePartition(traceCommand(listOf("cdt_status"), StringBuilder()))

    suspend fun retryCdtRecovery(appendFailureLog: Boolean) = withContext(Dispatchers.IO) {
        val log = StringBuilder()
        val recovery = traceCommand(listOf("cdt_recover"), log)
        if (appendFailureLog || recovery.hasMarker("CDT_RECOVERY_BEGIN")) {
            saveCdtFailureLog(readCdtFailureLog().orEmpty() + log)
        }
        recovery.failure?.let { throw it }
        recovery.field("CDT_RECOVERY")
    }

    private fun parsePartition(trace: CdtCommandTrace): CdtPartition {
        trace.failure?.let { throw it }
        return CdtPartition(
            bootId = requireNotNull(trace.field("BOOT", " ")),
            disk = requireNotNull(trace.field("CDT_DISK")),
            backupStatus = requireNotNull(trace.field("CDT_BACKUP_STATUS")),
        )
    }

    private suspend fun cdtCommand(args: List<String>): CdtOperationResult = withContext(Dispatchers.IO) {
        val log = StringBuilder()
        log.appendLine("SunTuner CDT operation log v2")
        log.appendLine("START_UTC=${Instant.now()}")
        log.appendLine("MODEL=${Build.MODEL}")
        log.appendLine("FIRMWARE_FINGERPRINT=${Build.FINGERPRINT}")
        log.appendLine("KERNEL=${Os.uname().release}")
        log.appendLine("APP_VERSION=${context.packageManager.getPackageInfo(context.packageName, 0).versionName}")
        val operation = traceCommand(args, log)
        val operationFailure = operation.failure
        if (operationFailure is MissingCdtBackupException) throw operationFailure
        val started = operation.hasMarker("CDT_OPERATION_BEGIN")
        var outcome = when (operation.field("CDT_RESULT")) {
            "verified" -> CdtOutcome.VERIFIED
            "unchanged" -> CdtOutcome.UNCHANGED
            "rolled_back" -> CdtOutcome.ROLLED_BACK
            else -> if (operation.exitCode == 1 && !started && !operation.hasMarker("CDT_RECOVERY_BEGIN")) {
                CdtOutcome.UNCHANGED
            } else {
                CdtOutcome.UNKNOWN
            }
        }
        if (outcome != CdtOutcome.VERIFIED || operationFailure != null) {
            saveCdtFailureLog(log.toString())
        }
        if (outcome == CdtOutcome.UNKNOWN) {
            val recovery = traceCommand(listOf("cdt_recover", args[1]), log)
            if (started && recovery.failure == null) {
                outcome = when (recovery.field("CDT_RECOVERY")) {
                    "target_verified" -> CdtOutcome.VERIFIED
                    "previous_verified" -> CdtOutcome.ROLLED_BACK
                    else -> CdtOutcome.UNKNOWN
                }
            }
        }

        val partition = if (outcome == CdtOutcome.UNKNOWN) null else reportOrNull(log) {
            parsePartition(traceCommand(listOf("cdt_status"), log))
        }
        val plan = if (partition == null) null else reportOrNull(log) {
            val report = traceCommand(listOf("read"), log)
            report.failure?.let { throw it }
            parsePlan(report.output)
        }
        val message = when (outcome) {
            CdtOutcome.VERIFIED -> if (plan == null) "CDT 校验通过，电压计划读取失败，可稍后刷新。" else null
            CdtOutcome.UNCHANGED -> "CDT 未改动。\n${operationFailure?.message ?: "写入未开始。"}"
            CdtOutcome.ROLLED_BACK -> "操作失败，已恢复并校验操作前的 CDT。"
            CdtOutcome.UNKNOWN -> "无法确认 CDT 操作结果。请先刷新并检查恢复结果，再重启。"
        }
        log.appendLine("FINAL_CDT_RESULT=$outcome")
        log.appendLine("END_UTC=${Instant.now()}")
        val logSaved = (outcome != CdtOutcome.VERIFIED || operationFailure != null || plan == null) &&
            saveCdtFailureLog(log.toString())
        CdtOperationResult(outcome, partition, plan, message, logSaved)
    }

    private suspend fun traceCommand(args: List<String>, log: StringBuilder): CdtCommandTrace {
        val output = StringBuilder()
        var exitCode: Int? = null
        log.appendLine()
        log.appendLine("[${Instant.now()}] ${args.joinToString(" ")}")
        val failure = try {
            command(args, onLine = {
                output.appendLine(it)
                log.appendLine(it)
            }, onExit = {
                exitCode = it
                log.appendLine("ROOT_EXIT=$it")
            })
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.appendLine(e.stackTraceToString())
            e
        }
        return CdtCommandTrace(output.toString(), exitCode, failure)
    }

    private inline fun <T> reportOrNull(log: StringBuilder, read: () -> T): T? = try {
        read()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.appendLine("REPORT_ERROR=${e.message}")
        null
    }

    private fun saveCdtFailureLog(log: String): Boolean = runCatching {
        val stream = cdtFailureLog.startWrite()
        try {
            stream.write(log.toByteArray(Charsets.UTF_8))
            cdtFailureLog.finishWrite(stream)
        } catch (e: Exception) {
            cdtFailureLog.failWrite(stream)
            throw e
        }
    }.isSuccess

    private fun readCdtFailureLog(): String? = runCatching {
        cdtFailureLog.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() }
    }.getOrNull()

    suspend fun exportCdtFailureLog(destination: Uri) = withContext(Dispatchers.IO) {
        val log = readCdtFailureLog() ?: error("没有找到保存的 CDT 刷入日志")
        val output = context.contentResolver.openOutputStream(destination, "wt")
            ?: error("无法打开刷入日志文件")
        output.bufferedWriter(Charsets.UTF_8).use { it.write(log) }
    }

    suspend fun reboot(bootId: String) {
        command(listOf("reboot", bootId))
    }

    suspend fun backupCdt(customPath: String? = null): CdtBackupResult {
        val args = if (customPath != null) listOf("cdt_backup", customPath) else listOf("cdt_backup")
        val lines = command(args).lines()
        val path = lines.firstOrNull { it.startsWith("CDT_BACKUP_PATH ") }?.removePrefix("CDT_BACKUP_PATH ")
            ?: throw IllegalStateException("未返回备份保存路径")
        val sha256 = lines.firstOrNull { it.startsWith("CDT_SHA256 ") }?.removePrefix("CDT_SHA256 ")
            ?: throw IllegalStateException("未返回备份校验哈希")
        return CdtBackupResult(path, sha256)
    }

    suspend fun runLoad(
        device: DevicePlan,
        config: LoadConfig,
        control: LoadControl,
        onLine: (String) -> Unit,
    ): LoadStats? {
        val args = listOf(
            "load", device.bootId, config.mask.toString(), config.threads.toString(), config.seconds.toString(),
            config.mode.argument, (config.targets[0] ?: 0).toString(), (config.targets[1] ?: 0).toString(),
            config.workload.argument,
        )
        return parseLoadStats(command(args, control, onLine), config, device.current)
    }

    private suspend fun command(
        args: List<String>,
        control: LoadControl? = null,
        onLine: (String) -> Unit = {},
        onExit: (Int) -> Unit = {},
    ): String = withContext(Dispatchers.IO) {
        val command = listOf("/system/bin/sh", File(bundle, "controller.sh").path, bundle.path) + args
        val process = try {
            ProcessBuilder("su", "-c", command.joinToString(" ") { shellQuote(it) })
                .redirectErrorStream(true)
                .start()
        } catch (e: java.io.IOException) {
            throw IllegalStateException("找不到可用的 su，请在 Root 管理器中授权此应用", e)
        }
        try {
            if (control == null) process.outputStream.close() else control.attach(process.outputStream)
            val output = process.inputStream.bufferedReader().use { reader ->
                buildString {
                    reader.forEachLine {
                        appendLine(it)
                        onLine(it)
                    }
                }
            }
            val exitCode = process.waitFor()
            onExit(exitCode)
            if (exitCode != 0) {
                val errors = output.lineSequence().filter { it.startsWith("ERROR ") }
                    .map { it.removePrefix("ERROR ") }.toList()
                val backupMissing = errors.firstOrNull { it.startsWith("CDT_NO_BACKUP ") }
                if (backupMissing != null) {
                    throw MissingCdtBackupException(backupMissing.removePrefix("CDT_NO_BACKUP "))
                }
                error(errors.joinToString("\n").ifBlank {
                    output.trim().takeLast(500).ifBlank { "Root 命令失败，请检查授权" }
                })
            }
            output
        } finally {
            control?.detach()
            runCatching { process.outputStream.close() }
        }
    }

    private fun shellQuote(value: String) = "'" + value.replace("'", "'\\''") + "'"
}

internal data class CdtBackupResult(
    val path: String,
    val sha256: String,
)
