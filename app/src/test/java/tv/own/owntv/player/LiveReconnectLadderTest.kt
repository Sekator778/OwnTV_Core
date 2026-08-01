package tv.own.owntv.player

import androidx.media3.common.C
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 1 — the two decisions that keep a live channel alive through a provider hiccup:
 * how long we wait between reconnects (X3), and whether an early END_FILE means "corrupt file"
 * or "live stream being live" (P1).
 */
class LiveReconnectLadderTest {

    @Test
    fun `the ladder widens instead of flat-lining at four seconds`() {
        assertEquals(1_500L, LivePreviewEngine.reconnectDelayMs(1))
        assertEquals(3_000L, LivePreviewEngine.reconnectDelayMs(2))
        assertEquals(6_000L, LivePreviewEngine.reconnectDelayMs(3))
        assertEquals(10_000L, LivePreviewEngine.reconnectDelayMs(4))
        assertEquals(15_000L, LivePreviewEngine.reconnectDelayMs(5))
    }

    @Test
    fun `attempts past the ladder hold at the last step`() {
        assertEquals(15_000L, LivePreviewEngine.reconnectDelayMs(6))
        assertEquals(15_000L, LivePreviewEngine.reconnectDelayMs(8))
    }

    @Test
    fun `a defensive out-of-range attempt still yields the first step`() {
        assertEquals(1_500L, LivePreviewEngine.reconnectDelayMs(0))
        assertEquals(1_500L, LivePreviewEngine.reconnectDelayMs(-3))
    }

    @Test
    fun `the ladder outlives a minute-long outage`() {
        // The pre-fix rule (1500 * n capped at 4 s) spent its eight attempts in ~26 s, so a router
        // reboot always ended in "Lost connection". Five attempts alone now span ~35 s.
        val fiveAttempts = (1..5).sumOf { LivePreviewEngine.reconnectDelayMs(it) }
        assertEquals(35_500L, fiveAttempts)
        assertTrue((1..8).sumOf { LivePreviewEngine.reconnectDelayMs(it) } > 60_000L)
    }

    @Test
    fun `recovery is only credited after a sustained healthy window`() {
        assertEquals(60_000L, LivePreviewEngine.HEALTHY_MS)
    }

    @Test
    fun `fatal HLS HTTP recovery stays short instead of widening to fifteen seconds`() {
        assertEquals(1_500L, LivePreviewEngine.hlsHttpReconnectDelayMs(1))
        assertEquals(1_500L, LivePreviewEngine.hlsHttpReconnectDelayMs(5))
        assertEquals(1_500L, LivePreviewEngine.hlsHttpReconnectDelayMs(8))
    }

    @Test
    fun `redirected playlist is recognized from final URL or response content type`() {
        assertTrue(LivePreviewEngine.isHlsResponse("http://cdn.test/live/7.m3u8?token=x", "application/octet-stream"))
        assertTrue(LivePreviewEngine.isHlsResponse("http://cdn.test/live/7", "application/x-mpegURL; charset=UTF-8"))
        assertTrue(LivePreviewEngine.isHlsResponse("http://cdn.test/live/7", "application/vnd.apple.mpegurl"))
        assertFalse(LivePreviewEngine.isHlsResponse("http://cdn.test/live/7.ts", "video/MP2T"))
    }

    @Test
    fun `a refused segment is retried once, not hammered until fatal`() {
        assertEquals(LivePreviewEngine.EDGE_REFUSAL_RETRY_MS, LivePreviewEngine.edgeRefusalRetryDelayMs(1))
        assertEquals(C.TIME_UNSET, LivePreviewEngine.edgeRefusalRetryDelayMs(2))
        assertEquals(C.TIME_UNSET, LivePreviewEngine.edgeRefusalRetryDelayMs(5))
    }

    @Test
    fun `only raw live MPEG-TS gets reconnect_at_eof — everything else keeps the plain reconnect set`() {
        // Live HLS keeps the shipped reconnect options: dropping them was an unproven experiment and one
        // provider's mpv playback stopped working under it.
        assertEquals(
            OwnTVPlayer.STREAM_RECONNECT_OPTIONS,
            OwnTVPlayer.streamLavfOptionsFor("http://panel/live/7.m3u8?token=x", live = true, hls = true),
        )
        assertEquals(
            "${OwnTVPlayer.STREAM_RECONNECT_OPTIONS},reconnect_at_eof=1",
            OwnTVPlayer.streamLavfOptionsFor("http://panel/live/7.ts", live = true, hls = false),
        )
        assertEquals(
            OwnTVPlayer.STREAM_RECONNECT_OPTIONS,
            OwnTVPlayer.streamLavfOptionsFor("http://panel/movie/7.m3u8", live = false, hls = false),
        )
    }

    @Test
    fun `a redirecting ts URL is treated as HLS by mpv, not as a raw stream`() {
        // The permanent-black-screen case: mpv reconnected to the same 1.8 KB manifest forever because
        // the URL said `.ts`. Nothing about the URL changes — only what we learned about the panel.
        assertEquals(
            "${OwnTVPlayer.STREAM_RECONNECT_OPTIONS},reconnect_at_eof=1",
            OwnTVPlayer.streamLavfOptionsFor("http://panel/live/7.ts", live = true, hls = false),
        )
        // Learned to be HLS → the manifest's EOF is legitimate, so no reconnect_at_eof.
        assertEquals(
            OwnTVPlayer.STREAM_RECONNECT_OPTIONS,
            OwnTVPlayer.streamLavfOptionsFor("http://panel/live/7.ts", live = true, hls = true),
        )
    }

    @Test
    fun `an HTTP refusal stops the identical-request retries but leaves the fallbacks armed`() {
        // The traced panel answers FFmpeg with a non-standard 458 while serving ExoPlayer normally;
        // repeating the same request cannot change that, so only one repeat is allowed — enough for the
        // format/User-Agent fallbacks, which need autoRetries >= 1, to still get their turn.
        assertTrue(OwnTVPlayer.isHardHttpRefusal("ffmpeg: http: HTTP error 458 <none>"))
        assertTrue(OwnTVPlayer.isHardHttpRefusal("ffmpeg: http: HTTP error 403 Forbidden"))
        assertFalse(OwnTVPlayer.isHardHttpRefusal("ffmpeg: http: HTTP error 502 Bad Gateway"))
        assertFalse(OwnTVPlayer.isHardHttpRefusal("stream: Failed to open http://panel/live/7.ts."))
        assertFalse(OwnTVPlayer.isHardHttpRefusal(null))
        assertEquals(1, OwnTVPlayer.HARD_REFUSAL_MAX_RETRIES)
    }

    @Test
    fun `mpv always keeps FFmpeg's default live start`() {
        // Regression guard: pinning live_start_index back (-5) to dodge the traced panel's 403s only
        // asked for staler signed segment URLs. Live HLS must carry no demuxer option at all.
        assertEquals(
            "",
            OwnTVPlayer.demuxerLavfOptionsFor(
                "http://panel/live/7.m3u8?token=x", live = true, trimmedRawTsProbe = false, hls = true,
            ),
        )
        assertEquals(
            "fflags=+nobuffer+genpts,seekable=1",
            OwnTVPlayer.demuxerLavfOptionsFor(
                "http://panel/live/7.ts", live = true, trimmedRawTsProbe = true, hls = false,
            ),
        )
        assertEquals(
            "",
            OwnTVPlayer.demuxerLavfOptionsFor(
                "http://panel/movie/7.m3u8", live = false, trimmedRawTsProbe = false, hls = true,
            ),
        )
    }

    @Test
    fun `mpv live opening loop is bounded`() {
        assertEquals(10_000L, OwnTVPlayer.LIVE_OPEN_TIMEOUT_MS)
    }

    @Test
    fun `an early end-file hard-resets a VOD but never a live channel`() {
        assertTrue(OwnTVPlayer.shouldHardResetOnEarlyEndFile(fileLoaded = false, expectingPlayback = true, isLive = false))
        assertFalse(OwnTVPlayer.shouldHardResetOnEarlyEndFile(fileLoaded = false, expectingPlayback = true, isLive = true))
    }

    @Test
    fun `an end-file after the file loaded is never an early end-file`() {
        assertFalse(OwnTVPlayer.shouldHardResetOnEarlyEndFile(fileLoaded = true, expectingPlayback = true, isLive = false))
        assertFalse(OwnTVPlayer.shouldHardResetOnEarlyEndFile(fileLoaded = false, expectingPlayback = false, isLive = false))
    }
}
