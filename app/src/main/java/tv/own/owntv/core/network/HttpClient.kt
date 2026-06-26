package tv.own.owntv.core.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream

/**
 * Thin OkHttp wrapper for fetching M3U playlists and Xtream JSON. [get] streams the response body to
 * a block (so huge payloads are never fully buffered) and always closes the response. A per-source
 * custom User-Agent can be supplied (Phase 12 power feature).
 */
class HttpClient(private val client: OkHttpClient) {

    suspend fun <T> get(
        url: String,
        userAgent: String? = null,
        onProgress: ((Long, Long?) -> Unit)? = null,
        block: suspend (InputStream) -> T,
    ): T = withContext(Dispatchers.IO) {
        // Many IPTV panels reject requests that don't look like a media player (or that use the
        // default OkHttp UA), so we send a player-style default unless the source overrides it
        // (custom User-Agent is a Phase 12 power feature).
        val ua = userAgent?.takeIf { it.isNotBlank() } ?: DEFAULT_USER_AGENT
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", ua)
            .build()

        val call = client.newCall(request)
        val coroutineContext = currentCoroutineContext()
        val cancellationHook = coroutineContext[Job]?.invokeOnCompletion { cause ->
            if (cause is CancellationException) call.cancel()
        }
        try {
            coroutineContext.ensureActive()
            call.execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code} for ${redact(url)}")
                val body = response.body ?: throw IOException("Empty response body for ${redact(url)}")
                val totalBytes = body.contentLength().takeIf { it >= 0 }
                onProgress?.invoke(0, totalBytes)
                body.byteStream().withProgress(totalBytes, onProgress).use { input ->
                    block(input)
                }
            }
        } catch (e: IOException) {
            coroutineContext.ensureActive()
            throw e
        } finally {
            cancellationHook?.dispose()
        }
    }

    /** Mask credentials in a URL before it appears in an error/log — Xtream embeds user/pass in the query. */
    private fun redact(url: String): String =
        url.replace(Regex("(?i)(username|password|user|pass|token)=[^&]*"), "$1=***")

    /** Convenience for small responses (e.g. Xtream category lists). */
    suspend fun getText(url: String, userAgent: String? = null): String =
        get(url, userAgent) { it.readBytes().decodeToString() }

    companion object {
        /** Player-style UA that IPTV panels broadly accept. Overridable per-source in Phase 12. */
        const val DEFAULT_USER_AGENT = "VLC/3.0.20 LibVLC/3.0.20"

        /** Mask credentials for display (info overlay / logs): query params AND Xtream `/type/user/pass/`
         *  path segments, which is where live URLs embed them. */
        fun redactUrl(url: String): String = url
            .replace(Regex("(?i)(username|password|user|pass|token)=[^&]*"), "$1=***")
            .replace(Regex("(?i)(://[^/]+/(?:live|movie|series|vod)/)([^/]+)/([^/]+)/"), "$1•••/•••/")
    }
}

internal fun InputStream.withProgress(
    totalBytes: Long?,
    onProgress: ((Long, Long?) -> Unit)?,
): InputStream = if (onProgress == null) this else ProgressInputStream(this, totalBytes, onProgress)

private class ProgressInputStream(
    input: InputStream,
    private val totalBytes: Long?,
    private val onProgress: (Long, Long?) -> Unit,
) : FilterInputStream(input) {
    private var bytesRead = 0L
    private var lastNotifiedBytes = 0L
    private var lastNotifiedAt = 0L

    override fun read(): Int {
        val value = super.read()
        if (value >= 0) advance(1)
        return value
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val read = super.read(b, off, len)
        if (read > 0) advance(read.toLong())
        return read
    }

    override fun close() {
        try {
            super.close()
        } finally {
            notifyProgress(force = true)
        }
    }

    private fun advance(delta: Long) {
        bytesRead += delta
        notifyProgress()
    }

    private fun notifyProgress(force: Boolean = false) {
        val now = System.currentTimeMillis()
        val bytesDelta = bytesRead - lastNotifiedBytes
        val timeDelta = now - lastNotifiedAt
        val complete = totalBytes?.let { bytesRead >= it } == true
        if (!force && bytesDelta < PROGRESS_BYTES_STEP && timeDelta < PROGRESS_MIN_INTERVAL_MS && !complete) return
        lastNotifiedBytes = bytesRead
        lastNotifiedAt = now
        onProgress(bytesRead, totalBytes)
    }

    private companion object {
        const val PROGRESS_BYTES_STEP = 256L * 1024L
        const val PROGRESS_MIN_INTERVAL_MS = 150L
    }
}
