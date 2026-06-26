package tv.own.owntv.core.sync.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import tv.own.owntv.core.sync.SyncContentTypes

class CatalogSyncScheduler(private val context: Context) {

    fun enqueueSync(
        sourceId: Long,
        reason: String = "manual",
        contentTypes: SyncContentTypes = SyncContentTypes(),
    ) {
        val request = OneTimeWorkRequestBuilder<CatalogSyncWorker>()
            .setInputData(workDataOf(
                CatalogSyncWorker.KEY_SOURCE_ID to sourceId,
                CatalogSyncWorker.KEY_REASON to reason,
                CatalogSyncWorker.KEY_LIVE to contentTypes.live,
                CatalogSyncWorker.KEY_MOVIES to contentTypes.movies,
                CatalogSyncWorker.KEY_SERIES to contentTypes.series,
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

    fun observeSync(sourceId: Long): Flow<CatalogSyncState> =
        WorkManager.getInstance(context)
            .getWorkInfosForUniqueWorkFlow(workName(sourceId))
            .map { infos ->
                val active = infos.firstOrNull { !it.state.isFinished }
                if (active == null) {
                    CatalogSyncState.Idle
                } else {
                    CatalogSyncState.Syncing(
                        label = active.progress.getString(CatalogSyncWorker.KEY_PROGRESS_LABEL),
                        processed = active.progress.getInt(CatalogSyncWorker.KEY_PROGRESS_PROCESSED, 0),
                        overallPercent = active.progress.getInt(CatalogSyncWorker.KEY_PROGRESS_OVERALL, 0),
                        breakdown = active.progress.getString(CatalogSyncWorker.KEY_PROGRESS_BREAKDOWN) ?: "",
                    )
                }
            }
            .distinctUntilChanged()

    companion object {
        const val TAG_CATALOG_SYNC = "catalog-sync"
        fun workName(sourceId: Long) = "catalog-sync-source-$sourceId"
    }
}

sealed interface CatalogSyncState {
    data object Idle : CatalogSyncState
    data class Syncing(
        val label: String?,
        val processed: Int,
        val overallPercent: Int,
        val breakdown: String,
    ) : CatalogSyncState

    val isActive: Boolean
        get() = this is Syncing
}
