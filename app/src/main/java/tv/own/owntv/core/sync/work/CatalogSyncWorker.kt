package tv.own.owntv.core.sync.work

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.launcher.LauncherIntegrationRepository
import tv.own.owntv.core.repository.SourceRepository
import tv.own.owntv.core.sync.ImportFinalizer
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

        val result = sourceRepository.sync(source, onProgress = { stage ->
            setProgressAsync(workDataOf(
                KEY_PROGRESS_LABEL to stage.label,
                KEY_PROGRESS_PROCESSED to stage.processed,
                KEY_PROGRESS_TOTAL to (stage.total ?: -1),
                KEY_PROGRESS_OVERALL to stage.overallPercent,
                KEY_PROGRESS_BREAKDOWN to stage.breakdown,
            ))
        }, contentTypes = contentTypes)

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

    companion object {
        const val TAG = "CatalogSyncWorker"
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
    }
}
