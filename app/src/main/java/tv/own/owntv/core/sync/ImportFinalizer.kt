package tv.own.owntv.core.sync

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tv.own.owntv.core.database.BulkInsertHelper
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
    private val bulkInsertHelper: BulkInsertHelper,
) {
    suspend fun finalize(source: SourceEntity, deferIndexes: Boolean = false): SyncCounts {
        val counts = contentCounts(source.id)
        if (!deferIndexes) {
            // A sync does REPLACE on 100k+ rows, which invalidates SQLite's planner statistics
            // (sqlite_stat1). With stale stats the query planner IGNORES the single-column and
            // composite indices on channels/movies/series and falls back to a full table scan +
            // temp B-tree sort — the Movies/Series grids' 2–3s cold-open. ANALYZE refreshes the
            // stats so the existing indices get used again, dropping the open back to <300ms. Same
            // trick EPG uses after its bulk sync (EpgRepository.refreshUrl). Safe, idempotent,
            // <1s even on 100k rows.
            ensureContentIndexes()
        }
        return counts
    }

    /** Current content counts for a source (no EPG) — for the success message and the Playlists list rows. */
    suspend fun contentCounts(sourceId: Long): SyncCounts = SyncCounts(
        channels = channelDao.countForSourceOnce(sourceId),
        movies = movieDao.countForSourceOnce(sourceId),
        series = seriesDao.countForSourceOnce(sourceId),
    )

    /**
     * Ensure the Movies/Series/Channels grid read-indices exist and the planner statistics are fresh.
     * Fresh imports may drop the non-unique content indices to speed up bulk inserts, so this rebuilds the
     * single-column and composite read paths, then refreshes `sqlite_stat1` with ANALYZE so SQLite keeps
     * choosing them instead of reverting to a full-table sort. Mirrors `EpgRepository.ensureEpgIndexes`.
     * Called inline for normal re-syncs and from the deferred background worker for first-ever imports.
     */
    suspend fun ensureContentIndexes() = withContext(Dispatchers.IO) {
        val startedAt = SystemClock.elapsedRealtime()
        Log.i(TAG, "ensureContentIndexes start")
        runCatching {
            val w = db.openHelper.writableDatabase
            val indexesStartedAt = SystemClock.elapsedRealtime()
            // Idempotent (IF NOT EXISTS): no-op once the indices exist. Covers DBs that somehow lack them.
            // Channels — single-column read paths + A–Z + playlist/provider composites.
            w.execSQL("CREATE INDEX IF NOT EXISTS `index_channels_sourceId` ON `channels` (`sourceId`)")
            w.execSQL("CREATE INDEX IF NOT EXISTS `index_channels_categoryId` ON `channels` (`categoryId`)")
            w.execSQL("CREATE INDEX IF NOT EXISTS `index_channels_name` ON `channels` (`name`)")
            w.execSQL("CREATE INDEX IF NOT EXISTS `index_channels_epgChannelId` ON `channels` (`epgChannelId`)")
            w.execSQL("CREATE INDEX IF NOT EXISTS `index_channels_sourceId_name` ON `channels` (`sourceId`, `name`)")
            w.execSQL("CREATE INDEX IF NOT EXISTS `index_channels_categoryId_name` ON `channels` (`categoryId`, `name`)")
            w.execSQL("CREATE INDEX IF NOT EXISTS `index_channels_sourceId_sortOrder_name` ON `channels` (`sourceId`, `sortOrder`, `name`)")
            w.execSQL("CREATE INDEX IF NOT EXISTS `index_channels_categoryId_sortOrder_name` ON `channels` (`categoryId`, `sortOrder`, `name`)")

            // Movies — single-column read paths + A–Z + playlist/provider composites.
            w.execSQL("CREATE INDEX IF NOT EXISTS `index_movies_sourceId` ON `movies` (`sourceId`)")
            w.execSQL("CREATE INDEX IF NOT EXISTS `index_movies_categoryId` ON `movies` (`categoryId`)")
            w.execSQL("CREATE INDEX IF NOT EXISTS `index_movies_name` ON `movies` (`name`)")
            w.execSQL("CREATE INDEX IF NOT EXISTS `index_movies_sourceId_name` ON `movies` (`sourceId`, `name`)")
            w.execSQL("CREATE INDEX IF NOT EXISTS `index_movies_categoryId_name` ON `movies` (`categoryId`, `name`)")
            w.execSQL("CREATE INDEX IF NOT EXISTS `index_movies_sourceId_sortOrder_name` ON `movies` (`sourceId`, `sortOrder`, `name`)")
            w.execSQL("CREATE INDEX IF NOT EXISTS `index_movies_categoryId_sortOrder_name` ON `movies` (`categoryId`, `sortOrder`, `name`)")

            // Series — same coverage as Movies.
            w.execSQL("CREATE INDEX IF NOT EXISTS `index_series_sourceId` ON `series` (`sourceId`)")
            w.execSQL("CREATE INDEX IF NOT EXISTS `index_series_categoryId` ON `series` (`categoryId`)")
            w.execSQL("CREATE INDEX IF NOT EXISTS `index_series_name` ON `series` (`name`)")
            w.execSQL("CREATE INDEX IF NOT EXISTS `index_series_sourceId_name` ON `series` (`sourceId`, `name`)")
            w.execSQL("CREATE INDEX IF NOT EXISTS `index_series_categoryId_name` ON `series` (`categoryId`, `name`)")
            w.execSQL("CREATE INDEX IF NOT EXISTS `index_series_sourceId_sortOrder_name` ON `series` (`sourceId`, `sortOrder`, `name`)")
            w.execSQL("CREATE INDEX IF NOT EXISTS `index_series_categoryId_sortOrder_name` ON `series` (`categoryId`, `sortOrder`, `name`)")
            Log.d(TAG, "ensureContentIndexes create indexes ms=${SystemClock.elapsedRealtime() - indexesStartedAt}")

            // Refresh planner stats so the indices above are chosen for the grids' "WHERE … ORDER BY …" —
            // without this, stale stats from a bulk REPLACE make SQLite ignore them and full-sort.
            val analyzeStartedAt = SystemClock.elapsedRealtime()
            bulkInsertHelper.analyzeTables("movies", "series", "channels", "categories")
            Log.d(TAG, "ensureContentIndexes analyze ms=${SystemClock.elapsedRealtime() - analyzeStartedAt}")
        }.onSuccess {
            Log.i(TAG, "ensureContentIndexes end ms=${SystemClock.elapsedRealtime() - startedAt}")
        }.onFailure {
            Log.w(TAG, "ensureContentIndexes failed ms=${SystemClock.elapsedRealtime() - startedAt}", it)
        }
    }

    companion object {
        private const val TAG = "ImportFinalizer"
    }
}
