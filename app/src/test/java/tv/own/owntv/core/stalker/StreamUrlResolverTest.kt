package tv.own.owntv.core.stalker

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.model.SourceType

/**
 * StreamUrlResolver unit tests — focus on the routing decisions (C-2 direct-play vs create_link,
 * and C-3's "every resolve is fresh" contract) without hitting a real portal. A fake auth manager +
 * recording client let us assert which path resolve() took and what it returned.
 */
class StreamUrlResolverTest {

    private val m3uSource = SourceEntity(name = "m3u", type = SourceType.M3U, url = "http://h/p.m3u")
    private val xtreamSource = SourceEntity(name = "xt", type = SourceType.XTREAM, url = "http://h", username = "u", password = "p")
    private fun stalkerSource(cmd: String, mac: String = "00:1A:79:AA:BB:CC") =
        SourceEntity(name = "stk", type = SourceType.STALKER, url = "http://portal/c/", mac = mac)

    // --- needsResolve gating ---

    @Test fun needsResolve_trueOnlyForStalker() {
        val r = resolver()
        assertTrue(r.needsResolve(stalkerSource("anything")))
        assertFalse(r.needsResolve(m3uSource))
        assertFalse(r.needsResolve(xtreamSource))
        assertFalse(r.needsResolve(null))
    }

    // --- pass-through (M3U/Xtream are never touched) ---

    @Test fun resolve_m3uReturnsStoredUrlUnchanged() = runBlocking {
        val r = resolver()
        assertEquals("http://h/stream.ts", r.resolve(m3uSource, "http://h/stream.ts"))
    }

    @Test fun resolve_xtreamReturnsStoredUrlUnchanged() = runBlocking {
        val r = resolver()
        assertEquals("http://h/live/u/p/1.ts", r.resolve(xtreamSource, "http://h/live/u/p/1.ts"))
    }

    // --- Stalker direct-play (embedded URL in cmd, e.g. light-ott) ---

    @Test fun resolve_stalkerDirectPlayUrlReturnsAsIsNoCreateLink() = runBlocking {
        val client = RecordingClient()
        val r = resolver(client = client)
        val cmd = "ffmpeg http://host/play/live.php?mac=00:1A:79:AA:BB:CC&stream=123&play_token=x"
        // Direct-play URL → played as-is, create_link MUST NOT be called (it blanks the stream id → 405).
        assertEquals(
            "http://host/play/live.php?mac=00:1A:79:AA:BB:CC&stream=123&play_token=x",
            r.resolve(stalkerSource(cmd), cmd),
        )
        assertEquals("create_link must not run for a direct-play cmd", 0, client.createLinkCalls)
    }

    // --- Stalker placeholder cmd (localhost → create_link) ---

    @Test fun resolve_stalkerPlaceholderCallsCreateLink() = runBlocking {
        val client = RecordingClient(resolved = "http://realhost/play/123/index.m3u8?token=fresh")
        val r = resolver(client = client)
        val cmd = "ffmpeg http://localhost/ch/12345_"
        assertEquals(
            "http://realhost/play/123/index.m3u8?token=fresh",
            r.resolve(stalkerSource(cmd), cmd),
        )
        assertEquals("placeholder cmd must resolve via create_link", 1, client.createLinkCalls)
    }

    // --- C-3: every resolve is a FRESH resolve (never cached) ---

    @Test fun resolve_everyCallIsFresh_createLinkRunsEachTime() = runBlocking {
        val client = RecordingClient(resolved = "http://realhost/play/123/index.m3u8?token=fresh")
        val r = resolver(client = client)
        val cmd = "ffmpeg http://localhost/ch/12345_"
        val source = stalkerSource(cmd)
        repeat(3) { r.resolve(source, cmd) } // simulates three reconnects
        assertEquals("reconnect must re-resolve a fresh URL each time", 3, client.createLinkCalls)
    }

    @Test fun resolve_everyCallIsFresh_directPlayIsIdempotent() = runBlocking {
        val client = RecordingClient()
        val r = resolver(client = client)
        val cmd = "http://host/play/live.php?mac=00:1A:79:AA:BB:CC&stream=123&token=x"
        val source = stalkerSource(cmd)
        repeat(3) { r.resolve(source, cmd) }
        assertEquals("direct-play never needs create_link, even on reconnect", 0, client.createLinkCalls)
    }

    // --- Stalker with no MAC → direct (best-effort, no auth possible) ---

    @Test fun resolve_stalkerNoMacFallsBackToDirect() = runBlocking {
        val client = RecordingClient()
        val r = resolver(client = client)
        val source = SourceEntity(name = "stk", type = SourceType.STALKER, url = "http://portal/c/", mac = null)
        val cmd = "http://localhost/ch/12345_" // placeholder, but no MAC → can't create_link
        assertEquals("http://localhost/ch/12345_", r.resolve(source, cmd))
        assertEquals(0, client.createLinkCalls)
    }

    // --- ReconnectUrlProvider contract (C-3) ---

    @Test fun reconnectUrlProvider_nullReturnMeansReplayAsIs() = runBlocking {
        // The provider is the engine's "ask for a fresh URL" hook. A null return = "no fresh URL
        // available / not an expiring-URL source" → the engine replays the stored URL. The provider
        // for M3U/Xtream is never installed (LiveViewModel sets it only for Stalker), so this is the
        // "absent provider" semantic the engines rely on.
        val provider = ReconnectUrlProvider { null }
        assertNull(provider.freshUrl())
    }

    @Test fun reconnectUrlProvider_returnsFreshUrl() = runBlocking {
        val r = resolver()
        val cmd = "ffmpeg http://localhost/ch/12345_"
        val source = stalkerSource(cmd)
        // The provider the VM installs resolves the current cmd via the resolver.
        val provider = ReconnectUrlProvider {
            runCatching { r.resolve(source, cmd) }.getOrNull()
        }
        assertEquals(
            "http://realhost/out.m3u8",
            provider.freshUrl(),
        )
    }

    // --- Phase E: catch-up archive resolve (§5.6) ---

    @Test fun resolveCatchup_buildsArchiveCmdAndTvArchiveType() = runBlocking {
        val client = RecordingClient(resolved = "http://realhost/archive/out.m3u8?token=a")
        val r = resolver(client = client)
        val startMs = 1_700_000_000_000L // 1700000000 s
        val endMs = startMs + 90 * 60_000L // 90 min
        assertEquals(
            "http://realhost/archive/out.m3u8?token=a",
            r.resolveCatchup(stalkerSource("unused"), "4321", startMs, endMs),
        )
        assertEquals("tv_archive", client.lastType)
        assertEquals("auto /media/4321_1700000000_5400.mpg", client.lastCmd)
    }

    @Test fun resolveCatchup_everyCallIsFresh() = runBlocking {
        val client = RecordingClient()
        val r = resolver(client = client)
        val source = stalkerSource("unused")
        repeat(3) { r.resolveCatchup(source, "1", 1_000_000_000_000L, 1_000_003_600_000L) }
        assertEquals(3, client.createLinkCalls)
    }

    // --- Phase E: short EPG (§5.5) ---

    @Test fun shortEpg_returnsClientEntriesForStalkerOnly() = runBlocking {
        val client = RecordingClient()
        client.shortEpgEntries = listOf(
            StalkerClient.ShortEpgEntry("News", null, 1_000L, 2_000L),
            StalkerClient.ShortEpgEntry("Movie", "desc", 2_000L, 3_000L),
        )
        val r = resolver(client = client)
        val entries = r.shortEpg(stalkerSource("unused"), "77")
        assertEquals(2, entries.size)
        assertEquals("News", entries[0].title)
        assertEquals("77", client.lastShortEpgChannel)
    }

    // --- helpers ---

    /** A minimal fake auth manager that runs the block with a synthetic session (no network). */
    private fun resolver(
        client: RecordingClient = RecordingClient(),
    ): StreamUrlResolver {
        val auth = object : StalkerAuthManager(client) {
            // Bypass the real handshake/profile; just hand the block a session whose apiBase/token the
            // client records. The resolver only needs withAuthRetry to call the block once.
            override suspend fun <T> withAuthRetry(
                creds: StalkerCredentials,
                block: suspend (StalkerSession) -> T,
            ): T = block(
                StalkerSession(
                    apiBase = "http://portal/portal.php",
                    token = "fake-token",
                    expiresAtMs = Long.MAX_VALUE,
                    profile = emptyMap(),
                ),
            )
        }
        return StreamUrlResolver(auth, client)
    }

    /**
     * A StalkerClient stand-in: records create_link invocations and returns a canned resolved URL.
     * Inherits from StalkerClient for the type, but every override short-circuits the network.
     */
    private open class RecordingClient(private val resolved: String = "http://realhost/out.m3u8") :
        StalkerClient(okhttp3.OkHttpClient()) {
        var createLinkCalls = 0
        var lastCmd: String? = null
        var lastType: String? = null
        var lastEpisode: Int? = null

        // Bypass okhttp entirely (the real constructor builds a client we never use).
        constructor() : this(resolved = "http://realhost/out.m3u8")

        override suspend fun createLink(
            apiBase: String, mac: String, token: String, userAgent: String?, cmd: String, type: String, episode: Int?,
        ): String {
            createLinkCalls++
            lastCmd = cmd
            lastType = type
            lastEpisode = episode
            // stripCmdPrefix on the way out, mirroring the real client.
            return stripCmdPrefix(resolved)
        }

        var shortEpgEntries: List<ShortEpgEntry> = emptyList()
        var lastShortEpgChannel: String? = null

        override suspend fun getShortEpg(
            apiBase: String, mac: String, token: String, userAgent: String?, channelId: String, size: Int,
        ): List<ShortEpgEntry> {
            lastShortEpgChannel = channelId
            return shortEpgEntries
        }
    }
}
