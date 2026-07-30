package tv.own.owntv.core.player

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import tv.own.owntv.core.i18n.AppLocale
import tv.own.owntv.core.i18n.LocaleStore

// Hands a stream URL (or a downloaded file path) to an external video player (VLC, MX Player, etc.)
// via ACTION_VIEW. When it fires, the in-app player is bypassed entirely (the fullscreen player
// never opens, because OwnTVPlayer is never told to play).
//
// Network URLs (http/https/rtsp/rtmp/udp/mms) are handed over verbatim. Local download paths are
// shared through the app FileProvider with a read permission grant.
//
// Limitations: no custom User-Agent / Referer / headers can be attached to an ACTION_VIEW intent,
// so streams needing per-source auth headers may fail; no resume position or prev/next queue.
class ExternalPlayerLauncher(private val context: Context) {

    // Open url externally. Returns true if an external app was actually launched.
    fun launch(url: String, title: String? = null, subtitle: String? = null): Boolean {
        val localized = localizedContext()
        val uri = uriFor(url)
        if (uri == null) {
            toast(localized.getString(tv.own.owntv.R.string.player_external_could_not_open))
            return false
        }
        // Try the precise MIME first, then widen. VLC and MX Player advertise `video/*` but NOT every
        // specific type: a live channel ending `.ts` (video/mp2t) or `.m3u8` (application/x-mpegURL)
        // matched no activity at all and reported "no external player installed", even though the same
        // players happily took a movie's video/mp4. Falling back to `video/*`, and finally to the URI
        // with no type at all, gets live streams to the same players without touching the movie path.
        for (mime in mimeCandidates(url)) {
            val intent = Intent(Intent.ACTION_VIEW)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (mime != null) intent.setDataAndType(uri, mime) else intent.data = uri

            val targets = context.packageManager.queryIntentActivities(intent, 0)
            if (targets.isEmpty()) continue
            return if (targets.size == 1) {
                startActivity(intent)
            } else {
                val chooserTitle = if (title != null && subtitle != null) {
                    localized.getString(tv.own.owntv.R.string.player_external_play_with_item, title, subtitle)
                } else {
                    title ?: localized.getString(tv.own.owntv.R.string.player_external_play_with)
                }
                startActivity(
                    Intent.createChooser(intent, chooserTitle)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
        toast(localized.getString(tv.own.owntv.R.string.player_external_not_found))
        return false
    }

    // Whether any installed app can handle a video URL.
    fun isAvailable(): Boolean {
        val probe = Intent(Intent.ACTION_VIEW)
            .setDataAndType(Uri.parse("https://example.com/video.mp4"), "video/mp4")
        return context.packageManager.queryIntentActivities(probe, 0).isNotEmpty()
    }

    private fun startActivity(intent: Intent): Boolean =
        runCatching { context.startActivity(intent) }
            .onFailure { toast(localizedContext().getString(tv.own.owntv.R.string.player_external_not_found)) }
            .isSuccess

    // Network scheme: hand the URL over verbatim; otherwise treat as a local file path.
    private fun uriFor(url: String): Uri? {
        val scheme = Uri.parse(url).scheme?.lowercase()
        if (scheme == "http" || scheme == "https" || scheme == "rtsp" ||
            scheme == "rtmp" || scheme == "udp" || scheme == "mms"
        ) {
            return Uri.parse(url)
        }
        val file = File(url)
        if (!file.exists()) return null
        val authority = context.packageName + ".fileprovider"
        return runCatching {
            FileProvider.getUriForFile(context, authority, file)
        }.getOrNull()
    }

    // MIME types to offer for a URL, most specific first, ending with an untyped attempt. Duplicates
    // are dropped so a plain video/* URL doesn't query the same intent twice.
    private fun mimeCandidates(url: String): List<String?> {
        val path = url.substringBefore('?').substringAfterLast('/', "")
        val ext = path.substringAfterLast('.', "").lowercase()
        val specific = when (ext) {
            "m3u8", "m3u" -> "application/x-mpegURL"
            "ts", "m2t", "mts" -> "video/mp2t"
            "mp4", "m4v", "mov", "3gp" -> "video/mp4"
            else -> "video/*"
        }
        return listOf(specific, "video/*", null).distinct()
    }

    private fun localizedContext(): Context =
        AppLocale.wrap(context, LocaleStore.from(context).readBlocking())

    private fun toast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }
}
