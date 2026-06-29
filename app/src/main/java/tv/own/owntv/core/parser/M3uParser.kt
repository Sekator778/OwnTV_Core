package tv.own.owntv.core.parser

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
    /** True when the entry is explicitly tagged as VOD (movie or series), not a live channel. */
    val isVod: Boolean get() = type == "vod" || tvgType == "movie" || tvgType == "series" || type == "movie"
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
        var header = M3uHeader(urlTvg = null)
        var pending: PendingExtInf? = null

        input.bufferedReader().forEachLineSafe { raw ->
            val line = raw.trim()
            when {
                line.isEmpty() -> Unit

                line.startsWith("#EXTM3U") -> {
                    header = M3uHeader(urlTvg = attr(line, "url-tvg") ?: attr(line, "x-tvg-url"))
                }

                line.startsWith("#EXTINF") -> {
                    pending = PendingExtInf(
                        name = line.substringAfterLast(',').trim(),
                        logo = attr(line, "tvg-logo"),
                        groupTitle = attr(line, "group-title"),
                        tvgId = attr(line, "tvg-id"),
                        tvgChno = attr(line, "tvg-chno")?.toIntOrNull(),
                        type = attr(line, "type"),
                        tvgType = attr(line, "tvg-type"),
                        catchup = attr(line, "catchup") ?: attr(line, "catchup-type"),
                        catchupSource = attr(line, "catchup-source"),
                        catchupDays = attr(line, "catchup-days")?.toIntOrNull(),
                    )
                }

                line.startsWith("#") -> Unit // other directives (e.g. #EXTGRP) ignored for now

                else -> {
                    // A URL line completes the pending channel.
                    val p = pending
                    if (p != null && p.name.isNotEmpty()) {
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
                    }
                    pending = null
                }
            }
        }
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

    /** Extracts a `key="value"` attribute from an EXTINF/EXTM3U line. */
    private fun attr(line: String, key: String): String? {
        val token = "$key=\""
        val start = line.indexOf(token)
        if (start < 0) return null
        val from = start + token.length
        val end = line.indexOf('"', from)
        if (end < 0) return null
        return line.substring(from, end).takeIf { it.isNotBlank() }
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
