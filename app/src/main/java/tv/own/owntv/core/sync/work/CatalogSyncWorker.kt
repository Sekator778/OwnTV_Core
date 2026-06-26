package tv.own.owntv.core.sync.work

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.launcher.LauncherIntegrationRepository
import tv.own.owntv.core.repository.SourceRepository
import tv.own.owntv.core.sync.ImportFinalizer
import tv.own.owntv.core.sync.ImportStage
import tv.own.owntv.core.sync.SyncContentTypes
import tv.own.owntv.core.sync.SyncResult

class CatalogSyncWorker(
    context: Context,
    params: WorkerParameters,
    private val sourceRepository: SourceRepository,
    private val sourceDao: SourceDao,
    private val importFinalizer: ImportFinalizer,
    private val launcherIntegrationRepository: LauncherIntegrationRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val sourceId = inputData.getLong(KEY_SOURCE_ID, -1L)
        val reason = inputData.getString(KEY_REASON) ?: "unknown"
        if (sourceId < 0) return Result.failure()

        val contentTypes = SyncContentTypes(
            live = inputData.getBoolean(KEY_LIVE, true),
            movies = inputData.getBoolean(KEY_MOVIES, true),
            series = inputData.getBoolean(KEY_SERIES, true),
        )

        val source = sourceRepository.getById(sourceId) ?: run {
            Log.w(TAG, "Source $sourceId not found — skipping ($reason)")
            return Result.failure()
        }

        Log.i(TAG, "Starting sync for source ${source.id} (${source.name}) reason=$reason contentTypes=$contentTypes")
        val progressPublisher = ProgressPublisher()
        progressPublisher.publishStarting()

        val result = sourceRepository.sync(source, onProgress = { stage ->
            progressPublisher.publish(stage)
        }, contentTypes = contentTypes)
        progressPublisher.flush()

        return when (result) {
            is SyncResult.Success -> {
                val warningText = result.warnings.takeIf { it.isNotEmpty() }?.joinToString { it.label }
                Log.i(TAG, "Sync succeeded for source ${source.id} (${source.name}) warnings=$warningText")
                runCatching { importFinalizer.finalize(source) }
                sourceDao.profileIdsForSource(source.id).forEach { profileId ->
                    runCatching { launcherIntegrationRepository.refreshProfile(profileId) }
                }
                Result.success()
            }
            is SyncResult.Failed -> {
                Log.w(TAG, "Sync failed for source ${source.id}: ${result.message}")
                Result.failure()
            }
            SyncResult.Cancelled -> Result.failure()
        }
    }

    private inner class ProgressPublisher {
        private var lastEmitAtMs = 0L
        private var lastLabel: String? = null
        private var lastOverall = -1
        private var pending: ImportStage? = null

        fun publishStarting() {
            setProgress(
                workDataOf(
                    KEY_PROGRESS_LABEL to "Starting",
                    KEY_PROGRESS_PROCESSED to 0,
                    KEY_PROGRESS_TOTAL to -1,
                    KEY_PROGRESS_OVERALL to 0,
                    KEY_PROGRESS_BREAKDOWN to "",
                    KEY_PROGRESS_LIVE_PROCESSED to 0,
                    KEY_PROGRESS_MOVIES_PROCESSED to 0,
                    KEY_PROGRESS_SERIES_PROCESSED to 0,
                ),
            )
            lastLabel = "Starting"
            lastOverall = 0
        }

        fun publish(stage: ImportStage) {
            pending = stage
            val now = SystemClock.elapsedRealtime()
            if (
                stage.label != lastLabel ||
                stage.overallPercent != lastOverall ||
                now - lastEmitAtMs >= PROGRESS_MIN_INTERVAL_MS
            ) {
                emit(stage, now)
            }
        }

        fun flush() {
            pending?.let { emit(it, SystemClock.elapsedRealtime()) }
        }

        private fun emit(stage: ImportStage, now: Long) {
            setProgress(stage.toWorkData())
            lastEmitAtMs = now
            lastLabel = stage.label
            lastOverall = stage.overallPercent
            pending = null
        }

        private fun setProgress(data: Data) {
            setProgressAsync(data)
        }
    }

    companion object {
        const val TAG = "CatalogSyncWorker"
        private const val PROGRESS_MIN_INTERVAL_MS = 500L
        const val KEY_SOURCE_ID = "sourceId"
        const val KEY_REASON = "reason"
        const val KEY_LIVE = "live"
        const val KEY_MOVIES = "movies"
        const val KEY_SERIES = "series"
        const val KEY_PROGRESS_LABEL = "label"
        const val KEY_PROGRESS_PROCESSED = "processed"
        const val KEY_PROGRESS_TOTAL = "total"
        const val KEY_PROGRESS_OVERALL = "overall"
        const val KEY_PROGRESS_BREAKDOWN = "breakdown"
        const val KEY_PROGRESS_LIVE_PROCESSED = "liveProcessed"
        const val KEY_PROGRESS_MOVIES_PROCESSED = "moviesProcessed"
        const val KEY_PROGRESS_SERIES_PROCESSED = "seriesProcessed"
    }
}

private fun ImportStage.toWorkData(): Data =
    workDataOf(
        CatalogSyncWorker.KEY_PROGRESS_LABEL to label,
        CatalogSyncWorker.KEY_PROGRESS_PROCESSED to processed,
        CatalogSyncWorker.KEY_PROGRESS_TOTAL to (total ?: -1),
        CatalogSyncWorker.KEY_PROGRESS_OVERALL to overallPercent,
        CatalogSyncWorker.KEY_PROGRESS_BREAKDOWN to breakdown,
        CatalogSyncWorker.KEY_PROGRESS_LIVE_PROCESSED to liveProcessed,
        CatalogSyncWorker.KEY_PROGRESS_MOVIES_PROCESSED to moviesProcessed,
        CatalogSyncWorker.KEY_PROGRESS_SERIES_PROCESSED to seriesProcessed,
    )
