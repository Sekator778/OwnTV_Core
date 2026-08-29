package tv.own.owntv.core.sync.work

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import tv.own.owntv.core.trending.TrendingRefreshOutcome
import tv.own.owntv.core.trending.TrendingRefreshProgress
import tv.own.owntv.core.trending.TrendingRepository
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.sync.TrendingActivityTracker

/** Optional post-sync enrichment. Every outcome is successful from WorkManager's catalog perspective. */
class TrendingRefreshWorker(
    context: Context,
    params: WorkerParameters,
    private val repository: TrendingRepository,
    private val sourceDao: SourceDao,
    private val activityTracker: TrendingActivityTracker,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val sourceId = inputData.getLong(KEY_SOURCE_ID, -1L)
        if (sourceId < 0) return Result.success()
        val force = inputData.getBoolean(KEY_FORCE, false)
        val sourceName = sourceDao.getById(sourceId)?.name ?: "Playlist"
        activityTracker.started(sourceId, sourceName)
        Log.i(TAG, "sourceId=$sourceId catalog sync finished; building Now Trending force=$force")
        val outcome = runCatching {
            repository.refresh(sourceId, force = force) { progress -> reportProgress(sourceId, progress) }
        }
            .onFailure { Log.w(TAG, "Unexpected refresh failure sourceId=$sourceId; preserving snapshot", it) }
            .getOrElse { repository.recordUnexpectedFailure(sourceId) }
        Log.i(TAG, "Finished sourceId=$sourceId outcome=${outcome.diagnosticName()}")
        val replaced = outcome as? TrendingRefreshOutcome.Replaced
        if (outcome == TrendingRefreshOutcome.SkippedProviderMode || outcome == TrendingRefreshOutcome.SkippedHidden) {
            activityTracker.dismiss(sourceId)
        } else {
            activityTracker.finished(
                sourceId = sourceId,
                sourceName = sourceName,
                itemCount = replaced?.itemCount ?: 0,
                eligible = replaced?.eligible == true,
                preservedFailure = outcome is TrendingRefreshOutcome.PreservedFailure,
            )
        }
        return Result.success()
    }

    private fun reportProgress(sourceId: Long, progress: TrendingRefreshProgress) {
        when (progress) {
            TrendingRefreshProgress.Fetching -> activityTracker.progress(
                sourceId,
                TrendingActivityTracker.Stage.STARTING,
            )
            is TrendingRefreshProgress.CandidatesReceived -> activityTracker.progress(
                sourceId,
                TrendingActivityTracker.Stage.RECEIVED,
                candidates = progress.total,
                movieCandidates = progress.movies,
                seriesCandidates = progress.series,
            )
            is TrendingRefreshProgress.PreparingCatalog -> activityTracker.progress(
                sourceId,
                TrendingActivityTracker.Stage.PREPARING,
                preparationProcessed = progress.processed,
                preparationTotal = progress.total,
            )
            is TrendingRefreshProgress.MatchingMovies -> activityTracker.progress(
                sourceId,
                TrendingActivityTracker.Stage.MATCHING_MOVIES,
                movieChecked = progress.checked,
                movieTarget = progress.target,
                movieMatches = progress.matched,
                matched = progress.matched,
            )
            is TrendingRefreshProgress.MatchingSeries -> activityTracker.progress(
                sourceId,
                TrendingActivityTracker.Stage.MATCHING_SERIES,
                seriesChecked = progress.checked,
                seriesTarget = progress.target,
                seriesMatches = progress.matched,
                matched = activityTracker.active.value[sourceId]?.movieMatches.orZero() + progress.matched,
            )
            is TrendingRefreshProgress.LoadingProviderSeasons -> activityTracker.progress(
                sourceId,
                TrendingActivityTracker.Stage.LOADING_SEASONS,
                seasonsProcessed = progress.processed,
                seasonsTotal = progress.total,
            )
            is TrendingRefreshProgress.Enriching -> activityTracker.progress(
                sourceId,
                TrendingActivityTracker.Stage.ENRICHING,
                matched = progress.itemCount,
                finalItems = progress.itemCount,
            )
            is TrendingRefreshProgress.Publishing -> activityTracker.progress(
                sourceId,
                TrendingActivityTracker.Stage.PUBLISHING,
                matched = progress.itemCount,
                finalItems = progress.itemCount,
            )
        }
        Log.i(TAG, "sourceId=$sourceId ${progress.diagnosticText()}")
    }

    companion object {
        private const val TAG = "TrendingRefreshWorker"
        const val KEY_SOURCE_ID = "sourceId"
        /** Maintainer-only: bypass the multi-day fetch timer (see `BuildConfig.DEV_TOOLS`). */
        const val KEY_FORCE = "force"
        const val WORK_TAG = "trending-refresh"
        fun workName(sourceId: Long) = "trending-refresh-source-$sourceId"
    }
}

private fun TrendingRefreshProgress.diagnosticText(): String = when (this) {
    TrendingRefreshProgress.Fetching -> "fetching latest Trending candidates"
    is TrendingRefreshProgress.CandidatesReceived -> "received candidates total=$total movies=$movies series=$series"
    is TrendingRefreshProgress.PreparingCatalog -> "preparing indexed provider titles processed=$processed/$total"
    is TrendingRefreshProgress.MatchingMovies -> "movie candidate checked=$checked/$candidates matched=$matched/$target"
    is TrendingRefreshProgress.MatchingSeries -> "series candidate checked=$checked/$candidates matched=$matched/$target"
    is TrendingRefreshProgress.LoadingProviderSeasons -> "loading provider seasons processed=$processed/$total"
    is TrendingRefreshProgress.Enriching -> "enriching final Now Trending itemCount=$itemCount"
    is TrendingRefreshProgress.Publishing -> "publishing complete Now Trending itemCount=$itemCount"
}

private fun Int?.orZero(): Int = this ?: 0

private fun TrendingRefreshOutcome.diagnosticName(): String = when (this) {
    TrendingRefreshOutcome.SkippedProviderMode -> "provider-mode"
    TrendingRefreshOutcome.SkippedHidden -> "home-row-hidden"
    TrendingRefreshOutcome.SourceMissing -> "source-missing"
    TrendingRefreshOutcome.NoVodScope -> "no-vod"
    is TrendingRefreshOutcome.PreservedFailure -> "preserved-$stage"
    is TrendingRefreshOutcome.Replaced -> "replaced-$itemCount"
}
