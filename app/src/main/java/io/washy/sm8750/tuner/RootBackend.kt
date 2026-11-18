package io.washy.sm8750.tuner

import android.content.Context
import java.io.File
import java.io.OutputStream
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

internal class RootBackend(private val context: Context) {
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

    suspend fun enableCdt(bootId: String): DevicePlan =
        parsePlan(command(listOf("cdt_enable", bootId)))

    suspend fun restoreCdt(bootId: String, fallbackClean: Boolean = false): DevicePlan {
        val args = if (fallbackClean) listOf("cdt_restore", bootId, "--fallback-clean") else listOf("cdt_restore", bootId)
        return parsePlan(command(args))
    }

    suspend fun reboot(bootId: String) {
        command(listOf("reboot", bootId))
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
            if (process.waitFor() != 0) {
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
