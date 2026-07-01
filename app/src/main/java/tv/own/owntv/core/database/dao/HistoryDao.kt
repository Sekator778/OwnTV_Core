package tv.own.owntv.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import tv.own.owntv.core.database.entity.WatchHistoryEntity
import tv.own.owntv.core.model.MediaType

/** Write side of watch history; the recently-watched content rows live in the content DAOs (joins). */
@Dao
interface HistoryDao {
    /** One row per (profile, type, item); REPLACE bumps `watchedAt` via the unique index. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun record(entry: WatchHistoryEntity)

    @Query("DELETE FROM watch_history WHERE profileId = :profileId AND mediaType = :type AND itemId = :itemId")
    suspend fun remove(profileId: Long, type: MediaType, itemId: Long)

    @Query("DELETE FROM watch_history WHERE profileId = :profileId")
    suspend fun clear(profileId: Long)

    /** Clear just one media type (Live / Movie / Series) for a profile. */
    @Query("DELETE FROM watch_history WHERE profileId = :profileId AND mediaType = :type")
    suspend fun clearType(profileId: Long, type: MediaType)

    @Query("SELECT COUNT(*) FROM watch_history WHERE profileId = :profileId AND mediaType = :type")
    fun count(profileId: Long, type: MediaType): Flow<Int>

    /** Everything, for Backup & Restore. */
    @Query("SELECT * FROM watch_history")
    suspend fun getAllOnce(): List<WatchHistoryEntity>

    /** User-data rows tied to one source, already joined to stable content keys for fast re-sync snapshots. */
    @Query(
        "SELECT h.profileId AS profileId, h.mediaType AS mediaType, h.itemId AS itemId, " +
            "COALESCE(c.sourceId, m.sourceId, s.sourceId, episodeSeries.sourceId) AS sourceId, " +
            "COALESCE(c.remoteId, m.remoteId, s.remoteId, e.remoteId) AS remoteId, " +
            "COALESCE(c.name, m.name, s.name) AS name, " +
            "episodeSeries.remoteId AS seriesRemoteId, episodeSeries.name AS seriesName, " +
            "e.seasonNumber AS seasonNumber, e.episodeNumber AS episodeNumber, " +
            "h.watchedAt AS at, 0 AS positionMs, 0 AS durationMs " +
            "FROM watch_history h " +
            "LEFT JOIN channels c ON h.mediaType = 'LIVE' AND h.itemId = c.id " +
            "LEFT JOIN movies m ON h.mediaType = 'MOVIE' AND h.itemId = m.id " +
            "LEFT JOIN series s ON h.mediaType = 'SERIES' AND h.itemId = s.id " +
            "LEFT JOIN episodes e ON h.mediaType = 'EPISODE' AND h.itemId = e.id " +
            "LEFT JOIN series episodeSeries ON e.seriesId = episodeSeries.id " +
            "WHERE c.sourceId = :sourceId OR m.sourceId = :sourceId OR s.sourceId = :sourceId OR episodeSeries.sourceId = :sourceId",
    )
    suspend fun exportRowsForSource(sourceId: Long): List<UserDataExportRow>

    @Query(
        "DELETE FROM watch_history WHERE profileId = :profileId AND mediaType = :type AND itemId = :itemId AND (" +
            "(:type = 'LIVE'   AND itemId NOT IN (SELECT id FROM channels)) OR " +
            "(:type = 'MOVIE'  AND itemId NOT IN (SELECT id FROM movies))   OR " +
            "(:type = 'SERIES' AND itemId NOT IN (SELECT id FROM series))" +
            ")",
    )
    suspend fun purgeSnapshotOrphan(profileId: Long, type: MediaType, itemId: Long)

    /** Drops history rows orphaned by a re-sync (see FavoriteDao.purgeOrphans); episodes excluded. */
    @Query(
        "DELETE FROM watch_history WHERE " +
            "(mediaType = 'LIVE'   AND itemId NOT IN (SELECT id FROM channels)) OR " +
            "(mediaType = 'MOVIE'  AND itemId NOT IN (SELECT id FROM movies))   OR " +
            "(mediaType = 'SERIES' AND itemId NOT IN (SELECT id FROM series))",
    )
    suspend fun purgeOrphans()
}
