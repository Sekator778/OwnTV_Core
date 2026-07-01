package tv.own.owntv.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import tv.own.owntv.core.database.entity.EpgHashProjection
import tv.own.owntv.core.database.entity.EpgChannelEntity
import tv.own.owntv.core.database.entity.EpgProgrammeEntity

/** EPG storage + now/next lookups. Programmes are kept to a rolling window and pruned. */
@Dao
interface EpgDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertChannels(channels: List<EpgChannelEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProgrammes(programmes: List<EpgProgrammeEntity>)

    @Query("DELETE FROM epg_programmes WHERE sourceId = :sourceId")
    suspend fun clearSource(sourceId: Long)

    /** Hash snapshot for one channel — an indexed prefix query on the natural key, loaded lazily per channel. */
    @Query("SELECT id, epgChannelId, startMs, contentHash FROM epg_programmes WHERE sourceId = :sourceId AND epgChannelId = :epgChannelId")
    suspend fun epgHashesForChannel(sourceId: Long, epgChannelId: String): List<EpgHashProjection>

    @Query("DELETE FROM epg_programmes WHERE id IN (:ids)")
    suspend fun deleteProgrammesByIds(ids: List<Long>)

    @Query("DELETE FROM epg_programmes WHERE sourceId = :sourceId AND epgChannelId IN (:epgChannelIds)")
    suspend fun deleteProgrammesForChannels(sourceId: Long, epgChannelIds: List<String>)

    /** Drop programmes that have already finished, to bound storage. */
    @Query("DELETE FROM epg_programmes WHERE stopMs < :before")
    suspend fun prune(before: Long)

    /** Drop all programmes for one EPG channel id (used to re-fill it from cache after a smart-match). */
    @Query("DELETE FROM epg_programmes WHERE epgChannelId = :epgChannelId")
    suspend fun clearChannel(epgChannelId: String)

    /**
     * Drop programmes for one EPG channel id only within the given sources. Used by the cache re-fill so
     * we delete only what we're about to repopulate — a source whose cache isn't fresh keeps its data
     * instead of being wiped and left empty (which would drop the channel out of the guide).
     */
    @Query("DELETE FROM epg_programmes WHERE epgChannelId = :epgChannelId AND sourceId IN (:sourceIds)")
    suspend fun clearChannelForSources(epgChannelId: String, sourceIds: List<Long>)

    /** The programme airing at [now] on a given EPG channel. */
    @Query("SELECT * FROM epg_programmes WHERE epgChannelId = :epgChannelId AND startMs <= :now AND stopMs > :now ORDER BY startMs DESC LIMIT 1")
    suspend fun nowPlaying(epgChannelId: String, now: Long): EpgProgrammeEntity?

    /** Now + upcoming programmes for a channel (now/next and a short guide). */
    @Query("SELECT * FROM epg_programmes WHERE epgChannelId = :epgChannelId AND stopMs > :now ORDER BY startMs ASC LIMIT :limit")
    fun upcoming(epgChannelId: String, now: Long, limit: Int): Flow<List<EpgProgrammeEntity>>

    /** All programmes overlapping a time window for the given sources — drives the full guide grid. */
    @Query("SELECT * FROM epg_programmes WHERE sourceId IN (:sourceIds) AND stopMs > :from AND startMs < :to ORDER BY epgChannelId ASC, startMs ASC")
    suspend fun programmesInWindow(sourceIds: List<Long>, from: Long, to: Long): List<EpgProgrammeEntity>

    /** Lightweight guide rows: the grid needs titles/times, not potentially huge XMLTV descriptions. */
    @Query(
        "SELECT id, sourceId, epgChannelId, startMs, stopMs, title, NULL AS description, 0 AS contentHash " +
            "FROM epg_programmes WHERE sourceId IN (:sourceIds) AND stopMs > :from AND startMs < :to " +
            "ORDER BY epgChannelId ASC, startMs ASC",
    )
    suspend fun programmeSummariesInWindow(sourceIds: List<Long>, from: Long, to: Long): List<EpgProgrammeEntity>

    /**
     * One page of the guide window, WITHOUT the heavy `description` column. Two reasons this is paged
     * (keyset on `id`, the primary key) instead of one query:
     *   1. dropping `description` keeps rows small (grid needs only title/time);
     *   2. a big lineup still returns far more rows than fit in a single ~2 MB CursorWindow, and the
     *      androidx.sqlite statement driver can't page past one window (crash: "Couldn't read row N"),
     *      so each call must be bounded by [limit].
     * Caller loops with `afterId = lastId` until a short page, then groups by channel. description is
     * fetched lazily via [programmeDescription] when a programme's detail dialog opens.
     */
    @Query("SELECT id, sourceId, epgChannelId, startMs, stopMs, title, NULL AS description, contentHash FROM epg_programmes WHERE sourceId IN (:sourceIds) AND stopMs > :from AND startMs < :to AND id > :afterId ORDER BY id ASC LIMIT :limit")
    suspend fun programmesInWindowPage(sourceIds: List<Long>, from: Long, to: Long, afterId: Long, limit: Int): List<EpgProgrammeEntity>

    /** One programme's synopsis, loaded on demand for the detail dialog (the grid load drops it). */
    @Query("SELECT description FROM epg_programmes WHERE id = :programmeId LIMIT 1")
    suspend fun programmeDescription(programmeId: Long): String?

    /**
     * One guide row's programmes, loaded lazily when the row scrolls into view. [epgKey] must be the
     * normalized (trim+lowercase) id — programmes are stored normalized, so this hits the
     * (epgChannelId, startMs) index and stays instant even with 100k+ stored programmes.
     */
    @Query("SELECT * FROM epg_programmes WHERE epgChannelId = :epgKey AND sourceId IN (:sourceIds) AND stopMs > :from AND startMs < :to ORDER BY startMs ASC")
    suspend fun programmesForChannel(sourceIds: List<Long>, epgKey: String, from: Long, to: Long): List<EpgProgrammeEntity>

    /** Lightweight version for Guide row rendering; avoids CursorWindow pressure from descriptions. */
    @Query(
        "SELECT id, sourceId, epgChannelId, startMs, stopMs, title, NULL AS description, 0 AS contentHash " +
            "FROM epg_programmes WHERE epgChannelId = :epgKey AND sourceId IN (:sourceIds) " +
            "AND stopMs > :from AND startMs < :to ORDER BY startMs ASC",
    )
    suspend fun programmeSummariesForChannel(sourceIds: List<Long>, epgKey: String, from: Long, to: Long): List<EpgProgrammeEntity>

    /** How many programmes are stored for these sources (to tell "no guide yet" from "empty window"). */
    @Query("SELECT COUNT(*) FROM epg_programmes WHERE sourceId IN (:sourceIds)")
    suspend fun countForSources(sourceIds: List<Long>): Int


    /** Live programme count for one source — drives the EPG status shown on the source row. */
    @Query("SELECT COUNT(*) FROM epg_programmes WHERE sourceId = :sourceId")
    fun countForSource(sourceId: Long): Flow<Int>

    /** How many distinct channels actually have guide data (for the Guide's status line). */
    @Query("SELECT COUNT(DISTINCT epgChannelId) FROM epg_programmes WHERE sourceId IN (:sourceIds)")
    suspend fun countGuideChannels(sourceIds: List<Long>): Int

    /** Distinct EPG channels available across feeds — drives the manual "Match EPG" picker. */
    @Query(
        "SELECT * FROM epg_channels WHERE sourceId IN (:sourceIds) " +
            "AND (:query = '' OR LOWER(displayName) LIKE '%' || :query || '%' OR LOWER(epgChannelId) LIKE '%' || :query || '%') " +
            "GROUP BY epgChannelId ORDER BY displayName ASC LIMIT :limit",
    )
    suspend fun listEpgChannels(sourceIds: List<Long>, query: String, limit: Int): List<EpgChannelEntity>

    @Query("SELECT epgChannelId FROM epg_channels WHERE sourceId = :sourceId")
    suspend fun epgChannelIdsForSource(sourceId: Long): List<String>

    @Query("DELETE FROM epg_channels WHERE sourceId = :sourceId AND epgChannelId IN (:epgChannelIds)")
    suspend fun deleteChannelsByEpgIds(sourceId: Long, epgChannelIds: List<String>)

    @Query("DELETE FROM epg_channels WHERE sourceId = :sourceId")
    suspend fun clearChannelsForSource(sourceId: Long)
}
