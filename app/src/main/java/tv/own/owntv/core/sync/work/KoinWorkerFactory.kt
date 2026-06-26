package tv.own.owntv.core.sync.work

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import org.koin.core.context.GlobalContext

class KoinWorkerFactory : WorkerFactory() {
    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters,
    ): ListenableWorker? = when (workerClassName) {
        CatalogSyncWorker::class.java.name -> CatalogSyncWorker(
            appContext,
            workerParameters,
            GlobalContext.get().get(),
        )
        else -> null
    }
}
