package tv.own.owntv.core.sync.work

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import tv.own.owntv.core.repository.SourceRepository
import tv.own.owntv.core.sync.SyncResult

class CatalogSyncWorker(
    context: Context,
    params: WorkerParameters,
    private val sourceRepository: SourceRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val sourceId = inputData.getLong(KEY_SOURCE_ID, -1L)
        val reason = inputData.getString(KEY_REASON) ?: "unknown"
        if (sourceId < 0) return Result.failure()

        val source = sourceRepository.getById(sourceId) ?: run {
            Log.w(TAG, "Source $sourceId not found — skipping ($reason)")
            return Result.failure()
        }

        Log.i(TAG, "Starting sync for source ${source.id} (${source.name}) reason=$reason")

        val result = sourceRepository.sync(source, onProgress = { stage ->
            setProgressAsync(workDataOf(
                "label" to stage.label,
                "processed" to stage.processed,
            ))
        })

        return when (result) {
            SyncResult.Success -> {
                Log.i(TAG, "Sync succeeded for source ${source.id} (${source.name})")
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
    }
}
