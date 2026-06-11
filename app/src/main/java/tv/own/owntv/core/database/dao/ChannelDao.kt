package tv.own.owntv.core.database.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import tv.own.owntv.core.database.entity.ChannelEntity

/**
 * Live TV channels. Big lists are exposed as [PagingSource]; totals come from indexed COUNT queries
 * (per the plan's count requirements). FTS search joins via `channels_fts.rowid = channels.id`.
 * Favorites/history join the profile-scoped user-data tables.
 */
@Dao
interface ChannelDao {
    /** Batch insert; the sync layer calls this in chunks (~500) inside a transaction. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(channels: List<ChannelEntity>)

    @Query("DELETE FROM channels WHERE sourceId = :sourceId")
    suspend fun clearSource(sourceId: Long)

    @Query("SELECT * FROM channels WHERE id = :id")
    suspend fun getById(id: Long): ChannelEntity?

    /** Channels that carry an EPG id (so the guide grid only lists channels that can have a schedule). */
    @Query(
        "SELECT * FROM channels WHERE sourceId IN (:sourceIds) AND epgChannelId IS NOT NULL AND epgChannelId != '' " +
            "ORDER BY number ASC, name ASC LIMIT :limit",
    )
    suspend fun channelsForGuide(sourceIds: List<Long>, limit: Int): List<ChannelEntity>

    // --- Browsing ---
    @Query("SELECT * FROM channels WHERE categoryId = :categoryId ORDER BY sortOrder ASC, name ASC")
    fun pagingByCategory(categoryId: Long): PagingSource<Int, ChannelEntity>

    @Query("SELECT * FROM channels WHERE sourceId IN (:sourceIds) ORDER BY name ASC")
    fun pagingAll(sourceIds: List<Long>): PagingSource<Int, ChannelEntity>

    // --- Counts ---
    @Query("SELECT COUNT(*) FROM channels WHERE categoryId = :categoryId")
    fun countByCategory(categoryId: Long): Flow<Int>

    @Query("SELECT COUNT(*) FROM channels WHERE sourceId IN (:sourceIds)")
    fun countAll(sourceIds: List<Long>): Flow<Int>

    // --- Search (FTS4) ---
    @Query(
        "SELECT * FROM channels WHERE sourceId IN (:sourceIds) " +
            "AND id IN (SELECT rowid FROM channels_fts WHERE channels_fts MATCH :query) ORDER BY name ASC",
    )
    fun search(query: String, sourceIds: List<Long>): PagingSource<Int, ChannelEntity>

    // --- Inline folder-scoped search (substring LIKE, matches the user's expectation) ---
    @Query("SELECT * FROM channels WHERE sourceId IN (:sourceIds) AND name LIKE '%' || :query || '%' ORDER BY name ASC")
    fun searchAll(query: String, sourceIds: List<Long>): PagingSource<Int, ChannelEntity>

    @Query("SELECT * FROM channels WHERE categoryId = :categoryId AND name LIKE '%' || :query || '%' ORDER BY sortOrder ASC, name ASC")
    fun searchInCategory(query: String, categoryId: Long): PagingSource<Int, ChannelEntity>

    /** Bounded list for global search (across all of a profile's sources). */
    @Query("SELECT * FROM channels WHERE sourceId IN (:sourceIds) AND name LIKE '%' || :query || '%' ORDER BY name ASC LIMIT :limit")
    suspend fun searchList(query: String, sourceIds: List<Long>, limit: Int): List<ChannelEntity>

    @Query(
        "SELECT c.* FROM channels c INNER JOIN favorites f ON f.itemId = c.id AND f.mediaType = 'LIVE' " +
            "WHERE f.profileId = :profileId AND c.name LIKE '%' || :query || '%' ORDER BY f.addedAt DESC",
    )
    fun searchFavorites(query: String, profileId: Long): PagingSource<Int, ChannelEntity>

    @Query(
        "SELECT c.* FROM channels c INNER JOIN watch_history h ON h.itemId = c.id AND h.mediaType = 'LIVE' " +
            "WHERE h.profileId = :profileId AND c.name LIKE '%' || :query || '%' ORDER BY h.watchedAt DESC",
    )
    fun searchHistory(query: String, profileId: Long): PagingSource<Int, ChannelEntity>

    // --- Favorites / History (profile-scoped) ---
    @Query(
        "SELECT c.* FROM channels c " +
            "INNER JOIN favorites f ON f.itemId = c.id AND f.mediaType = 'LIVE' " +
            "WHERE f.profileId = :profileId ORDER BY f.addedAt DESC",
    )
    fun pagingFavorites(profileId: Long): PagingSource<Int, ChannelEntity>

    @Query("SELECT COUNT(*) FROM favorites WHERE profileId = :profileId AND mediaType = 'LIVE'")
    fun countFavorites(profileId: Long): Flow<Int>

    @Query(
        "SELECT c.* FROM channels c " +
            "INNER JOIN watch_history h ON h.itemId = c.id AND h.mediaType = 'LIVE' " +
            "WHERE h.profileId = :profileId ORDER BY h.watchedAt DESC",
    )
    fun pagingHistory(profileId: Long): PagingSource<Int, ChannelEntity>

    /** Recently-watched row at the top of Live TV. */
    @Query(
        "SELECT c.* FROM channels c " +
            "INNER JOIN watch_history h ON h.itemId = c.id AND h.mediaType = 'LIVE' " +
            "WHERE h.profileId = :profileId ORDER BY h.watchedAt DESC LIMIT :limit",
    )
    fun recentlyWatched(profileId: Long, limit: Int): Flow<List<ChannelEntity>>
}
