package tv.own.owntv.core.sync.work

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import tv.own.owntv.core.epg.EpgSourceStore
import tv.own.owntv.core.network.ConnectivityObserver
import tv.own.owntv.core.repository.EpgRepository
import tv.own.owntv.core.util.friendlySyncError

class EpgSyncWorker(
    context: Context,
    params: WorkerParameters,
    private val epgRepository: EpgRepository,
    private val store: EpgSourceStore,
    private val connectivity: ConnectivityObserver,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val sourceId = inputData.getLong(KEY_SOURCE_ID, Long.MIN_VALUE)
        val reason = inputData.getString(KEY_REASON) ?: "unknown"
        if (sourceId == Long.MIN_VALUE) return Result.failure()

        val source = store.getAll().firstOrNull { it.id == sourceId } ?: run {
            Log.i(TAG, "Skipping stale EPG sync sourceId=$sourceId reason=$reason")
            return Result.success()
        }

        val baseProgrammes = epgRepository.countProgrammes(source.id)
        val progress = ProgressPublisher(baseProgrammes)
        val startedAt = SystemClock.elapsedRealtime()
        Log.i(TAG, "Starting EPG sync sourceId=${source.id} reason=$reason")

        try {
            val programmes = epgRepository.refreshUrl(source.id, source.url, source.userAgent) { channels, count ->
                progress.publish(channels, count)
            }
            progress.flush()
            store.setSynced(source.id, System.currentTimeMillis(), null)
            Log.i(
                TAG,
                "EPG sync finished sourceId=${source.id} reason=$reason programmes=$programmes ms=${SystemClock.elapsedRealtime() - startedAt}",
            )
            return Result.success()
        } catch (c: CancellationException) {
            Log.i(TAG, "EPG sync cancelled sourceId=${source.id} reason=$reason")
            throw c
        } catch (e: Exception) {
            val message = friendlySyncError(e.message, connectivity.isOnlineNow())
            store.setSynced(source.id, System.currentTimeMillis(), message)
            Log.w(TAG, "EPG sync failed sourceId=${source.id} reason=$reason", e)
            return Result.failure()
        }
    }

    private inner class ProgressPublisher(private val baseProgrammes: Int) {
        private var lastEmitAtMs = 0L
        private var lastChannels = -1
        private var lastProgrammes = -1
        private var pendingChannels = 0
        private var pendingProgrammes = 0
        private var hasPending = false

        fun publish(channels: Int, programmes: Int) {
            pendingChannels = channels
            pendingProgrammes = programmes
            hasPending = true
            val now = SystemClock.elapsedRealtime()
            if (lastEmitAtMs == 0L || (now - lastEmitAtMs >= PROGRESS_MIN_INTERVAL_MS && shouldEmit())) {
                emit(now)
            }
        }

        fun flush() {
            if (hasPending) emit(SystemClock.elapsedRealtime())
        }

        private fun shouldEmit(): Boolean =
            pendingChannels != lastChannels || pendingProgrammes != lastProgrammes

        private fun emit(now: Long) {
            if (!hasPending) return
            val channels = pendingChannels
            val programmes = pendingProgrammes
            if (channels == lastChannels && programmes == lastProgrammes && lastEmitAtMs != 0L) {
                hasPending = false
                return
            }
            setProgressAsync(
                workDataOf(
                    KEY_PROGRESS_CHANNELS to channels,
                    KEY_PROGRESS_PROGRAMMES to programmes,
                    KEY_BASE_PROGRAMMES to baseProgrammes,
                ),
            )
            lastEmitAtMs = now
            lastChannels = channels
            lastProgrammes = programmes
            hasPending = false
        }
    }

    companion object {
        const val TAG = "EpgSyncWorker"
        private const val PROGRESS_MIN_INTERVAL_MS = 750L
        const val KEY_SOURCE_ID = "sourceId"
        const val KEY_REASON = "reason"
        const val KEY_PROGRESS_CHANNELS = "channels"
        const val KEY_PROGRESS_PROGRAMMES = "programmes"
        const val KEY_BASE_PROGRAMMES = "baseProgrammes"
    }
}
