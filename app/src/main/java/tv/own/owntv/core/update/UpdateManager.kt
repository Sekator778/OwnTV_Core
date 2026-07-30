package tv.own.owntv.core.update

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import tv.own.owntv.BuildConfig
import java.io.File
import java.io.IOException

/**
 * In-app updates straight from GitHub Releases: checks the repo's latest release, compares its tag
 * with the installed version, downloads the release APK, and hands it to the system installer.
 * No server of our own — the releases CI already publishes `OwnTV-vX.Y.Z.apk` (arm) and
 * `OwnTV-x86_64-vX.Y.Z.apk` per tag; the asset matching this device's ABI is chosen, so updates
 * also work on an x86_64 emulator.
 */
class UpdateManager(
    private val context: Context,
    private val client: OkHttpClient,
) {
    data class UpdateInfo(val version: String, val notes: String, val apkUrl: String)

    sealed interface Failure {
        data class CheckHttp(val code: Int) : Failure
        data object NoCompatibleApk : Failure
        data object InvalidReleaseResponse : Failure
        data object CheckNetwork : Failure
        data class DownloadHttp(val code: Int) : Failure
        data object EmptyDownload : Failure
        data object DownloadNetwork : Failure
        data object Install : Failure
    }

    sealed interface State {
        data object Idle : State
        data object Checking : State
        data object UpToDate : State
        data class Available(val info: UpdateInfo) : State
        data class Downloading(val percent: Int) : State
        data class Failed(val failure: Failure, val retryInfo: UpdateInfo? = null) : State
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private class CheckHttpException(val code: Int) : IOException()
    private class DownloadHttpException(val code: Int) : IOException()
    private class NoCompatibleApkException : IOException()
    private class InvalidReleaseResponseException : IOException()
    private class EmptyDownloadException : IOException()

    private fun failureFor(error: Throwable, checking: Boolean): Failure = when (error) {
        is CheckHttpException -> Failure.CheckHttp(error.code)
        is DownloadHttpException -> Failure.DownloadHttp(error.code)
        is NoCompatibleApkException -> Failure.NoCompatibleApk
        is InvalidReleaseResponseException -> Failure.InvalidReleaseResponse
        is EmptyDownloadException -> Failure.EmptyDownload
        else -> if (checking) Failure.CheckNetwork else Failure.DownloadNetwork
    }

    val currentVersion: String = BuildConfig.VERSION_NAME

    /** Queries GitHub's latest release; moves to Available / UpToDate / a semantic failure. */
    fun check() {
        if (_state.value is State.Checking || _state.value is State.Downloading) return
        _state.value = State.Checking
        scope.launch {
            runCatching {
                val request = Request.Builder()
                    .url("https://api.github.com/repos/$REPO/releases/latest")
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "OwnTV")
                    .build()
                client.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) throw CheckHttpException(resp.code)
                    val body = resp.body?.string().orEmpty()
                    if (body.isBlank()) throw InvalidReleaseResponseException()
                    val o = runCatching { JSONObject(body) }.getOrElse { throw InvalidReleaseResponseException() }
                    val version = o.optString("tag_name").removePrefix("v").takeIf { it.isNotBlank() }
                        ?: throw InvalidReleaseResponseException()
                    val notes = o.optString("body").take(16_000)
                    val assets = o.optJSONArray("assets") ?: throw InvalidReleaseResponseException()
                    // Releases carry one APK per ABI flavor (arm = generic, x86_64 suffixed). Never
                    // silently install an APK for the wrong ABI.
                    val wantX86 = android.os.Build.SUPPORTED_ABIS.firstOrNull() == "x86_64"
                    val apkUrl = (0 until assets.length())
                        .asSequence()
                        .mapNotNull { assets.optJSONObject(it) }
                        .mapNotNull { asset ->
                            val name = asset.optString("name")
                            val url = asset.optString("browser_download_url")
                            if (!name.endsWith(".apk") || url.isBlank()) return@mapNotNull null
                            val isX86 = name.contains("x86_64", ignoreCase = true)
                            if (isX86 == wantX86) url else null
                        }
                        .firstOrNull()
                        ?: throw NoCompatibleApkException()
                    val info = UpdateInfo(version, notes, apkUrl)
                    if (isNewer(version, currentVersion)) _state.value = State.Available(info)
                    else _state.value = State.UpToDate
                }
            }.onFailure { error ->
                Log.w(TAG, "update check failed: ${error.message}", error)
                _state.value = State.Failed(failureFor(error, checking = true))
            }
        }
    }

    /** Downloads the release APK with progress, then opens the system installer. */
    fun downloadAndInstall() {
        val info = (_state.value as? State.Available)?.info ?: return
        _state.value = State.Downloading(0)
        scope.launch {
            runCatching {
                val dir = File(context.filesDir, "updates").apply { mkdirs() }
                val out = File(dir, "owntv-update.apk")
                val request = Request.Builder().url(info.apkUrl).header("User-Agent", "OwnTV").build()
                client.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) throw DownloadHttpException(resp.code)
                    val body = resp.body ?: throw EmptyDownloadException()
                    val total = body.contentLength()
                    var copied = 0L
                    body.byteStream().use { input ->
                        out.outputStream().use { output ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                output.write(buf, 0, n)
                                copied += n
                                if (total > 0) _state.value = State.Downloading((copied * 100 / total).toInt())
                            }
                        }
                    }
                    if (copied == 0L) throw EmptyDownloadException()
                }
                runCatching { install(out) }.getOrElse { throw InstallException(it) }
                _state.value = State.Available(info) // dialog stays sane if the user cancels install
            }.onFailure { error ->
                Log.w(TAG, "update download failed: ${error.message}", error)
                val failure = if (error is InstallException) Failure.Install else failureFor(error, checking = false)
                _state.value = State.Failed(failure, retryInfo = info)
            }
        }
    }

    private fun install(apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /** Retries the failed phase without losing a successfully resolved release asset. */
    fun retry() {
        val failed = _state.value as? State.Failed ?: return
        val info = failed.retryInfo
        if (info != null && failed.failure !is Failure.CheckHttp && failed.failure !is Failure.NoCompatibleApk &&
            failed.failure !is Failure.InvalidReleaseResponse && failed.failure !is Failure.CheckNetwork
        ) {
            _state.value = State.Available(info)
            downloadAndInstall()
        } else {
            check()
        }
    }

    fun reset() {
        if (_state.value !is State.Downloading) _state.value = State.Idle
    }

    /** Numeric segment-wise compare: "1.10.0" > "1.9.3"; non-numeric junk compares as 0. */
    private fun isNewer(remote: String, local: String): Boolean {
        val r = remote.split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        val l = local.split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(r.size, l.size)) {
            val a = r.getOrElse(i) { 0 }
            val b = l.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    private class InstallException(cause: Throwable) : IOException(cause)

    companion object {
        private const val TAG = "UpdateManager"
        const val REPO = "ahXN00/OwnTV"
    }
}
