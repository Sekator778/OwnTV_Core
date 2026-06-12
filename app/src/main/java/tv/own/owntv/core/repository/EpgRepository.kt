package tv.own.owntv.core.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import tv.own.owntv.core.database.dao.EpgDao
import tv.own.owntv.core.database.entity.EpgChannelEntity
import tv.own.owntv.core.database.entity.EpgProgrammeEntity
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.model.SourceType
import tv.own.owntv.core.network.HttpClient
import tv.own.owntv.core.parser.XmltvParser
import tv.own.owntv.core.parser.XtreamClient

/**
 * Fetches and stores the bulk XMLTV guide for a source (Xtream `xmltv.php`, or a M3U playlist's
 * `url-tvg`). Only programmes overlapping a rolling window are kept, and finished rows are pruned, so
 * the guide stays bounded. The grid reads from [EpgDao]; per-channel now/next still uses Xtream's
 * short-EPG separately.
 */
class EpgRepository(
    private val epgDao: EpgDao,
    private val http: HttpClient,
    private val xtream: XtreamClient,
) {
    /** The guide URL for a source, or null if it has no EPG feed. A manual EPG URL always wins. */
    fun guideUrl(source: SourceEntity): String? = when (source.type) {
        SourceType.XTREAM -> source.epgUrl?.takeIf { it.isNotBlank() } ?: xtream.xmltvUrl(source)
        SourceType.M3U -> source.epgUrl?.takeIf { it.isNotBlank() }
        SourceType.LOCAL_BACKUP -> null
    }

    fun hasGuide(source: SourceEntity): Boolean = guideUrl(source) != null

    /**
     * Refresh one source's guide. Returns the number of programmes stored. Throws on network/parse
     * failure (callers wrap in runCatching). A source with no guide URL is a no-op (returns 0).
     */
    suspend fun refresh(source: SourceEntity): Int = withContext(Dispatchers.IO) {
        val url = guideUrl(source) ?: return@withContext 0
        val now = System.currentTimeMillis()
        val from = now - WINDOW_BACK_MS
        val to = now + WINDOW_AHEAD_MS

        epgDao.clearSource(source.id) // clear-then-insert (no unique key on programmes to REPLACE on)

        val channels = LinkedHashMap<String, EpgChannelEntity>()
        val buffer = ArrayList<EpgProgrammeEntity>(CHUNK)
        var stored = 0

        http.get(url, source.userAgent) { input ->
            XmltvParser.parse(
                input,
                onChannel = { id, name ->
                    // Ids are stored normalized (trim+lowercase) so guide lookups can use the
                    // (epgChannelId, startMs) index directly — XMLTV ids often differ from the
                    // panel's epg_channel_id only in case.
                    val key = id.trim().lowercase()
                    channels.getOrPut(key) { EpgChannelEntity(sourceId = source.id, epgChannelId = key, displayName = name) }
                },
                onProgramme = { channelId, startMs, stopMs, title, desc ->
                    if (stopMs > from && startMs < to) {
                        buffer.add(
                            EpgProgrammeEntity(
                                sourceId = source.id, epgChannelId = channelId.trim().lowercase(),
                                startMs = startMs, stopMs = stopMs, title = title, description = desc,
                            ),
                        )
                        if (buffer.size >= CHUNK) {
                            runBlocking { epgDao.upsertProgrammes(buffer.toList()) }
                            stored += buffer.size
                            buffer.clear()
                        }
                    }
                },
            )
        }
        if (buffer.isNotEmpty()) { epgDao.upsertProgrammes(buffer.toList()); stored += buffer.size }
        if (channels.isNotEmpty()) epgDao.upsertChannels(channels.values.toList())
        epgDao.prune(now - WINDOW_BACK_MS)
        stored
    }

    companion object {
        private const val WINDOW_BACK_MS = 2L * 60 * 60 * 1000 // keep 2h of just-aired
        private const val WINDOW_AHEAD_MS = 48L * 60 * 60 * 1000 // and 48h ahead
        private const val CHUNK = 500
    }
}
