package tv.own.owntv.core.download

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tv.own.owntv.core.database.dao.DownloadDao
import tv.own.owntv.core.database.entity.DownloadEntity
import tv.own.owntv.core.model.DownloadStatus
import tv.own.owntv.core.model.MediaType
import tv.own.owntv.core.storage.StorageAccess
import tv.own.owntv.features.settings.data.SettingsRepository
import java.io.File

/** Free/total bytes of the volume backing the download root. */
data class DownloadStorageInfo(val freeBytes: Long, val totalBytes: Long) {
    val usedBytes: Long get() = (totalBytes - freeBytes).coerceAtLeast(0L)
    val usedFraction: Float get() = if (totalBytes > 0) (usedBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
}

/**
 * Phase 12 — downloads movies & series episodes for offline playback. Files go under the user-chosen
 * download folder, organised as `Movies/<name>.<ext>` and `Series/<show>/Season N/<episode>.<ext>`.
 *
 * This is the queue-control half only: it writes [DownloadDao] rows and hands the actual transfers
 * to [DownloadWorker]/[DownloadEngine], which run in a foreground service and therefore survive the
 * user leaving the app (audit item DL1). [DownloadDao] remains the single source of truth, and
 * downloads still run strictly one at a time.
 */
class DownloadManager(
    private val context: Context,
    private val downloadDao: DownloadDao,
    private val settings: SettingsRepository,
    private val engine: DownloadEngine,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        // Anything left QUEUED — or RUNNING when the process was killed — is picked up again.
        engine.markQueued()
        DownloadWorker.kick(context)
    }

    fun observe(profileId: Long): Flow<List<DownloadEntity>> = downloadDao.observeForProfile(profileId)

    /** Episode downloads for one series (poster-panel aggregate status). */
    fun observeForSeries(seriesId: Long): Flow<List<DownloadEntity>> = downloadDao.observeForSeries(seriesId)

    /** Free/total space of the volume holding the current download root (for the Downloads storage bar). */
    suspend fun storageInfo(): DownloadStorageInfo = withContext(Dispatchers.IO) {
        val root = runCatching { StorageAccess.resolveRoot(context, settings.downloadRoot.first()) }
            .getOrNull() ?: StorageAccess.defaultRoot(context)
        DownloadStorageInfo(freeBytes = root.usableSpace, totalBytes = root.totalSpace)
    }

    /** Queue a download into `<root>/<relativeDir>/<fileName>`. */
    fun enqueue(
        profileId: Long, mediaType: MediaType, itemId: Long, title: String, posterUrl: String?,
        streamUrl: String, relativeDir: String, fileName: String,
    ) {
        scope.launch {
            val root = StorageAccess.resolveRoot(context, settings.downloadRoot.first())
            val target = File(File(root, relativeDir).apply { mkdirs() }, fileName)
            downloadDao.upsert(
                DownloadEntity(
                    profileId = profileId, mediaType = mediaType, itemId = itemId, title = title,
                    posterUrl = posterUrl, streamUrl = streamUrl, filePath = target.absolutePath,
                    status = DownloadStatus.QUEUED,
                ),
            )
            kick()
        }
    }

    fun retry(download: DownloadEntity) {
        scope.launch {
            // Stop a still-running attempt BEFORE deleting its file — otherwise the writer keeps
            // streaming into the unlinked file and "completes" a download that no longer exists.
            engine.suspendTransfer(download.id)
            try {
                download.filePath?.let { runCatching { File(it).delete() } } // start fresh
                downloadDao.updateProgress(download.id, DownloadStatus.QUEUED, 0, download.totalBytes, System.currentTimeMillis())
            } finally {
                engine.release(download.id)
            }
            kick()
        }
    }

    /** Stop the running download but keep the partial file so it can resume. */
    fun pause(download: DownloadEntity) {
        scope.launch {
            // Wait for the writer to actually stop before recording how far it got (DL2) — cancel()
            // alone returns while the transfer is still appending bytes.
            engine.suspendTransfer(download.id)
            try {
                val d = downloadDao.getById(download.id) ?: download
                val bytes = DownloadResume.bytesOnDisk(d.filePath?.let(::File), d.downloadedBytes)
                downloadDao.updateProgress(d.id, DownloadStatus.PAUSED, bytes, d.totalBytes, System.currentTimeMillis())
            } finally {
                engine.release(download.id)
            }
        }
    }

    /** Continue a paused download from where it stopped (HTTP Range). */
    fun resume(download: DownloadEntity) {
        scope.launch {
            val d = downloadDao.getById(download.id) ?: download
            val bytes = DownloadResume.bytesOnDisk(d.filePath?.let(::File), d.downloadedBytes)
            downloadDao.updateProgress(d.id, DownloadStatus.QUEUED, bytes, d.totalBytes, System.currentTimeMillis())
            kick()
        }
    }

    fun delete(download: DownloadEntity) {
        scope.launch {
            engine.suspendTransfer(download.id)
            try {
                download.filePath?.let { runCatching { File(it).delete() } }
                downloadDao.delete(download)
            } finally {
                engine.release(download.id)
            }
            kick()
        }
    }

    private fun kick() {
        engine.markQueued()
        DownloadWorker.kick(context)
    }
}
