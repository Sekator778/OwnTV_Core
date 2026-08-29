package tv.own.owntv.core.parser

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.network.StreamHeaders

/**
 * F16 — per-channel HTTP options. Playlists that need a UA/Referer per entry returned 403 in OwnTV
 * and played fine in VLC/TiviMate, because every `#EXTVLCOPT`/`#EXTHTTP`/`#KODIPROP` line and the
 * `url|Key=Value` suffix were dropped on the floor.
 */
class M3uHeaderParsingTest {

    private fun parse(text: String): List<M3uEntry> = runBlocking {
        val out = mutableListOf<M3uEntry>()
        M3uParser().parse(text.byteInputStream()) { out += it }
        out
    }

    @Test
    fun extVlcOptHeadersAreParsed() {
        val entries = parse(
            """
            #EXTM3U
            #EXTINF:-1 tvg-id="a",Channel A
            #EXTVLCOPT:http-user-agent=MyPlayer/1.0
            #EXTVLCOPT:http-referrer=http://ref.example/
            #EXTVLCOPT:network-caching=1000
            http://host/a.ts
            """.trimIndent(),
        )
        assertEquals(1, entries.size)
        assertEquals("MyPlayer/1.0", entries[0].headers["User-Agent"])
        // VLC spells it "referrer"; the request header is "Referer".
        assertEquals("http://ref.example/", entries[0].headers["Referer"])
        // Non-HTTP VLC options are player settings, not headers.
        assertEquals(2, entries[0].headers.size)
    }

    @Test
    fun extHttpJsonAndKodiPropAreParsed() {
        val entries = parse(
            """
            #EXTM3U
            #EXTINF:-1,Channel B
            #EXTHTTP:{"cookie":"sid=42","User-Agent":"FromJson"}
            http://host/b.ts
            #EXTINF:-1,Channel C
            #KODIPROP:inputstream.adaptive.stream_headers=User-Agent=Kodi%2F1.0&Referer=http%3A%2F%2Fr.example%2F
            http://host/c.ts
            """.trimIndent(),
        )
        assertEquals("sid=42", entries[0].headers["Cookie"])
        assertEquals("FromJson", entries[0].headers["User-Agent"])
        assertEquals("Kodi/1.0", entries[1].headers["User-Agent"])
        assertEquals("http://r.example/", entries[1].headers["Referer"])
    }

    @Test
    fun pipeSuffixIsStrippedFromTheUrlAndBecomesHeaders() {
        val entries = parse(
            """
            #EXTM3U
            #EXTINF:-1,Channel D
            http://host/d.ts|User-Agent=Piped&Referer=http://p.example/
            """.trimIndent(),
        )
        assertEquals("http://host/d.ts", entries[0].streamUrl)
        assertEquals("Piped", entries[0].headers["User-Agent"])
        assertEquals("http://p.example/", entries[0].headers["Referer"])
    }

    /** A `|` that isn't a header suffix belongs to the URL and must survive untouched. */
    @Test
    fun pipeInsideAUrlIsNotTreatedAsHeaders() {
        val entries = parse(
            """
            #EXTM3U
            #EXTINF:-1,Channel E
            http://host/path|weird/e.ts
            """.trimIndent(),
        )
        assertEquals("http://host/path|weird/e.ts", entries[0].streamUrl)
        assertTrue(entries[0].headers.isEmpty())
    }

    /** Headers belong to the entry that declared them — never to the next one. */
    @Test
    fun headersDoNotLeakIntoTheFollowingEntry() {
        val entries = parse(
            """
            #EXTM3U
            #EXTINF:-1,With headers
            #EXTVLCOPT:http-user-agent=OnlyMine
            http://host/1.ts
            #EXTINF:-1,Without
            http://host/2.ts
            """.trimIndent(),
        )
        assertEquals("OnlyMine", entries[0].headers["User-Agent"])
        assertTrue(entries[1].headers.isEmpty())
    }

    @Test
    fun catchupTypeIsKept() {
        val entries = parse(
            """
            #EXTM3U
            #EXTINF:-1 catchup="append" catchup-source="?utc={utc}&lutc={lutc}" catchup-days="7",Archive
            http://host/a.ts
            """.trimIndent(),
        )
        assertEquals("append", entries[0].catchup)
        assertEquals("?utc={utc}&lutc={lutc}", entries[0].catchupSource)
        assertEquals(7, entries[0].catchupDays)
    }

    @Test
    fun serializationRoundTrips() {
        val headers = mapOf("User-Agent" to "UA/1", "Referer" to "http://r/")
        val encoded = StreamHeaders.encode(headers)
        assertEquals("User-Agent: UA/1\nReferer: http://r/", encoded)
        assertEquals(headers, StreamHeaders.decode(encoded))
        // mpv carries the UA in its own property, so it is not repeated in the field list.
        assertEquals("Referer: http://r/", StreamHeaders.toMpvHeaderFields(headers))
    }

    /** Headers the transport owns would break the request rather than fix it. */
    @Test
    fun reservedHeadersAreRejected() {
        assertEquals(emptyMap<String, String>(), StreamHeaders.decode("Host: evil.example\nContent-Length: 3"))
    }
}
