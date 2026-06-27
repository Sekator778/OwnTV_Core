package tv.own.owntv.core.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tv.own.owntv.core.database.OwnTVDatabase
import tv.own.owntv.core.database.dao.ChannelDao
import tv.own.owntv.core.database.dao.MovieDao
import tv.own.owntv.core.database.dao.SeriesDao
import tv.own.owntv.core.database.entity.SourceEntity

/** Per-type counts after a playlist import, for the success breakdown. */
data class SyncCounts(val channels: Int, val movies: Int, val series: Int, val epg: Int = 0) {
    /** e.g. "40K channels · 100K movies · 30K series · 30K EPG synced". */
    fun summary(includeEpg: Boolean): String {
        val parts = buildList {
            if (channels > 0) add("${human(channels)} channels")
            if (movies > 0) add("${human(movies)} movies")
            if (series > 0) add("${human(series)} series")
            if (includeEpg && epg > 0) add("${human(epg)} EPG")
        }
        return if (parts.isEmpty()) "Synced successfully" else parts.joinToString(" · ") + " synced"
    }

    /** Content breakdown without a trailing verb, for list rows: "40K channels · 100K movies · 30K series". */
    val breakdown: String
        get() = buildList {
            if (channels > 0) add("${human(channels)} channels")
            if (movies > 0) add("${human(movies)} movies")
            if (series > 0) add("${human(series)} series")
        }.joinToString(" · ")

    private fun human(n: Int): String = when {
        n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0).removeSuffix(".0M").let { if (it.endsWith("M")) it else "${it}M" }
        n >= 1_000 -> "%.1fK".format(n / 1_000.0).removeSuffix(".0K").let { if (it.endsWith("K")) it else "${it}K" }
        else -> n.toString()
    }
}

/**
 * Runs after a playlist syncs: returns the per-type content counts for the success message. EPG is NOT
 * auto-created/synced here — that's slow and not everyone wants it. The user adds a guide explicitly via
 * Settings → EPG sources, where the form pre-fills the playlist's own guide URL (Xtream `xmltv.php` /
 * M3U `url-tvg`) so it's still one tap.
 */
class ImportFinalizer(
    private val channelDao: ChannelDao,
    private val movieDao: MovieDao,
    private val seriesDao: SeriesDao,
    private val db: OwnTVDatabase,
) {
    suspend fun finalize(source: SourceEntity): SyncCounts {
        val counts = contentCounts(source.id)
        // A sync does REPLACE on 100k+ rows, which invalidates SQLite's planner statistics
        // (sqlite_stat1). With stale stats the query planner IGNORES the (sourceId, name) /
        // (categoryId, name) composite indices declared in v5 and falls back to a full table scan +
        // temp B-tree sort — the Movies/Series grids' 2–3s cold-open. ANALYZE refreshes the stats so the
        // existing indices get used again, dropping the open back to <300ms. Same trick EPG uses after its
        // bulk sync (EpgRepository.refreshUrl). Safe, idempotent, <1s even on 100k rows.
        ensureContentIndexes()
        return counts
    }

    /** Current content counts for a source (no EPG) — for the success message and the Playlists list rows. */
    suspend fun contentCounts(sourceId: Long): SyncCounts = SyncCounts(
        channels = channelDao.countForSourceOnce(sourceId),
        movies = movieDao.countForSourceOnce(sourceId),
        series = seriesDao.countForSourceOnce(sourceId),
    )

    /**
     * Ensure the Movies/Series/Channels grid read-indices exist and the planner statistics are fresh. The
     * composite indices (both A–Z `…, name` and playlist/provider `…, sortOrder, name`) are declared on the
     * entities (v5/v6) and created by migration, so the `CREATE INDEX IF NOT EXISTS` here is a permanent,
     * auto-maintained, instant no-op once run; the real value is the `ANALYZE`, which refreshes `sqlite_stat1`
     * after a bulk REPLACE so the planner keeps choosing those indices instead of reverting to a full-table
     * sort. Mirrors `EpgRepository.ensureEpgIndexes`. Called after every sync (above) — NOT at app launch.
     */
    suspend fun ensureContentIndexes() = withContext(Dispatchers.IO) {
        runCatching {
            val w = db.openHelper.writableDatabase
            // Idempotent (IF NOT EXISTS): no-op once the indices exist. Covers DBs that somehow lack them.
            // Movies — A–Z + playlist/provider composites.
            w.execSQL("CREATE INDEX IF NOT EXISTS `index_movies_sourceId_name` ON `movies` (`sourceId`, `name`)")
            w.execSQL("CREATE INDEX IF NOT EXISTS `index_movies_categoryId_name` ON `movies` (`categoryId`, `name`)")
            w.execSQL("CREATE INDEX IF NOT EXISTS `index_movies_sourceId_sortOrder_name` ON `movies` (`sourceId`, `sortOrder`, `name`)")
            w.execSQL("CREATE INDEX IF NOT EXISTS `index_movies_categoryId_sortOrder_name` ON `movies` (`categoryId`, `sortOrder`, `name`)")
            // Series — same four.
            w.execSQL("CREATE INDEX IF NOT EXISTS `index_series_sourceId_name` ON `series` (`sourceId`, `name`)")
            w.execSQL("CREATE INDEX IF NOT EXISTS `index_series_categoryId_name` ON `series` (`categoryId`, `name`)")
            w.execSQL("CREATE INDEX IF NOT EXISTS `index_series_sourceId_sortOrder_name` ON `series` (`sourceId`, `sortOrder`, `name`)")
            w.execSQL("CREATE INDEX IF NOT EXISTS `index_series_categoryId_sortOrder_name` ON `series` (`categoryId`, `sortOrder`, `name`)")
            // Channels — A–Z + playlist/provider composites (the Live TV grid).
            w.execSQL("CREATE INDEX IF NOT EXISTS `index_channels_sourceId_name` ON `channels` (`sourceId`, `name`)")
            w.execSQL("CREATE INDEX IF NOT EXISTS `index_channels_categoryId_name` ON `channels` (`categoryId`, `name`)")
            w.execSQL("CREATE INDEX IF NOT EXISTS `index_channels_sourceId_sortOrder_name` ON `channels` (`sourceId`, `sortOrder`, `name`)")
            w.execSQL("CREATE INDEX IF NOT EXISTS `index_channels_categoryId_sortOrder_name` ON `channels` (`categoryId`, `sortOrder`, `name`)")
            // Refresh planner stats so the indices above are chosen for the grids' "WHERE … ORDER BY …" —
            // without this, stale stats from a bulk REPLACE make SQLite ignore them and full-sort.
            w.execSQL("ANALYZE `movies`")
            w.execSQL("ANALYZE `series`")
            w.execSQL("ANALYZE `channels`")
            w.execSQL("ANALYZE `categories`")
        }
    }
}