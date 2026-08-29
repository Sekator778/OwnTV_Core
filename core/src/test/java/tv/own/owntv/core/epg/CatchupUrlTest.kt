package tv.own.owntv.core.epg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class CatchupUrlTest {

    // 2021-01-01 00:00:00 UTC = 1609459200; +1h = 1609462800.
    private val start = 1609459200_000L
    private val end = 1609462800_000L

    @Test
    fun template_fillsUnixTokens() {
        assertEquals(
            "http://x/stream?start=1609459200&end=1609462800&dur=3600",
            CatchupUrl.fromTemplate("http://x/stream?start=\${start}&end=\${end}&dur=\${duration}", start, end),
        )
    }

    @Test
    fun template_fillsUtcBraceAndDateParts() {
        assertEquals(
            "http://x/2021-01-01/00-00/1609459200.ts",
            CatchupUrl.fromTemplate("http://x/{Y}-{m}-{d}/{H}-{M}/{utc}.ts", start, end),
        )
    }

    @Test
    fun template_leavesUnknownTokens() {
        assertEquals("http://x/\${weird}", CatchupUrl.fromTemplate("http://x/\${weird}", start, end))
    }

    @Test
    fun forM3u_appendJoinsOntoLiveUrl() {
        val out = CatchupUrl.forM3u("http://x/live.ts", "append", "?utc=\${start}", start, end)
        assertEquals("http://x/live.ts?utc=1609459200", out)
    }

    @Test
    fun forM3u_nullWhenNoTemplate() {
        assertNull(CatchupUrl.forM3u("http://x/live.ts", "default", null, start, end))
    }

    /** F17 — `{lutc}` used to survive into the request verbatim. */
    @Test
    fun template_fillsNowTokens() {
        val now = 1609466400_000L
        assertEquals(
            "http://x/s?utc=1609459200&lutc=1609466400&now=1609466400",
            CatchupUrl.fromTemplate("http://x/s?utc={utc}&lutc={lutc}&now={now}", start, end, now),
        )
    }

    /** A channel URL that already carries a query must not get a second `?`. */
    @Test
    fun forM3u_appendFixesTheQuerySeparator() {
        val out = CatchupUrl.forM3u("http://x/live.ts?token=abc", "append", "?utc=\${start}", start, end)
        assertEquals("http://x/live.ts?token=abc&utc=1609459200", out)
    }

    @Test
    fun forM3u_shiftAppendsTheStandardQuery() {
        val out = CatchupUrl.forM3u("http://x/live.ts", "shift", null, start, end)
        assertNotNull(out)
        assertTrue(out!!.startsWith("http://x/live.ts?utc=1609459200&lutc="))
    }

    @Test
    fun forM3u_flussonicRewritesThePath() {
        assertEquals(
            "http://x/ch1/timeshift_abs-1609459200.m3u8",
            CatchupUrl.forM3u("http://x/ch1/index.m3u8", "flussonic", null, start, end),
        )
        assertEquals(
            "http://x/ch1/archive-1609459200-3600.ts",
            CatchupUrl.forM3u("http://x/ch1/mpegts", "flussonic-ts", null, start, end),
        )
        // Not a Flussonic-shaped URL → no guess.
        assertNull(CatchupUrl.forM3u("http://x", "flussonic", null, start, end))
    }

    @Test
    fun forM3u_xcRebuildsTheTimeshiftEndpoint() {
        assertEquals(
            "http://x:8080/streaming/timeshift.php?username=u&password=p&stream=1234&start=2021-01-01:00-00&duration=60",
            CatchupUrl.forM3u("http://x:8080/live/u/p/1234.ts", "xc", null, start, end, TimeZone.getTimeZone("UTC")),
        )
    }

    @Test
    fun timeshiftPhpAlternate_convertsPathToPhpForm() {
        val path = "http://gaming8k.top/timeshift/user1/pass1/15/2026-06-16:09-15/544435.ts"
        assertEquals(
            "http://gaming8k.top/streaming/timeshift.php?username=user1&password=pass1&stream=544435&start=2026-06-16:09-15&duration=15",
            CatchupUrl.timeshiftPhpAlternate(path),
        )
    }

    @Test
    fun timeshiftPhpAlternate_nullForNonTimeshiftUrls() {
        assertNull(CatchupUrl.timeshiftPhpAlternate("http://x/live/user/pass/1.ts"))
        assertNull(CatchupUrl.timeshiftPhpAlternate(null))
    }
}
