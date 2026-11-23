package io.washy.sm8750.tuner

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.system.Os
import android.util.AtomicFile
import android.util.Base64
import java.io.File
import java.io.OutputStream
import java.security.MessageDigest
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
    val state: PlanState?
        get() = when {
            backupStatus == "mismatch" -> PlanState.CDT_RECOVERY_REQUIRED
            disk == "corrupt" || backupStatus == "invalid" -> PlanState.CORRUPT_CDT
            disk == "slt_enabled" -> PlanState.CDT_WRITTEN
            disk == "empty" || disk == "stock" -> PlanState.STOCK_UNMODIFIED
            else -> null
        }

    val canRestore: Boolean
        get() = disk == "slt_enabled" || backupStatus in setOf("match", "enabled_match", "mismatch")
}

internal data class CdtOperationResult(
    val outcome: CdtOutcome,
    val partitionChanged: Boolean,
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
        File(context.filesDir, "backend").also { installAssets("backend", it) }
    }

    private fun installAssets(assetPath: String, directory: File) {
        if (directory.isFile) check(directory.delete()) { "无法替换 Root 后端文件 $assetPath" }
        check(directory.isDirectory || directory.mkdirs()) { "无法准备 Root 后端 $assetPath" }

        val assets = context.assets
        val names = checkNotNull(assets.list(assetPath)).toSet()
        directory.listFiles()?.forEach { existing ->
            if (existing.name !in names) existing.deleteRecursively()
        }
        for (name in names) {
            val path = "$assetPath/$name"
            val destination = File(directory, name)
            if (!assets.list(path).isNullOrEmpty()) {
                installAssets(path, destination)
                continue
            }
            val temporary = File.createTempFile("tuner-", ".tmp", directory)
            try {
                assets.open(path).use { input ->
                    temporary.outputStream().use { output -> input.copyTo(output) }
                }
                check(temporary.renameTo(destination)) { "无法准备 Root 后端文件 $path" }
            } finally {
                temporary.delete()
            }
        }
    }

    suspend fun read(): DevicePlan = parsePlan(command(listOf("read")))

    suspend fun readBenchmarkDevice(): BenchmarkDevice =
        parseBenchmarkDevice(command(listOf("read_frequencies")))

    suspend fun readCrashLogs(): String = command(listOf("crash_logs"))

    suspend fun deleteCrashEvidence(args: List<String>) {
        command(listOf("crash_logs") + args)
    }

    suspend fun exportCrashLog(destination: Uri, entry: CrashEntry) = withContext(Dispatchers.IO) {
        val output = context.contentResolver.openOutputStream(destination, "wt") ?: error("无法打开保存位置")
        output.use { it.write(entry.reportText().encodeToByteArray()) }
    }

    fun startGameTrace(tids: Set<Int>): GameTrace = GameTrace(tids) {
        val args = listOf("/system/bin/sh", File(bundle, "game_trace.sh").path) + tids.map(Int::toString)
        ProcessBuilder("su", "-c", args.joinToString(" ") { shellQuote(it) })
            .redirectErrorStream(true)
            .start()
    }

    suspend fun collectDiagnostics(): CompatibilityReport = withContext(Dispatchers.IO) {
        val installedPackage = context.packageManager.getPackageInfo(context.packageName, 0)
        val apkSha256 = runCatching {
            val digest = MessageDigest.getInstance("SHA-256")
            File(context.applicationInfo.sourceDir).inputStream().use { input ->
                val buffer = ByteArray(8192)
                var size = input.read(buffer)
                while (size != -1) {
                    digest.update(buffer, 0, size)
                    size = input.read(buffer)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }.getOrDefault("unavailable")
        val rootReport = try {
            command(listOf("diagnostics"))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        val report = buildString {
            appendLine("SunTuner compatibility report v1")
            appendLine("APP_VERSION=${installedPackage.versionName}")
            appendLine("APP_VERSION_CODE=${installedPackage.longVersionCode}")
            appendLine("APK_SHA256=$apkSha256")
            appendLine("MODEL=${Build.MODEL}")
            appendLine("MANUFACTURER=${Build.MANUFACTURER}")
            appendLine("DEVICE=${Build.DEVICE}")
            appendLine("PRODUCT=${Build.PRODUCT}")
            appendLine("BOARD=${Build.BOARD}")
            appendLine("ANDROID_RELEASE=${Build.VERSION.RELEASE}")
            appendLine("FIRMWARE_FINGERPRINT=${Build.FINGERPRINT}")
            appendLine("KERNEL=${Os.uname().release}")
            appendLine("ABIS=${Build.SUPPORTED_ABIS.joinToString(",")}")
            appendLine()
            if (rootReport != null) {
                append(rootReport)
            } else {
                appendLine("ROOT_PROBE=unavailable (check Root authorization and retry)")
            }
            val previousFailure = readCdtFailureLog()
            if (previousFailure != null) {
                appendLine()
                appendLine("[last CDT operation failure]")
                append(previousFailure)
            }
        }
        CompatibilityReport(report, rootReport)
    }

    suspend fun exportDiagnostics(destination: Uri, report: CompatibilityReport) = withContext(Dispatchers.IO) {
        val output = context.contentResolver.openOutputStream(destination, "wt")
            ?: error("无法打开诊断报告")
        output.bufferedWriter(Charsets.UTF_8).use { it.write(report.text) }
    }

    suspend fun enableCdt(bootId: String): CdtOperationResult =
        cdtCommand(listOf("cdt_enable", bootId))

    suspend fun restoreCdt(bootId: String, fallbackClean: Boolean, source: Uri?): CdtOperationResult =
        withContext(Dispatchers.IO) {
            val args = listOf("cdt_restore", bootId)
            if (source == null) {
                return@withContext cdtCommand(if (fallbackClean) args + "--fallback-clean" else args)
            }

            val staged = File.createTempFile("cdt-restore-", ".img", context.cacheDir)
            try {
                val sourceStream = context.contentResolver.openInputStream(source)
                    ?: error("无法打开所选 CDT 镜像")
                sourceStream.use { input ->
                    staged.outputStream().use { output ->
                        val buffer = ByteArray(8192)
                        var total = 0
                        while (true) {
                            val count = input.read(buffer)
                            if (count == -1) break
                            total += count
                            check(total <= 1_048_576) { "所选文件超出 CDT 镜像大小上限" }
                            output.write(buffer, 0, count)
                        }
                    }
                }
                cdtCommand(args + listOf("--from-file", staged.path))
            } finally {
                staged.delete()
            }
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
        var partitionChanged = operation.field("CDT_ACTION") in
            setOf("enabled_verified", "restored_from_backup", "restored_clean_fallback")
        if (outcome == CdtOutcome.UNKNOWN) {
            val recovery = traceCommand(listOf("cdt_recover", args[1]), log)
            if (started && recovery.failure == null) {
                outcome = when (recovery.field("CDT_RECOVERY")) {
                    "target_verified" -> CdtOutcome.VERIFIED
                    "previous_verified" -> CdtOutcome.ROLLED_BACK
                    else -> CdtOutcome.UNKNOWN
                }
                partitionChanged = outcome == CdtOutcome.VERIFIED
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
        CdtOperationResult(outcome, partitionChanged, partition, plan, message, logSaved)
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

    suspend fun backupCdt(destination: Uri): CdtBackupResult = withContext(Dispatchers.IO) {
        val output = command(listOf("cdt_backup"))
        val sha256 = output.lineSequence().singleOrNull { it.startsWith("CDT_SHA256 ") }
            ?.removePrefix("CDT_SHA256 ")
            ?: error("未返回备份校验哈希")
        val encoded = output.substringAfter("CDT_IMAGE_BEGIN\n", "").substringBefore("\nCDT_IMAGE_END", "")
        check(encoded.isNotBlank()) { "未返回完整 CDT 镜像" }
        val image = Base64.decode(encoded, Base64.DEFAULT)
        val actualSha256 = MessageDigest.getInstance("SHA-256").digest(image).joinToString("") { "%02x".format(it) }
        check(actualSha256 == sha256) { "CDT 镜像校验失败" }

        val resolver = context.contentResolver
        val stream = resolver.openOutputStream(destination, "wt") ?: error("无法打开 CDT 备份文件")
        stream.use { it.write(image) }
        val displayName = runCatching {
            resolver.query(destination, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }.getOrNull()
        CdtBackupResult(displayName, sha256)
    }

    suspend fun runLoad(
        device: BenchmarkDevice,
        config: LoadConfig,
        control: LoadControl,
        allowExtraFrequencies: Boolean,
        onLine: (String) -> Unit,
    ): LoadStats? {
        val args = listOf(
            "load", device.bootId, config.mask.toString(), config.threads.toString(), config.seconds.toString(),
            config.mode.argument, (config.targets[0] ?: 0).toString(), (config.targets[1] ?: 0).toString(),
            config.workload.argument,
        )
        return parseLoadStats(command(args, control, onLine), config, device.frequencies, allowExtraFrequencies)
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
    val displayName: String?,
    val sha256: String,
)
