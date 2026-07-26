package tv.own.owntv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
