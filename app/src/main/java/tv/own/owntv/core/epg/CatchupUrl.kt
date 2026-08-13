package tv.own.owntv.core.epg

import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.database.entity.EpgProgrammeEntity
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.model.SourceType
import tv.own.owntv.core.parser.XtreamClient
import java.util.TimeZone

/**
 * Builds catch-up / archive playback URLs for a past programme (#13 / Catch-up TV).
 *
 * Two provider conventions:
 *  - **M3U** carries a `catchup-source` URL *template* with placeholders (`${start}`, `{utc}`,
 *    `{Y}-{m}-{d}`, …) that [fromTemplate] fills from the programme's start/end. A `catchup="append"`
 *    channel instead *appends* its template to the live URL — that join is the caller's job.
 *  - **Xtream** has no template; its timeshift URL is built from the source credentials (see
 *    `XtreamClient.timeshiftUrl`).
 *
 * Pure string work, no I/O — easy to unit-test and reuse from any layer.
 */
object CatchupUrl {

    /** Unix-second tokens (both `${name}` and `{name}`, case-insensitive) → their value. */
    private val START_TOKENS = setOf("start", "utc", "timestamp", "start-timestamp", "utcstart")
    private val END_TOKENS = setOf("end", "utcend", "end-timestamp", "stop")

    /** "Now", in unix seconds. `catchup-source="…?utc={utc}&lutc={lutc}"` is one of the most common
     *  templates in the wild, and an unsubstituted `{lutc}` reached the provider verbatim (F17). */
    private val NOW_TOKENS = setOf("lutc", "now", "timenow", "currenttime")

    private val TOKEN = Regex("\\$?\\{([A-Za-z_][A-Za-z0-9_-]*)\\}")

    /**
     * Fill an M3U `catchup-source` [template] for the programme spanning [startMs]..[endMs].
     * Recognised placeholders (UTC): unix start/end/duration/offset, and date parts Y/m/d/H/M/S of the
     * start. Unknown placeholders are left untouched so unusual templates degrade gracefully.
     */
    fun fromTemplate(template: String, startMs: Long, endMs: Long, nowMs: Long = System.currentTimeMillis()): String {
        val startS = startMs / 1000
        val endS = endMs / 1000
        val durationS = ((endMs - startMs) / 1000).coerceAtLeast(0)
        val offsetS = ((nowMs - startMs) / 1000).coerceAtLeast(0)
        val cal = java.util.Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = startMs }
        fun two(n: Int) = n.toString().padStart(2, '0')
        val dateParts = mapOf(
            "y" to cal.get(java.util.Calendar.YEAR).toString(),
            "m" to two(cal.get(java.util.Calendar.MONTH) + 1),
            "d" to two(cal.get(java.util.Calendar.DAY_OF_MONTH)),
            "h" to two(cal.get(java.util.Calendar.HOUR_OF_DAY)),
            "min" to two(cal.get(java.util.Calendar.MINUTE)),
            "s" to two(cal.get(java.util.Calendar.SECOND)),
        )
        return TOKEN.replace(template) { match ->
            val raw = match.groupValues[1]
            val name = raw.lowercase()
            when {
                name in START_TOKENS -> startS.toString()
                name in END_TOKENS -> endS.toString()
                name in NOW_TOKENS -> (nowMs / 1000).toString()
                name == "duration" -> durationS.toString()
                name == "offset" -> offsetS.toString()
                // Date parts are single-letter and case-sensitive in the wild (M = month/minute), so map
                // by the ORIGINAL token: Y/m/d/H/M/S.
                raw == "Y" -> dateParts["y"]!!
                raw == "m" -> dateParts["m"]!!
                raw == "d" -> dateParts["d"]!!
                raw == "H" -> dateParts["h"]!!
                raw == "M" -> dateParts["min"]!!
                raw == "S" -> dateParts["s"]!!
                else -> match.value // unknown placeholder: leave as-is
            }
        }
    }

    /**
     * Build the catch-up URL for a [programme] on [channel] given its resolved [source] and the [tz] the
     * provider expects timeshift timestamps in. Shared by the Guide and Live TV catch-up entry points.
     */
    fun forSource(
        channel: ChannelEntity,
        programme: EpgProgrammeEntity,
        source: SourceEntity,
        tz: TimeZone,
        xtream: XtreamClient,
    ): String? {
        if (!channel.catchup) return null
        return when (source.type) {
            SourceType.XTREAM -> channel.remoteId?.let { streamId ->
                val durationMin = (((programme.stopMs - programme.startMs) / 60_000L).toInt()).coerceAtLeast(1)
                // Always `.ts`, and deliberately NOT subject to "Prefer HLS": that setting describes the
                // live edge, which panels remux to HLS on demand. The timeshift server is a different
                // thing — it serves recordings off disk with no HLS repackager in front of it, so asking
                // it for `.m3u8` reliably produces an error page rather than a playlist. Tying the two
                // together made "Prefer HLS" silently break catch-up for accounts whose live TV was fine.
                xtream.timeshiftUrl(source, streamId, programme.startMs, durationMin, tz, "ts")
            }
            // F17 — the catch-up TYPE is now persisted, so `append` (and the templateless styles)
            // finally reach [forM3u]; it used to be hardcoded to null here.
            SourceType.M3U -> forM3u(
                channel.streamUrl,
                channel.catchupType,
                channel.catchupSource,
                programme.startMs,
                programme.stopMs,
                tz,
            )
            else -> null
        }
    }

    private val TIMESHIFT_PATH = Regex(
        "^(.+?)/timeshift/([^/]+)/([^/]+)/([^/]+)/([^/]+)/([^/.]+)\\.(?:ts|m3u8)$",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Convert a path-style Xtream catch-up URL
     * (`…/timeshift/user/pass/duration/start/streamId.ts`) into the PHP query form
     * (`…/streaming/timeshift.php?username=…&password=…&stream=…&start=…&duration=…`) that some panels
     * require instead. Returns null if [url] isn't a recognised timeshift path. The player tries this
     * automatically when the path form is rejected (issue: some panels reply with an HTML error page).
     */
    fun timeshiftPhpAlternate(url: String?): String? {
        val g = url?.let { TIMESHIFT_PATH.find(it) }?.groupValues ?: return null
        return "${g[1]}/streaming/timeshift.php?username=${g[2]}&password=${g[3]}&stream=${g[6]}&start=${g[5]}&duration=${g[4]}"
    }

    /**
     * Resolve an M3U channel's catch-up URL from its `catchup` [catchupType] and `catchup-source`
     * [catchupSource] template. Returns null when nothing usable can be built.
     *
     * Four conventions, in the order playlists use them:
     *  - **append** — the template is a query fragment joined onto the [liveUrl] (the most common form,
     *    and previously unreachable because the type was never stored, F17).
     *  - **shift / timeshift** — usually typeless: append the standard `?utc=…&lutc=…` pair.
     *  - **flussonic** — no template at all; the archive lives at a different path on the same server.
     *  - **xc** — an Xtream panel behind an M3U playlist: rebuild the URL as `timeshift.php`.
     *
     * A plain template with no type is substituted as-is, exactly as before.
     */
    fun forM3u(
        liveUrl: String,
        catchupType: String?,
        catchupSource: String?,
        startMs: Long,
        endMs: Long,
        tz: TimeZone = TimeZone.getTimeZone("UTC"),
    ): String? {
        val template = catchupSource?.takeIf { it.isNotBlank() }
        val type = catchupType?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        return when {
            template != null && type == "append" -> joinQuery(liveUrl, fromTemplate(template, startMs, endMs))
            template != null -> fromTemplate(template, startMs, endMs)
            type == "shift" || type == "timeshift" -> joinQuery(liveUrl, fromTemplate(SHIFT_TEMPLATE, startMs, endMs))
            type != null && type.startsWith("flussonic") -> flussonic(liveUrl, startMs, endMs)
            type == "xc" || type == "xtream" -> xtreamStyle(liveUrl, startMs, endMs, tz)
            // A bare catchup="default" with no source needs server support we can't model.
            else -> null
        }
    }

    /** The `shift` convention's fixed query. */
    private const val SHIFT_TEMPLATE = "?utc={utc}&lutc={lutc}"

    /**
     * Join an appended query [fragment] onto [base]. A channel URL that already carries a query (a CDN
     * token, say) would otherwise get a second `?` and be rejected outright, so the separator is
     * corrected — everything else is appended verbatim, as the convention expects.
     */
    private fun joinQuery(base: String, fragment: String): String {
        if (fragment.isEmpty()) return base
        // Playlists write the fragment three ways — "?utc=…", "&utc=…" and a bare "utc=…" — and only the
        // first was handled. A bare fragment concatenated onto a URL that already carried a query produced
        // "…?token=abcutc=1700000000", which the archive answers with a 404.
        val body = fragment.removePrefix("?").removePrefix("&")
        return if (base.contains('?')) "$base&$body" else "$base?$body"
    }

    /**
     * Flussonic: the archive is a sibling of the live playlist —
     * `…/channel/index.m3u8` → `…/channel/timeshift_abs-<start>.m3u8`, and the MPEG-TS forms
     * (`…/channel/mpegts`, `…/channel/index.ts`) → `…/channel/archive-<start>-<duration>.ts`.
     * Any query string is preserved. Returns null when the URL doesn't look like one of those.
     */
    private fun flussonic(liveUrl: String, startMs: Long, endMs: Long): String? {
        val query = liveUrl.substringAfter('?', "").let { if (it.isEmpty()) "" else "?$it" }
        val path = liveUrl.substringBefore('?')
        val slash = path.lastIndexOf('/')
        if (slash <= 0) return null
        val last = path.substring(slash + 1).lowercase()
        val prefix = path.substring(0, slash + 1)
        val startS = startMs / 1000
        val durationS = ((endMs - startMs) / 1000).coerceAtLeast(1)
        return when {
            last.endsWith(".m3u8") -> "${prefix}timeshift_abs-$startS.m3u8$query"
            last == "mpegts" || last.endsWith(".ts") -> "${prefix}archive-$startS-$durationS.ts$query"
            else -> null
        }
    }

    private val XC_LIVE_URL = Regex(
        "^(https?://[^/]+)/(?:live/)?([^/]+)/([^/]+)/(\\d+)(?:\\.[A-Za-z0-9]+)?$",
        RegexOption.IGNORE_CASE,
    )

    /**
     * `catchup="xc"` — an Xtream panel served through a plain M3U playlist. The live URL carries the
     * credentials (`…/live/user/pass/1234.ts`), so the panel's own timeshift endpoint can be rebuilt
     * from it. The start timestamp is local to the provider, hence [tz]. Null when the URL isn't in
     * that shape.
     */
    private fun xtreamStyle(liveUrl: String, startMs: Long, endMs: Long, tz: TimeZone): String? {
        val g = XC_LIVE_URL.find(liveUrl.substringBefore('?'))?.groupValues ?: return null
        val durationMin = (((endMs - startMs) / 60_000L).toInt()).coerceAtLeast(1)
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd:HH-mm", java.util.Locale.US).apply { timeZone = tz }
        val start = fmt.format(java.util.Date(startMs))
        return "${g[1]}/streaming/timeshift.php?username=${g[2]}&password=${g[3]}&stream=${g[4]}" +
            "&start=$start&duration=$durationMin"
    }
}
