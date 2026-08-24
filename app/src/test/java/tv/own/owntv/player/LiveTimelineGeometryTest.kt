package tv.own.owntv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where the EPG marks sit on the live timeline.
 *
 * The bar always spans the last [LIVE_WINDOW_SEC] up to the live edge, so a programme's position is a
 * pure function of two timestamps and "now". Everything the drawing code needs is decided here, which
 * is why it can be pinned without a player, a surface or a device.
 */
class LiveTimelineGeometryTest {

    private val now = 1_700_000_000_000L // any fixed "live edge"

    private fun minutesAgo(m: Int) = now - m * 60_000L

    @Test
    fun `live edge is the right end and the window start is the left end`() {
        assertEquals(1f, offsetFrac(0), 0.0001f)
        assertEquals(0f, offsetFrac(LIVE_WINDOW_SEC), 0.0001f)
        assertEquals(0.5f, offsetFrac(LIVE_WINDOW_SEC / 2), 0.0001f)
    }

    @Test
    fun `scrubbing past the far edge pins to the left, never negative`() {
        assertEquals(0f, offsetFrac(LIVE_WINDOW_SEC * 3), 0.0001f)
        assertEquals(1f, offsetFrac(-60), 0.0001f)
    }

    @Test
    fun `a programme in the middle of the window maps to its share of the bar`() {
        // 90..60 minutes ago, in a 120-minute window: from a quarter along to half-way.
        val ticks = liveTicks(listOf(LiveProgramme(minutesAgo(90), minutesAgo(60), "News")), now)
        assertEquals(1, ticks.size)
        assertEquals(0.25f, ticks[0].startFrac, 0.0001f)
        assertEquals(0.5f, ticks[0].endFrac, 0.0001f)
        assertEquals("News", ticks[0].title)
    }

    @Test
    fun `the programme now on air runs to the live edge`() {
        val ticks = liveTicks(listOf(LiveProgramme(minutesAgo(30), now + 1_800_000L, "Film")), now)
        assertEquals(1f, ticks[0].endFrac, 0.0001f)
        assertEquals(0.75f, ticks[0].startFrac, 0.0001f)
    }

    @Test
    fun `a programme older than the window is dropped, one straddling it is clipped`() {
        val ticks = liveTicks(
            listOf(
                LiveProgramme(minutesAgo(400), minutesAgo(300), "Yesterday"),
                LiveProgramme(minutesAgo(150), minutesAgo(90), "Straddles"),
            ),
            now,
        )
        assertEquals(1, ticks.size)
        assertEquals("Straddles", ticks[0].title)
        assertEquals(0f, ticks[0].startFrac, 0.0001f)
        assertEquals(0.25f, ticks[0].endFrac, 0.0001f)
    }

    @Test
    fun `a future programme is not on the bar`() {
        val ticks = liveTicks(listOf(LiveProgramme(now + 60_000L, now + 600_000L, "Later")), now)
        assertTrue(ticks.isEmpty())
    }

    @Test
    fun `every tick stays inside the bar`() {
        val ticks = liveTicks(
            (0..11).map { LiveProgramme(minutesAgo(240 - it * 20), minutesAgo(220 - it * 20), "P$it") },
            now,
        )
        ticks.forEach {
            assertTrue(it.startFrac in 0f..1f)
            assertTrue(it.endFrac in 0f..1f)
            assertTrue(it.endFrac >= it.startFrac)
        }
    }

    @Test
    fun `the scrub bubble names the programme under the thumb`() {
        val programmes = listOf(
            LiveProgramme(minutesAgo(120), minutesAgo(60), "News"),
            LiveProgramme(minutesAgo(60), now, "Film"),
        )
        assertEquals("News", programmeAt(programmes, now, offsetSec = 90 * 60)?.title)
        assertEquals("Film", programmeAt(programmes, now, offsetSec = 30 * 60)?.title)
        assertEquals("Film", programmeAt(programmes, now, offsetSec = 0)?.title)
        assertNull(programmeAt(emptyList(), now, offsetSec = 0))
    }

    @Test
    fun `a gap in the guide names nothing rather than the neighbour`() {
        val programmes = listOf(LiveProgramme(minutesAgo(120), minutesAgo(90), "News"))
        assertNull(programmeAt(programmes, now, offsetSec = 30 * 60))
    }
}
