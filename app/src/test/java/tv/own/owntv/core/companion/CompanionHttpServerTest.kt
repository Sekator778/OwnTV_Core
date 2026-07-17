package tv.own.owntv.core.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.model.SourceType

/**
 * Tests the pure parse/auth logic of the companion server (no socket bound). The JSON branch relies
 * on `org.json`, which is only a stub in plain JVM unit tests, so it is exercised on-device; the
 * form-encoded path (what the HTML form actually submits) is fully covered here.
 */
class CompanionHttpServerTest {

    private val server = CompanionHttpServer()
    private val form = "application/x-www-form-urlencoded"

    // ---- PIN comparison ----

    @Test
    fun `pin matches only when equal and same length`() {
        assertTrue(CompanionHttpServer.pinEquals("123456", "123456"))
        assertFalse(CompanionHttpServer.pinEquals("123456", "123457"))
        assertFalse(CompanionHttpServer.pinEquals("12345", "123456"))
        assertFalse(CompanionHttpServer.pinEquals("", "123456"))
    }

    // ---- query / form parsing ----

    @Test
    fun `parseQuery decodes pairs and plus-as-space`() {
        val map = CompanionHttpServer.parseQuery("a=1&b=hello+world&c=")
        assertEquals("1", map["a"])
        assertEquals("hello world", map["b"])
        assertEquals("", map["c"])
    }

    // ---- Xtream ----

    @Test
    fun `xtream form parses all fields`() {
        val body = "type=xtream&name=My+IPTV&server=http://h:80&user=u&pass=p&userAgent=UA&epgUrl=http://e" +
            "&syncLive=on&syncMovies=on&syncSeries=on&isDefault=on&autoRefresh=HOURS_6"
        val p = server.parsePayload(form, body, SourceType.XTREAM)!!
        assertEquals(SourceType.XTREAM, p.type)
        assertEquals("My IPTV", p.name)
        assertEquals("http://h:80", p.server)
        assertEquals("u", p.user)
        assertEquals("p", p.pass)
        assertEquals("UA", p.userAgent)
        assertEquals("http://e", p.epgUrl)
        assertEquals("HOURS_6", p.autoRefresh)
        assertTrue(p.syncLive && p.syncMovies && p.syncSeries && p.isDefault)
    }

    @Test
    fun `xtream missing credentials is rejected`() {
        assertNull(server.parsePayload(form, "type=xtream&server=http://h", SourceType.XTREAM))
    }

    @Test
    fun `explicit false sync fields parse as off`() {
        // The web form pairs each checkbox with a hidden value=false, so an unchecked box submits
        // "false" (last-value-wins gives "true" when checked). Verify false is honoured, not defaulted.
        val body = "type=xtream&server=h&user=u&pass=p&syncLive=false&syncMovies=false&syncSeries=false&isDefault=false"
        val p = server.parsePayload(form, body, SourceType.XTREAM)!!
        assertFalse(p.syncLive || p.syncMovies || p.syncSeries || p.isDefault)
    }

    @Test
    fun `checked box overrides the hidden false when both are posted`() {
        // Mirrors the real form wire order: hidden false first, checkbox true second.
        val body = "type=xtream&server=h&user=u&pass=p&syncLive=false&syncLive=true"
        assertTrue(server.parsePayload(form, body, SourceType.XTREAM)!!.syncLive)
    }

    // ---- M3U ----

    @Test
    fun `m3u form parses with url alias and only needs the url`() {
        val p = server.parsePayload(form, "type=m3u&name=P&url=http://x/p.m3u", SourceType.M3U)!!
        assertEquals(SourceType.M3U, p.type)
        assertEquals("http://x/p.m3u", p.server)
        assertEquals("P", p.name)
    }

    @Test
    fun `m3u without a url is rejected`() {
        assertNull(server.parsePayload(form, "type=m3u&name=P", SourceType.M3U))
    }

    // ---- Stalker ----

    @Test
    fun `stalker form needs portal and mac`() {
        val p = server.parsePayload(form, "type=stalker&portalUrl=http://h/c/&mac=00:1A:79:AA:BB:CC", SourceType.STALKER)!!
        assertEquals(SourceType.STALKER, p.type)
        assertEquals("http://h/c/", p.portalUrl)
        assertEquals("00:1A:79:AA:BB:CC", p.mac)
    }

    @Test
    fun `stalker missing mac is rejected`() {
        assertNull(server.parsePayload(form, "type=stalker&portalUrl=http://h/c/", SourceType.STALKER))
    }

    // ---- fallback type ----

    @Test
    fun `blank type falls back to the endpoint's type`() {
        val p = server.parsePayload(form, "server=h&user=u&pass=p", SourceType.XTREAM)!!
        assertEquals(SourceType.XTREAM, p.type)
    }

    // ---- robustness ----

    @Test
    fun `malformed json body does not throw and yields null`() {
        // org.json is stubbed in unit tests; parsePayload must swallow it, not crash the accept thread.
        assertNull(server.parsePayload("application/json", "{ this is not json", SourceType.XTREAM))
    }
}
