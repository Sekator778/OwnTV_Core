package tv.own.owntv.player

import java.util.concurrent.ConcurrentHashMap

/**
 * Per-provider live-stream quirks learned at runtime, shared by both engines.
 *
 * Everything here is **in-memory for the session only** — nothing is persisted, so a provider that
 * fixes its panel is back to stock behaviour after the next app start. Keyed by `host:port` so a
 * lesson learned on one channel applies immediately to every other channel of the same panel
 * (these faults are panel-wide, not per-channel).
 *
 * Two quirks are tracked:
 *
 *  1. **`.ts` that is really HLS.** Some Xtream panels advertise `/live/user/pass/ID.ts` but
 *     HTTP-redirect it to an `.m3u8` manifest. ExoPlayer picks its media source from the URL
 *     *before* that redirect and hands a text manifest to the progressive extractor
 *     (`ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED`); mpv/FFmpeg treats the manifest response's EOF as
 *     a broken raw stream and reconnects to the same 1.8 KB body forever (permanent black screen).
 *     Once either engine has seen the redirect land on a manifest we remember it here.
 *
 *  2. **Per-segment signed URLs that Media3 cannot keep fresh.** The panel this was traced on hands
 *     out segment URLs carrying a short-lived signed token
 *     (`/serve/<id>/<token>/<token>/…/136875_2559.ts`) and answers **every** one of them with
 *     `403 "Invalid token 2"` — the playlist itself keeps returning 200, and not a single segment ever
 *     succeeds. Media3's chunk pipeline can only re-issue the *identical* URL it resolved from the
 *     playlist snapshot, so once a token has aged out the channel can never recover; FFmpeg (mpv, VLC)
 *     re-reads the playlist and fetches with a fresh token, which is why the same channel plays there,
 *     lagging but alive. There is nothing to tune — the fix is to recognise the pattern quickly and
 *     hand the panel to mpv, rather than grinding ExoPlayer's reconnect ladder on a dead channel.
 */
object LiveStreamQuirks {

    /**
     * How many distinct live segments a provider must refuse before we stop trying on ExoPlayer.
     *
     * Two, not one: a single 403 can be a genuine one-off (a segment rolled out of the window mid-flight),
     * and the load-error policy already absorbs that with one quick retry. Two *different* segments
     * refused in the same load is the signature of a URL-signing scheme Media3 structurally cannot
     * satisfy, and every further attempt just costs the user another dead spinner.
     */
    const val REFUSALS_BEFORE_HANDOFF = 2

    /** HTTP statuses a panel uses to refuse a segment outright. Never worth hammering the same URL for. */
    fun isEdgeRefusal(responseCode: Int): Boolean =
        responseCode == 403 || responseCode == 404 || responseCode == 410

    /**
     * The non-standard status a panel returns when the account's one allowed session is already in use.
     *
     * Traced on a panel whose edge answers `458` with an empty `text/html` body and no redirect, while a
     * permitted request is redirected through to the origin. It is not an HTTP standard code — Xtream
     * panels invent codes in this range for "max connections" — so it is matched exactly rather than by
     * class, and it means something very different from [isEdgeRefusal]: the stream is fine, *we* are the
     * second client.
     */
    fun isSessionLimit(responseCode: Int): Boolean = responseCode == 458

    /** `host:port` of [url], lowercased; the whole URL when it can't be parsed (still a stable key). */
    fun hostKey(url: String): String {
        val afterScheme = url.substringAfter("://", url)
        val authority = afterScheme.substringBefore('/').substringBefore('?').substringAfterLast('@')
        return authority.ifBlank { url }.lowercase()
    }

    // --- learned state ---------------------------------------------------------------------------

    private val hlsRedirectHosts = ConcurrentHashMap.newKeySet<String>()
    private val segmentRefusingHosts = ConcurrentHashMap.newKeySet<String>()
    private val singleSessionHosts = ConcurrentHashMap.newKeySet<String>()
    private val brokenTimestampStreams = ConcurrentHashMap.newKeySet<String>()
    private val softwareArchiveHosts = ConcurrentHashMap.newKeySet<String>()

    /** Record that [url]'s host serves HLS even when its advertised URL says `.ts`. */
    fun rememberHlsRedirect(url: String) { hlsRedirectHosts += hostKey(url) }

    fun isKnownHlsHost(url: String): Boolean = hostKey(url) in hlsRedirectHosts

    /**
     * True when [url] should be treated as HLS regardless of its extension: either it already ends in
     * `.m3u8`, or its panel has been caught redirecting `.ts` to a manifest.
     */
    fun isHlsUrl(url: String): Boolean =
        url.substringBefore('?').endsWith(".m3u8", ignoreCase = true) || isKnownHlsHost(url)

    /** Rewrite an Xtream-style `.ts` live URL to its `.m3u8` sibling; other URLs are returned as-is. */
    fun toHlsUrl(url: String): String {
        val query = url.substringAfter('?', "")
        val path = url.substringBefore('?')
        if (!path.endsWith(".ts", ignoreCase = true)) return url
        val rewritten = path.dropLast(3) + ".m3u8"
        return if (query.isEmpty()) rewritten else "$rewritten?$query"
    }

    /**
     * Record that this panel refuses its own signed segment URLs, so the *next* channel on it opens
     * straight on mpv instead of repeating ExoPlayer's dead spinner. Panel-wide because the signing
     * scheme is a property of the panel, not of one channel.
     */
    fun rememberSegmentRefusal(url: String) { segmentRefusingHosts += hostKey(url) }

    fun refusesSegments(url: String): Boolean = hostKey(url) in segmentRefusingHosts

    /**
     * Record that this panel allows only one session at a time (it answered [isSessionLimit]).
     *
     * On such a panel the two engines must never be connected at once: whichever one holds the session
     * keeps playing and the other is locked out until the holder's socket is really gone, which is the
     * whole "mpv works but ExoPlayer doesn't" (and the reverse) symptom. Panel-wide, because the limit is
     * on the *account*, not the channel.
     */
    fun rememberSessionLimit(url: String) { singleSessionHosts += hostKey(url) }

    fun isSingleSession(url: String): Boolean = hostKey(url) in singleSessionHosts

    /**
     * Record that mpv can't trust this stream's video timestamps, so its next open starts on free-running
     * video timing (`correct-pts=no` + `video-sync=desync` + `framedrop=no`).
     *
     * Keyed by the **whole URL**, not the panel: a broken mux is a property of one feed, and its
     * healthy neighbours on the same panel must keep mpv's accurate audio-synced timing — free-running
     * timing drifts sound away from picture on a stream whose PTS were fine all along.
     */
    fun rememberBrokenTimestamps(url: String) { brokenTimestampStreams += url }

    fun hasBrokenTimestamps(url: String): Boolean = url in brokenTimestampStreams

    /**
     * Record that this panel's catch-up archive needs a SOFTWARE video decoder.
     *
     * Archive (timeshift) segments start mid-GOP, and some TV-class hardware decoders can't resync from
     * that: the decoder accepts the format, audio plays, and no video frame is ever emitted (Realtek OMX:
     * "setPortMode … DynamicANWBuffer failed", "BAD CODEC: stride 1920 -> 64"). A software decoder picks
     * up cleanly at the next keyframe.
     *
     * Catch-up used to be pinned to software *unconditionally* for that reason, which cost every panel
     * hardware decoding — including the majority whose decoders cope fine. So archives now open in
     * hardware and drop to software only once this panel has actually been caught failing; the cost of a
     * panel that does fail is one silent retry on the session's first catch-up.
     *
     * Panel-wide, because the mid-GOP archive mux is a property of the panel's timeshift server, not of
     * one channel. Session-scoped like every other quirk here — a re-learn per app run is cheap and keeps
     * a one-off decoder hiccup from permanently downgrading a healthy provider.
     */
    fun rememberArchiveNeedsSoftware(url: String) { softwareArchiveHosts += hostKey(url) }

    fun archiveNeedsSoftware(url: String): Boolean = hostKey(url) in softwareArchiveHosts

    /** Test hook — the session cache is never cleared in production. */
    internal fun clearForTest() {
        hlsRedirectHosts.clear(); segmentRefusingHosts.clear(); singleSessionHosts.clear()
        brokenTimestampStreams.clear(); softwareArchiveHosts.clear()
    }
}
