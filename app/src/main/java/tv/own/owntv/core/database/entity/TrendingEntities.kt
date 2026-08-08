package tv.own.owntv.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import tv.own.owntv.core.model.MediaType

/** Persisted result of the most recent completed Trending refresh for one IPTV source. */
@Entity(
    tableName = "trending_snapshots",
    primaryKeys = ["sourceId"],
    foreignKeys = [
        ForeignKey(
            entity = SourceEntity::class,
            parentColumns = ["id"],
            childColumns = ["sourceId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class TrendingSnapshotEntity(
    val sourceId: Long,
    val status: TrendingSnapshotStatus,
    val metadataLanguage: String,
    val refreshedAt: Long,
    /** Time the candidate endpoints answered successfully, before local matching/enrichment. */
    val candidateFetchedAt: Long,
    /** Identifies one atomic state/items replacement and rejects accidentally mixed item lists. */
    val generationId: String,
    val itemCount: Int,
    /** Matches found by the completed attempt, including a below-threshold result whose item table is empty. */
    @ColumnInfo(defaultValue = "0") val matchedItemCount: Int = 0,
    @ColumnInfo(defaultValue = "0") val lastAttemptAt: Long = 0,
    @ColumnInfo(defaultValue = "'NEVER'") val lastAttemptStatus: TrendingAttemptStatus = TrendingAttemptStatus.NEVER,
    val failureStage: String? = null,
)

enum class TrendingSnapshotStatus {
    NEVER_BUILT,
    BELOW_THRESHOLD,
    ELIGIBLE,
}

enum class TrendingAttemptStatus {
    NEVER,
    SUCCESS,
    BELOW_THRESHOLD,
    FAILED,
}

/**
 * One exact, provider-playable item selected for a source snapshot.
 *
 * There is deliberately no movie/series foreign key: those catalog tables can be replaced during a
 * resync. Home resolves [providerItemId] and the stable provider fields after the import is complete.
 */
@Entity(
    tableName = "trending_items",
    primaryKeys = ["sourceId", "position"],
    foreignKeys = [
        ForeignKey(
            entity = TrendingSnapshotEntity::class,
            parentColumns = ["sourceId"],
            childColumns = ["sourceId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["sourceId", "mediaType", "providerItemId"]),
        Index(value = ["mediaType", "tmdbId"]),
    ],
)
data class TrendingItemEntity(
    val sourceId: Long,
    /** Final interleaved showcase position, zero based. */
    val position: Int,
    val tmdbId: Int,
    /** Only [MediaType.MOVIE] and [MediaType.SERIES] are valid. */
    val mediaType: MediaType,
    /** Rank from the original movie or TV Trending response before provider matching. */
    val trendingRank: Int,
    val providerItemId: Long,
    val providerRemoteId: String?,
    /** Remote ID when available, otherwise the provider-name fallback chosen by the matcher. */
    val providerStableKey: String,
    val providerRawName: String,
    val canonicalTitle: String,
    /** Advertised/inferred provider values; null means the provider did not label them. */
    val providerLanguage: String?,
    val advertisedQuality: String?,
    /** Optional secondary advertised flags such as HDR10 or Dolby Vision. */
    val advertisedCapabilities: String?,
    val localizedTitle: String,
    val originalTitle: String?,
    val year: Int?,
    val overview: String?,
    val posterPath: String?,
    val backdropPath: String?,
    val rating: Double?,
    val trailerKey: String?,
    val generationId: String,
    val refreshedAt: Long,
)

data class TrendingSnapshot(
    val state: TrendingSnapshotEntity,
    val items: List<TrendingItemEntity>,
)
