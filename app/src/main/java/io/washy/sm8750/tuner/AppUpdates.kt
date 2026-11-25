package io.washy.sm8750.tuner

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

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
        DOWNLOAD_PENDING("更新已在下载队列中"),
        DOWNLOAD_READY("更新已下载，可在下载目录中打开"),
        DOWNLOAD_FAILED("无法开始下载，请稍后重试"),
    }
    data class Available(val version: String, val notes: String, val apkUrl: String) : UpdateState {
        val apkName: String get() = apkUrl.substringAfterLast('/')
    }
}

internal class AppUpdatesViewModel : ViewModel() {
    private val mutableState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state = mutableState.asStateFlow()

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

    fun download(context: Context, update: UpdateState.Available) {
        if (!mutableState.compareAndSet(update, UpdateState.StartingDownload)) return
        val appContext = context.applicationContext
        viewModelScope.launch {
            mutableState.value = withContext(Dispatchers.IO) {
                runCatching { enqueueUpdate(appContext, update) }
                    .getOrDefault(UpdateState.Message.DOWNLOAD_FAILED)
            }
        }
    }
}

private fun enqueueUpdate(context: Context, update: UpdateState.Available): UpdateState.Message {
    val manager = context.getSystemService(DownloadManager::class.java)
        ?: return UpdateState.Message.DOWNLOAD_FAILED
    manager.query(DownloadManager.Query()).use { downloads ->
        if (downloads != null) {
            val uri = downloads.getColumnIndexOrThrow(DownloadManager.COLUMN_URI)
            val status = downloads.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
            val id = downloads.getColumnIndexOrThrow(DownloadManager.COLUMN_ID)
            while (downloads.moveToNext()) {
                if (downloads.getString(uri) != update.apkUrl) continue
                when (downloads.getInt(status)) {
                    DownloadManager.STATUS_PENDING, DownloadManager.STATUS_RUNNING, DownloadManager.STATUS_PAUSED ->
                        return UpdateState.Message.DOWNLOAD_PENDING
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        val exists = runCatching { manager.openDownloadedFile(downloads.getLong(id)).close() }.isSuccess
                        if (exists) return UpdateState.Message.DOWNLOAD_READY
                    }
                }
            }
        }
    }
    val request = DownloadManager.Request(Uri.parse(update.apkUrl))
        .setTitle(update.apkName)
        .setMimeType("application/vnd.android.package-archive")
        .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, update.apkName)
        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
    return if (manager.enqueue(request) >= 0) UpdateState.Message.DOWNLOAD_STARTED
    else UpdateState.Message.DOWNLOAD_FAILED
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
