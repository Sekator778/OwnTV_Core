package tv.own.owntv.core.live

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tv.own.owntv.core.customize.CustomizeKeys
import tv.own.owntv.core.customize.SectionCustomizations
import tv.own.owntv.core.database.dao.EpgDao
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.database.entity.EpgProgrammeEntity
import tv.own.owntv.core.epg.EpgShift
import tv.own.owntv.core.epg.EpgSourceStore

/** One page of the window load. Bounded so a page always fits a single ~2 MB CursorWindow. */
private const val WINDOW_PAGE = 1_000

/** Guide rows are looked up by epg id in chunks, to stay inside SQLite's variable limit. */
private const val KEY_CHUNK = 400

/**
 * Reading a span of guide, rather than a single channel's now/next.
 *
 * The Guide asks a different question from Live TV: not "what is on this channel", but "what is on
 * every one of these channels, between these two instants". That is the app's heaviest query, and
 * getting it wrong is not slow but fatal — a large lineup returns far more rows than one CursorWindow
 * holds — so it lives here, once, rather than in each app's guide screen.
 *
 * Nothing here caches: what to keep and when to drop it depends on how the screen scrolls, which is
 * the caller's business. The guide shift is passed in for the same reason [LiveEpgReader] takes it —
 * the caller is the one place that knows a customization changed.
 */
class GuideReader(
    private val epgDao: EpgDao,
    private val epgSourceStore: EpgSourceStore,
    private val sourceDao: SourceDao,
) {
    /**
     * Every playlist plus every EPG feed. Guide rows are keyed by epg id and routinely live under a
     * different source than the channel showing them — one playlist's `xmltv.php` covering another's
     * lineup — so narrowing this to the channels' own sources loses rows that are really there.
     */
    suspend fun guideSourceIds(): List<Long> = withContext(Dispatchers.IO) {
        (sourceDao.allSourceIds() + epgSourceStore.getAll().map { it.id }).distinct()
    }

    /**
     * The whole window, grouped by epg id, each row list in start order.
     *
     * Read in id-keyset pages: a keyset walk stays fast at any depth, and each page is small enough
     * to come back in one cursor window. The descriptions are dropped by the query — a window of them
     * is megabytes of text nothing on screen shows — so [description] fetches the one that is opened.
     */
    suspend fun window(sourceIds: List<Long>, from: Long, to: Long): Map<String, List<EpgProgrammeEntity>> =
        withContext(Dispatchers.Default) {
            val all = ArrayList<EpgProgrammeEntity>()
            var afterId = 0L
            while (true) {
                val page = epgDao.programmesInWindowPage(sourceIds, from, to, afterId, WINDOW_PAGE)
                if (page.isEmpty()) break
                all += page
                afterId = page.last().id
                if (page.size < WINDOW_PAGE) break
            }
            all.groupBy { it.epgChannelId }.mapValues { (_, v) -> v.sortedBy { it.startMs } }
        }

    /**
     * One channel's programmes in the window, on the clock the user sees.
     *
     * A shifted channel cannot be served from a [window] batch — its rows are a different slice of
     * stored time — so it is read on its own, which is why this exists beside the batch rather than
     * only inside it.
     */
    suspend fun row(
        channel: ChannelEntity,
        cust: SectionCustomizations,
        globalShiftMinutes: Int,
        sourceIds: List<Long>,
        from: Long,
        to: Long,
    ): List<EpgProgrammeEntity> = withContext(Dispatchers.IO) {
        val epgKey = epgKeyOf(channel, cust) ?: return@withContext emptyList()
        val shift = EpgShift.minutesFor(cust, channel, globalShiftMinutes)
        if (shift == 0) return@withContext epgDao.programmeSummariesForChannel(sourceIds, epgKey, from, to)
        epgDao
            .programmeSummariesForChannel(sourceIds, epgKey, EpgShift.toStored(from, shift), EpgShift.toStored(to, shift))
            .map { EpgShift.apply(it, shift) }
    }

    /**
     * A window of several channels at once, keyed by channel id and already on the user's clock —
     * what a Home rail or an "on now" list needs.
     *
     * One query per *shift group*, not per channel: channels with the same offset (almost always all
     * of them) read one moved window together, so a rail of twenty channels is one query. The order
     * of [channels] is kept, because the rails render straight off this map's iteration order.
     */
    suspend fun slice(
        channels: List<ChannelEntity>,
        cust: SectionCustomizations,
        globalShiftMinutes: Int,
        sourceIds: List<Long>,
        from: Long,
        to: Long,
    ): Map<Long, List<EpgProgrammeEntity>> = withContext(Dispatchers.IO) {
        if (channels.isEmpty()) return@withContext emptyMap()
        val keyed = channels.mapNotNull { ch ->
            epgKeyOf(ch, cust)?.let { key -> Triple(ch.id, key, EpgShift.minutesFor(cust, ch, globalShiftMinutes)) }
        }
        if (keyed.isEmpty()) return@withContext emptyMap()
        val collected = HashMap<Long, List<EpgProgrammeEntity>>()
        for ((shift, group) in keyed.groupBy { it.third }) {
            val rowsByKey = group
                .map { it.second }.distinct()
                .chunked(KEY_CHUNK)
                .flatMap { keys ->
                    epgDao.programmeSummariesForChannels(
                        sourceIds,
                        keys,
                        EpgShift.toStored(from, shift),
                        EpgShift.toStored(to, shift),
                    )
                }
                .groupBy { it.epgChannelId }
            for ((channelId, epgKey, _) in group) {
                rowsByKey[epgKey]?.takeIf { it.isNotEmpty() }
                    ?.let { collected[channelId] = EpgShift.apply(it.sortedBy { row -> row.startMs }, shift) }
            }
        }
        val ordered = LinkedHashMap<Long, List<EpgProgrammeEntity>>(collected.size)
        for (channel in channels) collected[channel.id]?.let { ordered[channel.id] = it }
        ordered
    }

    /**
     * What is on each of [channels] at [atMs], and what follows it — the "on now" list.
     *
     * [LiveEpgReader.nowPlayingFor] answers the same question with only a title, which is all a
     * channel row under a name needs. A guide list is about the programme itself: it draws how far
     * through it is and what is next, so it needs the rows.
     */
    suspend fun onNow(
        channels: List<ChannelEntity>,
        cust: SectionCustomizations,
        globalShiftMinutes: Int,
        sourceIds: List<Long>,
        atMs: Long,
        lookAheadMs: Long,
    ): Map<Long, GuideSlot> {
        val rows = slice(channels, cust, globalShiftMinutes, sourceIds, atMs, atMs + lookAheadMs)
        val result = HashMap<Long, GuideSlot>(rows.size)
        for ((channelId, list) in rows) {
            val now = list.firstOrNull { atMs in it.startMs until it.stopMs }
            val next = list.firstOrNull { it.startMs > (now?.startMs ?: atMs) }
            if (now == null && next == null) continue
            result[channelId] = GuideSlot(now = now, next = next)
        }
        return result
    }

    /** The synopsis of one programme, fetched when it is opened — the list queries drop it. */
    suspend fun description(programmeId: Long): String? =
        withContext(Dispatchers.IO) { runCatching { epgDao.programmeDescription(programmeId) }.getOrNull() }

    /** The guide id this channel really reads from: a manual match wins over the channel's own. */
    private fun epgKeyOf(channel: ChannelEntity, cust: SectionCustomizations): String? =
        (cust.epgMatches[CustomizeKeys.channel(channel)] ?: channel.epgChannelId)
            ?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
}

/** What is on a channel now, and what follows it — both already on the user's clock. */
data class GuideSlot(val now: EpgProgrammeEntity?, val next: EpgProgrammeEntity?)
