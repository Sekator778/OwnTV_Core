package tv.own.owntv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Legacy playback diagnostics remain semantic until the current Compose renderer. */
class PlaybackErrorLogTest {
    @Test
    fun `legacy hardware spec is mapped instead of shown as storage syntax`() {
        val entry = entry(spec = "H264 • 1920x1080 • hardware:direct")
        val spec = entry.mediaSpec()

        assertEquals("H264", spec?.codec)
        assertEquals("1920x1080", spec?.resolution)
        assertTrue(spec?.decoder is DecoderSpec.Hardware)
        assertTrue((spec?.decoder as DecoderSpec.Hardware).direct)
    }

    @Test
    fun `legacy software gpu spec is mapped`() {
        val entry = entry(spec = "HEVC • software:gpu")
        val spec = entry.mediaSpec()

        assertEquals("HEVC", spec?.codec)
        assertTrue(spec?.decoder is DecoderSpec.Software)
        assertTrue((spec?.decoder as DecoderSpec.Software).gpu)
    }

    @Test
    fun `structured unknown decoder names are retained`() {
        val entry = PlaybackErrorLog.Entry(
            atMs = 0L,
            engine = "mpv",
            live = false,
            reason = null,
            spec = null,
            raw = null,
            model = "",
            android = "",
            codec = "AV1",
            decoderKind = "future_decoder",
            decoderName = "future_decoder",
            decoderHardware = true,
        )
        val decoder = entry.mediaSpec()?.decoder

        assertTrue(decoder is DecoderSpec.Named)
        assertEquals("future_decoder", (decoder as DecoderSpec.Named).value)
        assertTrue(decoder.hardware)
    }

    @Test
    fun `legacy English reason maps to localized semantic reason`() {
        val parsed = parsePersistedReason("Hardware video decoder is busy or can't handle this stream")

        assertEquals(PlayerFailureReason.DECODER_BUSY, parsed.semantic)
        assertEquals(null, parsed.legacyText)
    }

    @Test
    fun `unknown legacy reason remains visible and survives rewrite`() {
        val rawText = "Some future error we haven't mapped yet"
        val parsed = parsePersistedReason(rawText)
        val entry = PlaybackErrorLog.Entry(
            atMs = 0L,
            engine = "mpv",
            live = false,
            reason = parsed.semantic,
            legacyReason = parsed.legacyText,
            spec = null,
            raw = null,
            model = "",
            android = "",
        )

        assertEquals(null, parsed.semantic)
        assertEquals(rawText, parsed.legacyText)
        assertEquals(rawText, entry.persistedReasonValue())
    }

    @Test
    fun `current enum reason parses and persists by stable name`() {
        val parsed = parsePersistedReason(PlayerFailureReason.NETWORK.name)
        val entry = PlaybackErrorLog.Entry(
            atMs = 0L,
            engine = "mpv",
            live = false,
            reason = parsed.semantic,
            legacyReason = parsed.legacyText,
            spec = null,
            raw = null,
            model = "",
            android = "",
        )

        assertEquals(PlayerFailureReason.NETWORK, parsed.semantic)
        assertEquals(null, parsed.legacyText)
        assertEquals("NETWORK", entry.persistedReasonValue())
    }

    private fun entry(spec: String) = PlaybackErrorLog.Entry(
        atMs = 0L,
        engine = "mpv",
        live = false,
        reason = null,
        spec = spec,
        raw = null,
        model = "",
        android = "",
    )
}
