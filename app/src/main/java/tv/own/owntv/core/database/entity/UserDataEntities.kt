package tv.own.owntv.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import tv.own.owntv.core.model.DownloadStatus
import tv.own.owntv.core.model.MediaType

/*
 * User data is scoped per profile. `itemId` is the local id of a channel/movie/series/episode and is
 * disambiguated by `mediaType` (it can't be a single foreign key since it points at several tables),
 * so referential cleanup of these rows is handled in the repository layer.
 */

@Entity(
    tableName = "favorites",
    foreignKeys = [
        ForeignKey(entity = ProfileEntity::class, parentColumns = ["id"], childColumns = ["profileId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [
        Index("profileId"),
        Index(value = ["profileId", "mediaType", "itemId"], unique = true),
    ],
)
data class FavoriteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val profileId: Long,
    val mediaType: MediaType,
    val itemId: Long,
    val addedAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "watch_history",
    foreignKeys = [
        ForeignKey(entity = ProfileEntity::class, parentColumns = ["id"], childColumns = ["profileId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [
        Index("profileId"),
        Index(value = ["profileId", "watchedAt"]),
        Index(value = ["profileId", "mediaType", "itemId"], unique = true),
    ],
)
data class WatchHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val profileId: Long,
    val mediaType: MediaType,
    val itemId: Long,
    val watchedAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "playback_progress",
    foreignKeys = [
        ForeignKey(entity = ProfileEntity::class, parentColumns = ["id"], childColumns = ["profileId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [
        Index("profileId"),
        Index(value = ["profileId", "mediaType", "itemId"], unique = true),
    ],
)
data class PlaybackProgressEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val profileId: Long,
    val mediaType: MediaType,
    val itemId: Long,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long = System.currentTimeMillis(),
)

/**
 * Per-profile manual ordering for individual items ("Move up/down"). Each row pins one content item
 * (channel/movie/series) to a [position] within a [contextKey] — a category's stable key for a folder,
 * or [FAV_CONTEXT] for the Favorites list. The browsing queries LEFT JOIN this table and order by
 * [position] first, falling back to the natural order for items without a row. `itemId` is volatile
 * (content is clear-then-insert on every sync), so these rows are snapshotted with stable keys and
 * re-attached after a sync by UserDataResolver, just like favorites/history.
 */
@Entity(
    tableName = "content_order",
    foreignKeys = [
        ForeignKey(entity = ProfileEntity::class, parentColumns = ["id"], childColumns = ["profileId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [
        Index("profileId"),
        Index(value = ["profileId", "mediaType", "contextKey"]),
        Index(value = ["profileId", "mediaType", "contextKey", "itemId"], unique = true),
    ],
)
data class ContentOrderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val profileId: Long,
    /** LIVE / MOVIE / SERIES — never EPISODE (episodes aren't reorderable). */
    val mediaType: MediaType,
    /** A category stable key (CustomizeKeys.category) for a folder, or [FAV_CONTEXT] for Favorites. */
    val contextKey: String,
    val itemId: Long,
    val position: Int,
) {
    companion object {
        /** Sentinel [contextKey] for the per-section Favorites list. */
        const val FAV_CONTEXT = "__fav__"
    }
}

@Entity(
    tableName = "downloads",
    foreignKeys = [
        ForeignKey(entity = ProfileEntity::class, parentColumns = ["id"], childColumns = ["profileId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [
        Index("profileId"),
        Index("status"),
        Index(value = ["profileId", "mediaType", "itemId"], unique = true),
    ],
)
data class DownloadEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val profileId: Long,
    /** Movies & series episodes only — never LIVE. */
    val mediaType: MediaType,
    val itemId: Long,
    val title: String,
    val posterUrl: String? = null,
    val streamUrl: String,
    val filePath: String? = null,
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val downloadedBytes: Long = 0,
    val totalBytes: Long = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

/**
 * Per-profile, per-series presentation order for the series episode view ("Sorting" popup): the
 * season rail and the episode list are ordered independently, each Oldest-first (ascending) or
 * Newest-first (descending). A series with no row uses the defaults (both ascending), so the table
 * only ever holds the shows the user actually changed.
 *
 * PRESENTATION ONLY — playback order (autoplay next episode) is never affected by these values.
 *
 * Deliberately a separate user-data table rather than a column on `series`: the catalog tables are
 * bulk-synced and would wipe it. Like favorites/history/content_order it foreign-keys the profile
 * only — `seriesId` is a volatile local id (content is re-inserted on sync), so stale rows are
 * dropped by [purgeOrphans] and re-attached from stable keys by UserDataResolver.
 */
@Entity(
    tableName = "series_sort_order",
    foreignKeys = [
        ForeignKey(entity = ProfileEntity::class, parentColumns = ["id"], childColumns = ["profileId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [
        Index("profileId"),
        Index(value = ["profileId", "seriesId"], unique = true),
    ],
)
data class SeriesSortOrderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val profileId: Long,
    /** Local `series.id`. Volatile across a re-sync/restore — see the class KDoc. */
    val seriesId: Long,
    /** Season rail order. false = Oldest first (default), true = Newest first. */
    val seasonsDescending: Boolean = false,
    /** Episode list order. false = Oldest first (default), true = Newest first. */
    val episodesDescending: Boolean = false,
)
