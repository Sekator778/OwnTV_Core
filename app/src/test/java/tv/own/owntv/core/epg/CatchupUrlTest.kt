package tv.own.owntv.core.epg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

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
