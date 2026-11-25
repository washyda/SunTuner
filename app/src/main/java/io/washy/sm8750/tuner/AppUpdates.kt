package io.washy.sm8750.tuner

import android.app.Application
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import androidx.core.content.FileProvider
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

internal const val SUN_TUNER_REPOSITORY = "https://github.com/washyda/SunTuner"
internal const val SUN_TUNER_RELEASES = "$SUN_TUNER_REPOSITORY/releases"

internal sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object StartingDownload : UpdateState
    enum class Message(val text: String) : UpdateState {
        UP_TO_DATE("已是最新版本"),
        CHECK_FAILED("检查更新失败，请稍后重试"),
        DOWNLOAD_STARTED("已开始下载更新"),
        DOWNLOAD_INTERRUPTED("下载未完成，请重试"),
        DOWNLOAD_FAILED("无法开始下载，请稍后重试"),
    }
    data class Available(val version: String, val notes: String, val apkUrl: String) : UpdateState {
        val apkName: String get() = apkUrl.substringAfterLast('/')
    }
}

internal const val APK_MIME_TYPE = "application/vnd.android.package-archive"

internal data class PendingUpdate(val id: Long, val version: String, val offered: Boolean = false) {
    val apkName: String get() = "SunTuner-$version-release.apk"
}

private fun updatePreferences(context: Context) =
    context.getSharedPreferences("app_updates", Context.MODE_PRIVATE)

private fun readPendingUpdate(context: Context): PendingUpdate? {
    val preferences = updatePreferences(context)
    val id = preferences.getLong("download_id", -1)
    val version = preferences.getString("version", null) ?: return null
    if (id < 0 || ReleaseVersion.parse(version) == null) return null
    return PendingUpdate(id, version, preferences.getBoolean("offered", false))
}

private fun writePendingUpdate(context: Context, pending: PendingUpdate?) {
    updatePreferences(context).edit {
        if (pending == null) {
            clear()
        } else {
            putLong("download_id", pending.id)
            putString("version", pending.version)
            putBoolean("offered", pending.offered)
        }
    }
}

private fun downloadStatus(manager: DownloadManager, id: Long): Int? =
    manager.query(DownloadManager.Query().setFilterById(id))?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
        else null
    }

private fun updateFile(context: Context, pending: PendingUpdate): File? =
    context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.let { File(it, "updates/${pending.apkName}") }

private fun removeCachedUpdate(context: Context, pending: PendingUpdate) {
    context.getSystemService(DownloadManager::class.java)?.remove(pending.id)
    val file = updateFile(context, pending)
    if (file == null || !file.exists() || file.delete()) {
        if (readPendingUpdate(context)?.id == pending.id) writePendingUpdate(context, null)
    }
}

private fun isInstalled(context: Context, pending: PendingUpdate): Boolean {
    val installed = context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    val current = ReleaseVersion.parse(installed) ?: return false
    val target = ReleaseVersion.parse(pending.version) ?: return false
    return current >= target
}

internal class UpdateInstalledReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val result = goAsync()
        val app = context.applicationContext
        thread {
            try {
                val pending = readPendingUpdate(app)
                if (pending != null && isInstalled(app, pending)) {
                    runCatching { removeCachedUpdate(app, pending) }
                }
            } finally {
                result.finish()
            }
        }
    }
}

internal class AppUpdatesViewModel(private val app: Application) : AndroidViewModel(app) {
    private val manager = checkNotNull(app.getSystemService(DownloadManager::class.java))
    private val mutableState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state = mutableState.asStateFlow()

    private val mutablePending = MutableStateFlow(readPendingUpdate(app))
    val pending = mutablePending.asStateFlow()

    fun dismiss(state: UpdateState) {
        mutableState.compareAndSet(state, UpdateState.Idle)
    }

    fun check(installedVersion: String) {
        if (mutableState.value == UpdateState.Checking || mutableState.value == UpdateState.StartingDownload) return
        mutableState.value = UpdateState.Checking
        viewModelScope.launch {
            mutableState.value = try {
                withContext(Dispatchers.IO) { checkGitHubReleases(installedVersion) }
            } catch (_: IOException) {
                UpdateState.Message.CHECK_FAILED
            } catch (_: JSONException) {
                UpdateState.Message.CHECK_FAILED
            }
        }
    }

    fun download(update: UpdateState.Available) {
        if (!mutableState.compareAndSet(update, UpdateState.StartingDownload)) return
        val previous = mutablePending.value
        viewModelScope.launch {
            val download = withContext(Dispatchers.IO) {
                runCatching { enqueueUpdate(update, previous) }.getOrNull()
            }
            if (download == null) {
                mutableState.value = UpdateState.Message.DOWNLOAD_FAILED
            } else {
                writePendingUpdate(app, download)
                mutablePending.value = download
                mutableState.value = if (download.id == previous?.id) UpdateState.Idle
                else UpdateState.Message.DOWNLOAD_STARTED
            }
        }
    }

    private fun enqueueUpdate(update: UpdateState.Available, previous: PendingUpdate?): PendingUpdate? {
        if (previous?.version == update.version) {
            when (downloadStatus(manager, previous.id)) {
                DownloadManager.STATUS_PENDING, DownloadManager.STATUS_RUNNING, DownloadManager.STATUS_PAUSED ->
                    return previous.copy(offered = false)
                DownloadManager.STATUS_SUCCESSFUL ->
                    if (updateFile(app, previous)?.isFile == true) return previous.copy(offered = false)
            }
            removeCachedUpdate(app, previous)
        }
        val request = DownloadManager.Request(update.apkUrl.toUri())
            .setTitle(update.apkName)
            .setMimeType(APK_MIME_TYPE)
            .setDestinationInExternalFilesDir(app, Environment.DIRECTORY_DOWNLOADS, "updates/${update.apkName}")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        val id = manager.enqueue(request)
        if (id < 0) return null
        if (previous != null && previous.version != update.version) {
            runCatching { removeCachedUpdate(app, previous) }
        }
        return PendingUpdate(id, update.version)
    }

    suspend fun awaitInstaller(download: PendingUpdate): Uri? {
        if (isInstalled(app, download)) {
            finishDownload(download)
            return null
        }
        if (download.offered) return null
        while (true) {
            val status = withContext(Dispatchers.IO) { runCatching { downloadStatus(manager, download.id) }.getOrNull() }
            when (status) {
                DownloadManager.STATUS_PENDING, DownloadManager.STATUS_RUNNING, DownloadManager.STATUS_PAUSED -> delay(700)
                DownloadManager.STATUS_SUCCESSFUL -> {
                    val uri = withContext(Dispatchers.IO) {
                        val file = updateFile(app, download)?.takeIf { it.isFile && it.length() > 0 }
                        file?.let { runCatching { FileProvider.getUriForFile(app, "${app.packageName}.updates", it) }.getOrNull() }
                    }
                    if (uri != null) return uri
                    break
                }
                else -> break
            }
        }
        finishDownload(download)
        mutableState.value = UpdateState.Message.DOWNLOAD_INTERRUPTED
        return null
    }

    private suspend fun finishDownload(download: PendingUpdate) {
        withContext(Dispatchers.IO) { runCatching { removeCachedUpdate(app, download) } }
        mutablePending.compareAndSet(download, null)
    }

    fun markInstallerOffered(download: PendingUpdate): Boolean {
        val offered = download.copy(offered = true)
        if (!mutablePending.compareAndSet(download, offered)) return false
        writePendingUpdate(app, offered)
        return true
    }
}

private fun checkGitHubReleases(installedVersion: String): UpdateState {
    val connection = URL("https://api.github.com/repos/washyda/SunTuner/releases?per_page=100")
        .openConnection() as HttpURLConnection
    return try {
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.setRequestProperty("User-Agent", "SunTuner/$installedVersion")
        val body = connection.inputStream.bufferedReader().use { it.readText() }
        findAppUpdate(JSONArray(body), installedVersion)
    } finally {
        connection.disconnect()
    }
}

internal fun findAppUpdate(releases: JSONArray, installedVersion: String): UpdateState {
    val installed = ReleaseVersion.parse(installedVersion) ?: return UpdateState.Message.CHECK_FAILED
    val latest = (0 until releases.length()).asSequence()
        .map(releases::getJSONObject)
        .filterNot { it.getBoolean("draft") }
        .mapNotNull { release ->
            val tag = release.getString("tag_name")
            if (!tag.startsWith("v")) return@mapNotNull null
            val name = tag.removePrefix("v")
            val version = ReleaseVersion.parse(name) ?: return@mapNotNull null
            val apkName = "SunTuner-$name-release.apk"
            val apkUrl = "$SUN_TUNER_RELEASES/download/$tag/$apkName"
            val assets = release.getJSONArray("assets")
            val hasApk = (0 until assets.length()).any { index ->
                val asset = assets.getJSONObject(index)
                asset.optString("name") == apkName && asset.optString("state") == "uploaded" &&
                    asset.optLong("size") > 0 && asset.optString("browser_download_url") == apkUrl
            }
            if (!hasApk) return@mapNotNull null
            version to UpdateState.Available(name, release.optString("body", ""), apkUrl)
        }
        .maxByOrNull { it.first }
        ?: return UpdateState.Message.CHECK_FAILED
    return if (latest.first > installed) latest.second else UpdateState.Message.UP_TO_DATE
}

private data class ReleaseVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val stable: Boolean,
    val beta: Int,
) : Comparable<ReleaseVersion> {
    override fun compareTo(other: ReleaseVersion): Int =
        compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch }, { it.stable }, { it.beta })

    companion object {
        private val pattern = Regex("""(\d+)\.(\d+)\.(\d+)(?:(beta)(?:[.-]?(\d+))?)?""")

        fun parse(name: String): ReleaseVersion? {
            val match = pattern.matchEntire(name) ?: return null
            val (major, minor, patch, betaLabel, betaNumber) = match.destructured
            return ReleaseVersion(
                major.toIntOrNull() ?: return null,
                minor.toIntOrNull() ?: return null,
                patch.toIntOrNull() ?: return null,
                stable = betaLabel.isEmpty(),
                beta = if (betaNumber.isEmpty()) 0 else betaNumber.toIntOrNull() ?: return null,
            )
        }
    }
}
