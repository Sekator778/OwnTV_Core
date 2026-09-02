package tv.own.owntv.core.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class CatchupJumpsTest {

    private val hour = 3600
    private val day = 24 * hour

    @Test fun `options stay strictly inside the archive window`() {
        // A 1-day archive must not offer the 24h jump: the very edge is the part most likely to have
        // already rolled off by the time the request lands.
        val options = CatchupJumps.optionsFor(day)
        assertTrue(options.contains(12 * hour))
        assertFalse(options.contains(day))
        assertTrue(options.all { it < day })
    }

    @Test fun `options are nearest first`() {
        val options = CatchupJumps.optionsFor(7 * day)
        assertEquals(options.sorted(), options)
        assertEquals(15 * 60, options.first())
    }

    @Test fun `a short archive still offers the near jumps`() {
        val options = CatchupJumps.optionsFor(2 * hour)
        assertEquals(listOf(15 * 60, 30 * 60, hour), options)
    }

    @Test fun `no options when there is no window`() {
        assertTrue(CatchupJumps.optionsFor(0).isEmpty())
    }

    @Test fun `instant is the offset subtracted from now`() {
        val now = 1_700_000_000_000L
        assertEquals(now - 3 * hour * 1000L, CatchupJumps.instantFor(3 * hour, now))
    }

    // ---- Exact-time entry ----

    private val zone = TimeZone.getTimeZone("UTC")

    /** 2026-03-10 12:00:00 UTC. */
    private fun fixedNow(): Long = Calendar.getInstance(zone).apply {
        set(2026, Calendar.MARCH, 10, 12, 0, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    @Test fun `a 3-day archive reaches back into a fourth calendar day`() {
        // 3 days back from noon lands on the day before yesterday's *morning*, so that day is
        // partially reachable and must be selectable: today, -1, -2, -3.
        assertEquals(4, CatchupJumps.selectableDays(3 * day))
        assertEquals(1, CatchupJumps.selectableDays(0))
    }

    @Test fun `offset of a point is measured back from now`() {
        val now = fixedNow()
        val p = CatchupJumps.Point(daysAgo = 1, hour = 10, minute = 31)
        // Yesterday 10:31 → 25h 29m before today 12:00.
        assertEquals(25 * hour + 29 * 60, CatchupJumps.offsetSecOf(p, now, zone))
    }

    @Test fun `a future time is pulled back to just behind the live edge`() {
        val now = fixedNow()
        // 18:00 today is still in the future at 12:00.
        val clamped = CatchupJumps.clampToArchive(CatchupJumps.Point(0, 18, 0), now, zone, 3 * day)
        assertEquals(CatchupJumps.MIN_OFFSET_SEC, CatchupJumps.offsetSecOf(clamped, now, zone))
    }

    @Test fun `a time older than the archive is pulled forward to inside its far end`() {
        val now = fixedNow()
        val window = 2 * day
        val clamped = CatchupJumps.clampToArchive(CatchupJumps.Point(5, 9, 0), now, zone, window)
        val offset = CatchupJumps.offsetSecOf(clamped, now, zone)
        // Strictly inside the window is the requirement; the exact value depends on minute rounding,
        // and asserting it would only pin down an implementation detail.
        assertTrue("offset $offset should be inside the $window s window", offset < window)
        // ...and it must land near that far end, not somewhere arbitrary.
        assertTrue("offset $offset should be within a minute of the wall", offset > window - 120)
    }

    @Test fun `clamping is idempotent at both walls`() {
        val now = fixedNow()
        val window = 2 * day
        for (p in listOf(CatchupJumps.Point(5, 9, 0), CatchupJumps.Point(0, 18, 0))) {
            val once = CatchupJumps.clampToArchive(p, now, zone, window)
            assertEquals(once, CatchupJumps.clampToArchive(once, now, zone, window))
        }
    }

    @Test fun `a time inside the archive is left exactly as entered`() {
        val now = fixedNow()
        val p = CatchupJumps.Point(daysAgo = 1, hour = 10, minute = 31)
        assertEquals(p, CatchupJumps.clampToArchive(p, now, zone, 3 * day))
    }

    @Test fun `pointAt round-trips through instantOf`() {
        val now = fixedNow()
        val p = CatchupJumps.Point(daysAgo = 2, hour = 23, minute = 5)
        assertEquals(p, CatchupJumps.pointAt(CatchupJumps.instantOf(p, now, zone), now, zone))
    }

    @Test fun `crossesDay is true only once the jump lands on an earlier date`() {
        val zone = TimeZone.getTimeZone("UTC")
        // Fix "now" at 12:00 UTC so the arithmetic is unambiguous.
        val cal = Calendar.getInstance(zone).apply {
            set(2026, Calendar.MARCH, 10, 12, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val now = cal.timeInMillis
        assertFalse(CatchupJumps.crossesDay(3 * hour, now, zone))   // 09:00 same day
        assertTrue(CatchupJumps.crossesDay(13 * hour, now, zone))   // 23:00 previous day
        assertTrue(CatchupJumps.crossesDay(2 * day, now, zone))
    }
}
