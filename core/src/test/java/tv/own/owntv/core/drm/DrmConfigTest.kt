package tv.own.owntv.core.drm

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.parser.M3uEntry
import tv.own.owntv.core.parser.M3uParser

/**
 * #115 — protected (Widevine/ClearKey) channels. A playlist declares the licence server on its own
 * `#KODIPROP` lines; every one of them used to be dropped, so the app had no way to know a stream was
 * encrypted, let alone where to ask for the key.
 *
 * The rejection cases matter as much as the happy path: a licence config we cannot fully honour must
 * be dropped, not stored half-right. A stored-but-wrong config produces a failing licence request the
 * user cannot diagnose; no config at all fails exactly the way it does today.
 */
class DrmConfigTest {

    private fun parse(text: String): List<M3uEntry> = runBlocking {
        val out = mutableListOf<M3uEntry>()
        M3uParser().parse(text.byteInputStream()) { out += it }
        out
    }

    // --- fromKodiProps ---

    @Test
    fun `a widevine licence url is accepted`() {
        val cfg = DrmConfig.fromKodiProps(
            mapOf("license_type" to "com.widevine.alpha", "license_key" to "https://host/live/key/144"),
        )
        assertEquals(DrmConfig.Scheme.WIDEVINE, cfg?.scheme)
        assertEquals("https://host/live/key/144", cfg?.licenseUrl)
        assertTrue(cfg?.headers.isNullOrEmpty())
    }

    @Test
    fun `clearkey is recognised too`() {
        val cfg = DrmConfig.fromKodiProps(
            mapOf("license_type" to "org.w3c.clearkey", "license_key" to "https://host/ck"),
        )
        assertEquals(DrmConfig.Scheme.CLEARKEY, cfg?.scheme)
    }

    @Test
    fun `the pipe form splits into url and licence request headers`() {
        val cfg = DrmConfig.fromKodiProps(
            mapOf(
                "license_type" to "com.widevine.alpha",
                "license_key" to "https://host/key|Referer=http%3A%2F%2Fr.example%2F&Authorization=Bearer%20abc|R{SSM}|",
            ),
        )
        assertEquals("https://host/key", cfg?.licenseUrl)
        assertEquals("http://r.example/", cfg?.headers?.get("Referer"))
        assertEquals("Bearer abc", cfg?.headers?.get("Authorization"))
    }

    @Test
    fun `a request body template we do not build is rejected outright`() {
        // Kodi's postDataTemplate: the portal expects a body we never construct, so a licence request
        // built from this would always fail. Better to report "no DRM" than to fail unexplainably.
        assertNull(
            DrmConfig.fromKodiProps(
                mapOf(
                    "license_type" to "com.widevine.alpha",
                    "license_key" to "https://host/key||{\"payload\":\"{SSM}\"}|",
                ),
            ),
        )
    }

    @Test
    fun `unsupported or incomplete declarations are dropped`() {
        // PlayReady: out of scope.
        assertNull(DrmConfig.fromKodiProps(mapOf("license_type" to "com.microsoft.playready", "license_key" to "https://h/k")))
        // A type with no key, and a key with no type.
        assertNull(DrmConfig.fromKodiProps(mapOf("license_type" to "com.widevine.alpha")))
        assertNull(DrmConfig.fromKodiProps(mapOf("license_key" to "https://h/k")))
        // Inline ClearKey data is a local key, not a licence server — a different mechanism.
        assertNull(DrmConfig.fromKodiProps(mapOf("license_type" to "org.w3c.clearkey", "license_key" to "a1b2:c3d4")))
        assertNull(DrmConfig.fromKodiProps(emptyMap()))
    }

    // --- encode / decode ---

    @Test
    fun `encode and decode round-trip, including licence headers`() {
        val cfg = DrmConfig(DrmConfig.Scheme.WIDEVINE, "https://host/key", mapOf("Referer" to "http://r/"))
        val encoded = DrmConfig.encode(cfg)
        assertEquals(cfg, DrmConfig.decode(encoded))
        assertNull(DrmConfig.encode(null))
    }

    @Test
    fun `a malformed or unknown blob reads as no DRM rather than throwing`() {
        assertNull(DrmConfig.decode(null))
        assertNull(DrmConfig.decode(""))
        assertNull(DrmConfig.decode("not json"))
        assertNull(DrmConfig.decode("""{"scheme":"playready","license":"https://h/k"}"""))
        assertNull(DrmConfig.decode("""{"scheme":"widevine"}"""))
    }

    // --- the parser wiring ---

    @Test
    fun `the issue 115 playlist entry parses into a widevine config`() {
        val entries = parse(
            """
            #EXTM3U
            #EXTINF:-1 tvg-id="144" group-title="Entertainment", Colors HD
            #KODIPROP:inputstream=inputstream.adaptive
            #KODIPROP:inputstream.adaptive.manifest_type=mpd
            #KODIPROP:inputstream.adaptive.license_type=com.widevine.alpha
            #KODIPROP:inputstream.adaptive.license_key=https://tv.example/live/key/144
            https://tv.example/live/mpd/143
            """.trimIndent(),
        )
        assertEquals(1, entries.size)
        assertEquals(DrmConfig.Scheme.WIDEVINE, entries[0].drm?.scheme)
        assertEquals("https://tv.example/live/key/144", entries[0].drm?.licenseUrl)
        assertEquals("https://tv.example/live/mpd/143", entries[0].streamUrl)
    }

    /** The older bare `inputstream.license_type` spelling is used by plenty of playlists. */
    @Test
    fun `the legacy property spelling works too`() {
        val entries = parse(
            """
            #EXTM3U
            #EXTINF:-1,Legacy
            #KODIPROP:inputstream.license_type=com.widevine.alpha
            #KODIPROP:inputstream.license_key=https://host/k
            http://host/s.mpd
            """.trimIndent(),
        )
        assertEquals(DrmConfig.Scheme.WIDEVINE, entries[0].drm?.scheme)
    }

    /** A licence must belong to the entry that declared it — never to the next channel. */
    @Test
    fun `drm does not leak into the following entry`() {
        val entries = parse(
            """
            #EXTM3U
            #EXTINF:-1,Protected
            #KODIPROP:inputstream.adaptive.license_type=com.widevine.alpha
            #KODIPROP:inputstream.adaptive.license_key=https://host/k
            http://host/1.mpd
            #EXTINF:-1,Plain
            http://host/2.ts
            """.trimIndent(),
        )
        assertEquals(DrmConfig.Scheme.WIDEVINE, entries[0].drm?.scheme)
        assertNull(entries[1].drm)
    }

    /** DRM lines and header lines coexist on the same entry without consuming each other. */
    @Test
    fun `stream headers and drm properties are collected independently`() {
        val entries = parse(
            """
            #EXTM3U
            #EXTINF:-1,Both
            #KODIPROP:inputstream.adaptive.stream_headers=User-Agent=Kodi%2F1.0
            #KODIPROP:inputstream.adaptive.license_type=com.widevine.alpha
            #KODIPROP:inputstream.adaptive.license_key=https://host/k
            http://host/s.mpd
            """.trimIndent(),
        )
        assertEquals("Kodi/1.0", entries[0].headers["User-Agent"])
        assertEquals("https://host/k", entries[0].drm?.licenseUrl)
    }

    @Test
    fun `a plain playlist entry carries no drm at all`() {
        val entries = parse("#EXTM3U\n#EXTINF:-1,Plain\nhttp://host/a.ts")
        assertNull(entries[0].drm)
    }
}
