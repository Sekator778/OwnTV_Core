package tv.own.owntv.core.trending

import tv.own.owntv.core.database.entity.TrendingAttemptStatus
import tv.own.owntv.core.database.entity.TrendingSnapshotEntity
import tv.own.owntv.core.database.entity.TrendingSnapshotStatus

/**
 * Why the Now Trending row is, or is not, on Home.
 *
 * The row is the one part of Home that can be empty for six different reasons, only one of which is
 * a fault — metadata turned off, a provider with no movies or series, a sync that has not run yet,
 * too few matches to be worth a row. Each needs its own sentence in settings, or the user is left
 * looking at a blank space with nothing to act on.
 *
 * Shared because both apps show that sentence and they must not reach different conclusions from the
 * same rows: a phone saying "turned off" while the television says "waiting for a sync" is a bug
 * report nobody can act on.
 */
sealed interface TrendingAvailability {
    data object WaitingForSync : TrendingAvailability
    data object Building : TrendingAvailability
    data object MetadataDisabled : TrendingAvailability
    data object NoVodScope : TrendingAvailability
    data object Failed : TrendingAvailability
    data class BelowThreshold(val matched: Int) : TrendingAvailability
    data class Showing(val count: Int, val refreshFailed: Boolean) : TrendingAvailability
}

/**
 * The order matters and is not alphabetical: a cause the user can fix outranks a symptom. Metadata
 * being off explains every other state, so it is asked first; a build in flight outranks whatever
 * the last one left behind; and rows that are actually showing outrank a stale failure, because a
 * refresh that failed on top of a good snapshot still leaves a working row on Home.
 */
fun trendingAvailability(
    states: List<TrendingSnapshotEntity>,
    metadataEnabled: Boolean,
    building: Boolean,
): TrendingAvailability = when {
    !metadataEnabled -> TrendingAvailability.MetadataDisabled
    building -> TrendingAvailability.Building
    states.any { it.status == TrendingSnapshotStatus.ELIGIBLE } -> {
        val eligible = states.filter { it.status == TrendingSnapshotStatus.ELIGIBLE }
        TrendingAvailability.Showing(
            count = eligible.sumOf { it.itemCount }.coerceAtMost(MAX_ROW_ITEMS),
            refreshFailed = eligible.any { it.lastAttemptStatus == TrendingAttemptStatus.FAILED },
        )
    }
    states.any { it.failureStage == NO_VOD_STAGE } -> TrendingAvailability.NoVodScope
    states.any { it.status == TrendingSnapshotStatus.BELOW_THRESHOLD } ->
        TrendingAvailability.BelowThreshold(states.maxOf { it.matchedItemCount })
    states.any { it.lastAttemptStatus == TrendingAttemptStatus.FAILED } -> TrendingAvailability.Failed
    else -> TrendingAvailability.WaitingForSync
}

/** The row never shows more than this, so a larger count would overstate what the user will see. */
private const val MAX_ROW_ITEMS = 10

/** Written by the builder when the provider has no movies and no series to match against. */
private const val NO_VOD_STAGE = "no VOD content"
