package tv.own.owntv.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import tv.own.owntv.core.database.entity.PlaybackProgressEntity
import tv.own.owntv.core.model.MediaType

/** Resume positions for VOD and episodes (per profile). */
@Dao
interface ProgressDao {
    /** One row per (profile, type, item); REPLACE updates position via the unique index. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(progress: PlaybackProgressEntity)

    @Query("SELECT * FROM playback_progress WHERE profileId = :profileId AND mediaType = :type AND itemId = :itemId")
    fun observe(profileId: Long, type: MediaType, itemId: Long): Flow<PlaybackProgressEntity?>

    @Query("SELECT * FROM playback_progress WHERE profileId = :profileId AND mediaType = :type AND itemId = :itemId")
    suspend fun get(profileId: Long, type: MediaType, itemId: Long): PlaybackProgressEntity?

    @Query("DELETE FROM playback_progress WHERE profileId = :profileId AND mediaType = :type AND itemId = :itemId")
    suspend fun clear(profileId: Long, type: MediaType, itemId: Long)

    /** Wipe all resume positions for a profile — drives "Clear watch history" (so Home's continue-watching empties). */
    @Query("DELETE FROM playback_progress WHERE profileId = :profileId")
    suspend fun clearProfile(profileId: Long)

    /** Wipe resume positions for one media type (MOVIE / EPISODE) for a profile. */
    @Query("DELETE FROM playback_progress WHERE profileId = :profileId AND mediaType = :type")
    suspend fun clearProfileType(profileId: Long, type: MediaType)

    /** Everything, for Backup & Restore. */
    @Query("SELECT * FROM playback_progress")
    suspend fun getAllOnce(): List<PlaybackProgressEntity>

    /** User-data rows tied to one source, already joined to stable content keys for fast re-sync snapshots. */
    @Query(
        "SELECT p.profileId AS profileId, p.mediaType AS mediaType, p.itemId AS itemId, " +
            "COALESCE(c.sourceId, m.sourceId, s.sourceId, episodeSeries.sourceId) AS sourceId, " +
            "COALESCE(c.remoteId, m.remoteId, s.remoteId, e.remoteId) AS remoteId, " +
            "COALESCE(c.name, m.name, s.name) AS name, " +
            "episodeSeries.remoteId AS seriesRemoteId, episodeSeries.name AS seriesName, " +
            "e.seasonNumber AS seasonNumber, e.episodeNumber AS episodeNumber, " +
            "p.updatedAt AS at, p.positionMs AS positionMs, p.durationMs AS durationMs " +
            "FROM playback_progress p " +
            "LEFT JOIN channels c ON p.mediaType = 'LIVE' AND p.itemId = c.id " +
            "LEFT JOIN movies m ON p.mediaType = 'MOVIE' AND p.itemId = m.id " +
            "LEFT JOIN series s ON p.mediaType = 'SERIES' AND p.itemId = s.id " +
            "LEFT JOIN episodes e ON p.mediaType = 'EPISODE' AND p.itemId = e.id " +
            "LEFT JOIN series episodeSeries ON e.seriesId = episodeSeries.id " +
            "WHERE c.sourceId = :sourceId OR m.sourceId = :sourceId OR s.sourceId = :sourceId OR episodeSeries.sourceId = :sourceId",
    )
    suspend fun exportRowsForSource(sourceId: Long): List<UserDataExportRow>

    @Query(
        "DELETE FROM playback_progress WHERE profileId = :profileId AND mediaType = :type AND itemId = :itemId AND (" +
            "(:type = 'LIVE'   AND itemId NOT IN (SELECT id FROM channels)) OR " +
            "(:type = 'MOVIE'  AND itemId NOT IN (SELECT id FROM movies))   OR " +
            "(:type = 'SERIES' AND itemId NOT IN (SELECT id FROM series))" +
            ")",
    )
    suspend fun purgeSnapshotOrphan(profileId: Long, type: MediaType, itemId: Long)

    /** The episode most recently watched in [seriesId] (by this profile), or null — so opening a show can
     *  jump straight to where you left off instead of episode 1. */
    @Query(
        "SELECT itemId FROM playback_progress " +
            "WHERE profileId = :profileId AND mediaType = 'EPISODE' " +
            "AND itemId IN (SELECT id FROM episodes WHERE seriesId = :seriesId) " +
            "ORDER BY updatedAt DESC LIMIT 1",
    )
    suspend fun lastWatchedEpisodeId(profileId: Long, seriesId: Long): Long?

    /**
     * Drops resume positions orphaned by a re-sync (see FavoriteDao.purgeOrphans). Episodes are
     * excluded — they load lazily, so episode progress is kept and re-attached when the show opens.
     */
    @Query(
        "DELETE FROM playback_progress WHERE " +
            "(mediaType = 'LIVE'   AND itemId NOT IN (SELECT id FROM channels)) OR " +
            "(mediaType = 'MOVIE'  AND itemId NOT IN (SELECT id FROM movies))   OR " +
            "(mediaType = 'SERIES' AND itemId NOT IN (SELECT id FROM series))",
    )
    suspend fun purgeOrphans()
}
