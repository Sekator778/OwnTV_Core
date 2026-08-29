package tv.own.owntv.core.download

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import tv.own.owntv.core.i18n.LocaleStore

/**
 * Runs the download queue as a foreground service so transfers keep going once the user leaves
 * OwnTV — an app-scoped coroutine died with the process the moment Android reclaimed it (DL1).
 *
 * There is exactly one of these at a time (unique work, KEEP), and it lives until the queue is
 * empty, which is what preserves the one-at-a-time semantics downloads always had.
 */
class DownloadWorker(
    appContext: Context,
    params: WorkerParameters,
    private val engine: DownloadEngine,
    private val localeStore: LocaleStore,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        setForeground(DownloadNotifications.foregroundInfo(applicationContext, null, localeStore))
        var lastTick = 0L
        engine.drainQueue { progress ->
            // The transfer reports twice a second; the notification does not need that.
            val now = System.currentTimeMillis()
            if (now - lastTick > NOTIFICATION_INTERVAL_MS) {
                lastTick = now
                runCatching {
                    setForegroundAsync(DownloadNotifications.foregroundInfo(applicationContext, progress, localeStore))
                }
            }
        }
        return Result.success()
    }

    companion object {
        const val WORK_NAME = "owntv-downloads"
        private const val NOTIFICATION_INTERVAL_MS = 2_000L

        /**
         * Make sure the queue is being drained. KEEP, not REPLACE: if a worker is already running,
         * it will pick up the newly queued row itself when it finishes the current one.
         */
        fun kick(context: Context) {
            val request = OneTimeWorkRequestBuilder<DownloadWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .addTag(WORK_NAME)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
        }
    }
}
