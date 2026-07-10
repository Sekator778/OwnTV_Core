package tv.own.owntv.core.parser

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.BufferedReader
import java.io.InputStream

/** A single entry parsed from an M3U playlist — can be a live channel or a VOD movie/series. */
data class M3uEntry(
    val name: String,
    val streamUrl: String,
    val logo: String?,
    val groupTitle: String?,
    val tvgId: String?,
    val tvgChno: Int?,
    /** `type` attribute — "vod" / "series" / "movie" — tells us whether this is VOD content. */
    val type: String?,
    /** `tvg-type` attribute — alternative VOD type marker used by some playlists. */
    val tvgType: String?,
    /** `catchup` type (e.g. "default"/"append"/"shift") — its presence marks the channel as having archive. */
    val catchup: String?,
    /** `catchup-source` URL template (placeholders like `${start}`/`{utc}` filled at playback). */
    val catchupSource: String?,
    /** `catchup-days` — how many days back the archive goes. */
    val catchupDays: Int?,
) {
    /** Tagged as series content — per-episode entries like "Show S01E05" grouped into shows. */
    val isSeries: Boolean get() = type == "series" || tvgType == "series"

    /** True when the entry is explicitly tagged as VOD (movie or series), not a live channel. */
    val isVod: Boolean get() =
        isSeries || type == "vod" || type == "movie" || tvgType == "vod" || tvgType == "movie"
}

/** Header info from the `#EXTM3U` line (notably the `url-tvg` EPG URL). */
data class M3uHeader(val urlTvg: String?)

/**
 * Streaming M3U / M3U8 parser. Reads line-by-line (never loads the whole file) and invokes [onEntry]
 * for each channel, so the sync layer can batch-insert without buffering 340k items in memory.
 * Returns the parsed header.
 *
 * Recognized per-channel attributes on `#EXTINF`: `tvg-id`, `tvg-name`, `tvg-logo`, `tvg-chno`,
 * `group-title`, plus the display name after the comma and the following URL line.
 */
class M3uParser {

    suspend fun parse(input: InputStream, onEntry: suspend (M3uEntry) -> Unit): M3uHeader {
        // Per-line timing costs millions of elapsedRealtime() syscalls on a 100k+ playlist, so the
        // detailed metrics only run when the tag is debuggable (`setprop log.tag.M3uParser DEBUG`).
        val debug = Log.isLoggable(TAG, Log.DEBUG)
        val startedAt = SystemClock.elapsedRealtime()
        var lastLogAt = startedAt
        var lastParseOrReadMs = 0L
        var lastCallbackMs = 0L
        var entries = 0
        val metrics = ParseMetrics()
        var header = M3uHeader(urlTvg = null)
        var pending: PendingExtInf? = null
        if (debug) Log.d(TAG, "parse start")

        input.bufferedReader().forEachLineSafe { raw ->
            val parseStart = if (debug) SystemClock.elapsedRealtime() else 0L
            val line = raw.trim()
            var callbackHandled = false
            when {
                line.isEmpty() -> Unit

                line.startsWith("#EXTM3U") -> {
                    val attrs = parseAttrs(line)
                    header = M3uHeader(urlTvg = attrs.attr("url-tvg") ?: attrs.attr("x-tvg-url"))
                }

                line.startsWith("#EXTINF") -> {
                    val attrs = parseAttrs(line)
                    pending = PendingExtInf(
                        name = line.substringAfterLast(',').trim(),
                        logo = attrs.attr("tvg-logo"),
                        groupTitle = attrs.attr("group-title"),
                        tvgId = attrs.attr("tvg-id"),
                        tvgChno = attrs.attr("tvg-chno")?.toIntOrNull(),
                        type = attrs.attr("type"),
                        tvgType = attrs.attr("tvg-type"),
                        catchup = attrs.attr("catchup") ?: attrs.attr("catchup-type"),
                        catchupSource = attrs.attr("catchup-source"),
                        catchupDays = attrs.attr("catchup-days")?.toIntOrNull(),
                    )
                }

                line.startsWith("#") -> Unit // other directives (e.g. #EXTGRP) ignored for now

                else -> {
                    // A URL line completes the pending channel.
                    val p = pending
                    if (p != null && p.name.isNotEmpty()) {
                        if (debug) metrics.parseOrReadMs += SystemClock.elapsedRealtime() - parseStart
                        val callbackStart = if (debug) SystemClock.elapsedRealtime() else 0L
                        try {
                            onEntry(
                                M3uEntry(
                                    name = p.name,
                                    streamUrl = line,
                                    logo = p.logo,
                                    groupTitle = p.groupTitle,
                                    tvgId = p.tvgId,
                                    tvgChno = p.tvgChno,
                                    type = p.type,
                                    tvgType = p.tvgType,
                                    catchup = p.catchup,
                                    catchupSource = p.catchupSource,
                                    catchupDays = p.catchupDays,
                                ),
                            )
                        } finally {
                            if (debug) metrics.callbackMs += SystemClock.elapsedRealtime() - callbackStart
                        }
                        entries++
                        callbackHandled = true
                    }
                    pending = null
                }
            }

            if (debug) {
                if (!callbackHandled) {
                    metrics.parseOrReadMs += SystemClock.elapsedRealtime() - parseStart
                }

                if (entries > 0 && entries % STREAM_LOG_ITEM_STEP == 0) {
                    val now = SystemClock.elapsedRealtime()
                    val parseOrReadDelta = metrics.parseOrReadMs - lastParseOrReadMs
                    val callbackDelta = metrics.callbackMs - lastCallbackMs
                    Log.d(
                        TAG,
                        "parse progress entries=$entries deltaMs=${now - lastLogAt} " +
                            "parseOrReadMs=$parseOrReadDelta callbackMs=$callbackDelta " +
                            "totalParseOrReadMs=${metrics.parseOrReadMs} totalCallbackMs=${metrics.callbackMs} " +
                            "totalMs=${now - startedAt}",
                    )
                    lastLogAt = now
                    lastParseOrReadMs = metrics.parseOrReadMs
                    lastCallbackMs = metrics.callbackMs
                }
            }
        }
        Log.i(TAG, "parse end entries=$entries totalMs=${SystemClock.elapsedRealtime() - startedAt}")
        return header
    }

    private data class PendingExtInf(
        val name: String,
        val logo: String?,
        val groupTitle: String?,
        val tvgId: String?,
        val tvgChno: Int?,
        val type: String?,
        val tvgType: String?,
        val catchup: String?,
        val catchupSource: String?,
        val catchupDays: Int?,
    )

    private data class ParseMetrics(
        var parseOrReadMs: Long = 0L,
        var callbackMs: Long = 0L,
    )

    /**
     * Extracts every `key="value"` attribute from an EXTINF/EXTM3U line in one left-to-right scan
     * (the old per-key `indexOf` re-scanned the line ~10× per entry — the dominant parse cost on huge
     * playlists — and could also mis-match a key that is a suffix of another, e.g. `type` inside
     * `tvg-type="…"`). Keys are matched exactly and case-sensitively, as before.
     */
    private fun parseAttrs(line: String): Map<String, String> {
        var eq = line.indexOf("=\"")
        if (eq < 0) return emptyMap()
        val map = HashMap<String, String>(12)
        while (eq >= 0) {
            val valueEnd = line.indexOf('"', eq + 2)
            if (valueEnd < 0) break
            var keyStart = eq
            while (keyStart > 0) {
                val c = line[keyStart - 1]
                if (c.isLetterOrDigit() || c == '-' || c == '_') keyStart-- else break
            }
            if (keyStart < eq) map[line.substring(keyStart, eq)] = line.substring(eq + 2, valueEnd)
            eq = line.indexOf("=\"", valueEnd + 1)
        }
        return map
    }

    private fun Map<String, String>.attr(key: String): String? = this[key]?.takeIf { it.isNotBlank() }

    private companion object {
        private const val TAG = "M3uParser"
        private const val STREAM_LOG_ITEM_STEP = 10_000
    }
}

private suspend inline fun BufferedReader.forEachLineSafe(action: suspend (String) -> Unit) {
    val ctx = currentCoroutineContext()
    try {
        var line = readLine()
        while (line != null) {
            ctx.ensureActive()
            action(line)
            line = readLine()
        }
    } finally {
        close()
    }
}
