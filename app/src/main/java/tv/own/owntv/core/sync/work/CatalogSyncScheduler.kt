package tv.own.owntv.core.sync.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf

class CatalogSyncScheduler(private val context: Context) {

    fun enqueueSync(sourceId: Long, reason: String = "manual") {
        val request = OneTimeWorkRequestBuilder<CatalogSyncWorker>()
            .setInputData(workDataOf(
                CatalogSyncWorker.KEY_SOURCE_ID to sourceId,
                CatalogSyncWorker.KEY_REASON to reason,
            ))
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .addTag(TAG_CATALOG_SYNC)
            .addTag("source-$sourceId")
            .build()

        WorkManager.getInstance(context)
            .enqueueUniqueWork(workName(sourceId), ExistingWorkPolicy.REPLACE, request)
    }

    fun cancelSync(sourceId: Long) {
        WorkManager.getInstance(context).cancelUniqueWork(workName(sourceId))
    }

    companion object {
        const val TAG_CATALOG_SYNC = "catalog-sync"
        fun workName(sourceId: Long) = "catalog-sync-source-$sourceId"
    }
}
