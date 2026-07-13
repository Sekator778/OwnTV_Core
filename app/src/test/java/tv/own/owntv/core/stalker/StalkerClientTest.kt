package tv.own.owntv.core.stalker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class StalkerClientTest {

    // ---- MAC canonicalization ----

    @Test
    fun mac_plainHexBecomesColonForm() {
        assertEquals("00:1A:79:AA:BB:CC", StalkerClient.canonicalizeMac("001a79aabbcc"))
    }

    @Test
    fun mac_acceptsSeparatorsAndCase() {
        assertEquals("00:1A:79:AA:BB:CC", StalkerClient.canonicalizeMac("00:1a:79:aa:bb:cc"))
        assertEquals("00:1A:79:AA:BB:CC", StalkerClient.canonicalizeMac("00-1A-79-AA-BB-CC"))
        assertEquals("00:1A:79:AA:BB:CC", StalkerClient.canonicalizeMac(" 00.1a.79.aa.bb.cc "))
    }

    @Test
    fun mac_rejectsWrongLengthAndNonHex()  {
        assertNull(StalkerClient.canonicalizeMac("001a79aabbc"))
        assertNull(StalkerClient.canonicalizeMac("001a79aabbccdd"))
        assertNull(StalkerClient.canonicalizeMac("001g79aabbcc"))
        assertNull(StalkerClient.canonicalizeMac(""))
    }

    // ---- portal URL normalization ----

    @Test
    fun portalRoot_stripsCPathAndSlashes() {
        assertEquals("http://host:8080", StalkerClient.portalRoot("http://host:8080/c/"))
        assertEquals("http://host:8080", StalkerClient.portalRoot("http://host:8080/c"))
        assertEquals("http://host:8080", StalkerClient.portalRoot("http://host:8080/"))
        assertEquals("http://host:8080", StalkerClient.portalRoot("http://host:8080"))
    }

    @Test
    fun apiCandidates_probeOrder() {
        assertEquals(
            listOf(
                "http://host:8080/portal.php",
                "http://host:8080/stalker_portal/server/load.php",
                "http://host:8080/server/load.php",
            ),
            StalkerClient.apiCandidates("http://host:8080/c/"),
        )
    }

    @Test
    fun apiCandidates_directPhpEndpointComesFirst() {
        val candidates = StalkerClient.apiCandidates("http://host:8080/portal.php")
        assertEquals("http://host:8080/portal.php", candidates.first())
        assertFalse(candidates.drop(1).contains("http://host:8080/portal.php"))
    }

    @Test
    fun isValidPortalUrl_basicCheck() {
        assertTrue(StalkerClient.isValidPortalUrl("http://host:8080/c/"))
        assertTrue(StalkerClient.isValidPortalUrl("https://portal.example.com"))
        assertFalse(StalkerClient.isValidPortalUrl("host:8080/c/"))
        assertFalse(StalkerClient.isValidPortalUrl(""))
    }

    // ---- create_link cmd prefix stripping ----

    @Test
    fun stripCmdPrefix_removesKnownPrefixes() {
        assertEquals("http://real/play/index.m3u8?token=X", StalkerClient.stripCmdPrefix("ffmpeg http://real/play/index.m3u8?token=X"))
        assertEquals("http://real/1.ts", StalkerClient.stripCmdPrefix("auto http://real/1.ts"))
        assertEquals("http://real/1.ts", StalkerClient.stripCmdPrefix("ffrt2 http://real/1.ts"))
        assertEquals("http://real/1.ts", StalkerClient.stripCmdPrefix("ffrt3 http://real/1.ts"))
        assertEquals("http://real/1.ts", StalkerClient.stripCmdPrefix("ffrt http://real/1.ts"))
    }

    @Test
    fun stripCmdPrefix_leavesPlainUrls() {
        assertEquals("http://real/1.ts", StalkerClient.stripCmdPrefix("http://real/1.ts"))
        assertEquals("http://real/1.ts", StalkerClient.stripCmdPrefix("  http://real/1.ts  "))
    }

    // ---- direct-play URL detection (portals that embed the real URL in the cmd) ----

    @Test
    fun isDirectPlayUrl_realHostWithQueryOrExtension() {
        // The light-ott portal shape: a full play.php URL with stream/token already present.
        assertTrue(StalkerClient.isDirectPlayUrl("http://host:80/play/live.php?mac=00:1A:79:1C:F8:46&stream=1745079&extension=ts&play_token=abc"))
        assertTrue(StalkerClient.isDirectPlayUrl("http://host/live/1.ts"))
    }

    @Test
    fun isDirectPlayUrl_localhostPlaceholderNeedsCreateLink() {
        assertFalse(StalkerClient.isDirectPlayUrl("http://localhost/ch/12345_"))
        assertFalse(StalkerClient.isDirectPlayUrl("http://127.0.0.1/ch/12345_0"))
    }

    @Test
    fun isDirectPlayUrl_nonUrlIsFalse() {
        assertFalse(StalkerClient.isDirectPlayUrl("ffmpeg localhost/ch/1"))
        assertFalse(StalkerClient.isDirectPlayUrl(""))
    }
}
