package tv.own.owntv.core.download

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import okhttp3.Request
import tv.own.owntv.core.database.dao.DownloadDao
import tv.own.owntv.core.database.dao.MovieDao
import tv.own.owntv.core.database.dao.SeriesDao
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.database.entity.DownloadEntity
import tv.own.owntv.core.model.DownloadStatus
import tv.own.owntv.core.model.MediaType
import tv.own.owntv.core.network.HttpClient
import tv.own.owntv.core.stalker.StalkerClient
import tv.own.owntv.core.stalker.StreamUrlResolver
import tv.own.owntv.core.storage.StorageAccess
import tv.own.owntv.features.settings.data.SettingsRepository
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Free/total bytes of the volume backing the download root. */
data class DownloadStorageInfo(val freeBytes: Long, val totalBytes: Long) {
    val usedBytes: Long get() = (totalBytes - freeBytes).coerceAtLeast(0L)
    val usedFraction: Float get() = if (totalBytes > 0) (usedBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
}

/**
 * Phase 12 — downloads movies & series episodes for offline playback. Files go under the user-chosen
 * download folder, organised as `Movies/<name>.<ext>` and `Series/<show>/Season N/<episode>.<ext>`.
 * Downloads run one-at-a-time on an IO scope and push byte-progress to [DownloadDao]; interrupted
 * ones restart on launch.
 */
class DownloadManager(
    private val context: Context,
    private val downloadDao: DownloadDao,
    private val client: OkHttpClient,
    private val settings: SettingsRepository,
    private val sourceDao: SourceDao,
    private val movieDao: MovieDao,
    private val seriesDao: SeriesDao,
    private val streamUrlResolver: StreamUrlResolver,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val jobs = ConcurrentHashMap<Long, Job>()

    init {
        scope.launch {
            (downloadDao.byStatus(DownloadStatus.RUNNING) + downloadDao.byStatus(DownloadStatus.QUEUED))
                .forEach { start(it.id) }
        }
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
            val id = downloadDao.upsert(
                DownloadEntity(
                    profileId = profileId, mediaType = mediaType, itemId = itemId, title = title,
                    posterUrl = posterUrl, streamUrl = streamUrl, filePath = target.absolutePath,
                    status = DownloadStatus.QUEUED,
                ),
            )
            start(id)
        }
    }

    fun retry(download: DownloadEntity) {
        scope.launch {
            // Stop a still-running attempt BEFORE deleting its file — otherwise the writer keeps
            // streaming into the unlinked file and "completes" a download that no longer exists.
            jobs.remove(download.id)?.cancelAndJoin()
            download.filePath?.let { runCatching { File(it).delete() } } // start fresh
            downloadDao.updateProgress(download.id, DownloadStatus.QUEUED, 0, download.totalBytes, System.currentTimeMillis())
            start(download.id)
        }
    }

    /** Stop the running download but keep the partial file so it can resume. */
    fun pause(download: DownloadEntity) {
        jobs.remove(download.id)?.cancel()
        scope.launch {
            val d = downloadDao.getById(download.id) ?: download
            downloadDao.updateProgress(d.id, DownloadStatus.PAUSED, d.downloadedBytes, d.totalBytes, System.currentTimeMillis())
        }
    }

    /** Continue a paused download from where it stopped (HTTP Range). */
    fun resume(download: DownloadEntity) = start(download.id)

    fun delete(download: DownloadEntity) {
        jobs.remove(download.id)?.cancel()
        scope.launch {
            download.filePath?.let { runCatching { File(it).delete() } }
            downloadDao.delete(download)
        }
    }

    private fun start(id: Long) {
        if (jobs.containsKey(id)) return
        val job = scope.launch { mutex.withLock { runDownload(id) } }
        jobs[id] = job
        job.invokeOnCompletion { jobs.remove(id) }
    }

    private suspend fun runDownload(id: Long) {
        val d = downloadDao.getById(id) ?: return
        val file = d.filePath?.let { File(it) } ?: File(StorageAccess.defaultRoot(context), "$id.mp4")
        file.parentFile?.mkdirs()
        // Resume only a previously-paused download; anything else starts fresh.
        val resuming = d.status == DownloadStatus.PAUSED && file.exists() && file.length() > 0
        if (!resuming && file.exists()) runCatching { file.delete() }
        // Attempt loop (plan D-3): a Stalker `create_link` URL dies after ~2-4 h, so a long download
        // can fail mid-stream. Each attempt re-resolves a FRESH URL from the stored cmd and resumes
        // with an HTTP Range from the bytes already written; a server that ignores Range restarts the
        // file from 0. M3U/Xtream pass through the resolver unchanged, so for them the loop is just a
        // plain transient-error retry with the same URL.
        var attempt = 0
        while (currentCoroutineContext().isActive) {
            attempt++
            val done = try {
                attemptDownload(id, d, file)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w(TAG, "download attempt $attempt/$MAX_ATTEMPTS failed id=$id: ${e.message}")
                false
            }
            if (done) return
            if (!currentCoroutineContext().isActive) return // paused/deleted — status already set by caller
            if (attempt >= MAX_ATTEMPTS) { markFailed(id, file.length(), d.totalBytes); return }
            delay(RETRY_DELAY_MS * attempt)
        }
    }

    /** One download attempt. Returns true when the file completed; false/throws = retryable failure. */
    private suspend fun attemptDownload(id: Long, d: DownloadEntity, file: File): Boolean {
        // Resolve at download-start time, fresh every attempt — the row keeps the stored cmd as the
        // item's identity; only this attempt's HTTP request sees the minted URL. A resolve failure
        // (portal down / bad auth) is a retryable attempt like any HTTP failure.
        val (url, userAgent) = resolveTarget(d)
        val existing = if (file.exists()) file.length() else 0L
        val rb = Request.Builder().url(url).header("User-Agent", userAgent)
        if (existing > 0) rb.header("Range", "bytes=$existing-")
        client.newCall(rb.build()).execute().use { resp ->
            // 416 on a resume = our Range starts at/after the end of the resource — the file already
            // holds every byte the server has (a completed download whose COMPLETED write was lost to
            // a crash/disconnect). Without this, the retry loop would mark a finished file FAILED.
            if (resp.code == 416 && existing > 0) {
                downloadDao.upsert(d.copy(status = DownloadStatus.COMPLETED, downloadedBytes = existing, totalBytes = existing, updatedAt = System.currentTimeMillis()))
                return true
            }
            val body = resp.body
            if (!resp.isSuccessful || body == null) return false
            val append = resp.code == 206 && existing > 0 // server honoured the Range
            val total = (if (append) existing else 0L) + body.contentLength().coerceAtLeast(0)
            var done = if (append) existing else 0L
            downloadDao.updateProgress(id, DownloadStatus.RUNNING, done, total, System.currentTimeMillis())
            body.byteStream().use { input ->
                java.io.FileOutputStream(file, append).use { out ->
                    val buf = ByteArray(128 * 1024)
                    var lastTick = 0L
                    while (true) {
                        if (!currentCoroutineContext().isActive) return false
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        val t = System.currentTimeMillis()
                        if (t - lastTick > 500) { downloadDao.updateProgress(id, DownloadStatus.RUNNING, done, total, t); lastTick = t }
                    }
                }
            }
            val size = file.length()
            downloadDao.upsert(d.copy(status = DownloadStatus.COMPLETED, downloadedBytes = size, totalBytes = size, updatedAt = System.currentTimeMillis()))
            return true
        }
    }

    /**
     * The URL + User-Agent this download should fetch. Non-Stalker rows return their stored
     * `streamUrl` untouched (byte-identical to the pre-D-3 behavior). Stalker rows mint a playable
     * URL via `create_link` from the stored cmd — episodes carry `series=<ep>` (the season cmd is
     * shared, looked up from the episode row) — and fetch with the source's MAG-style User-Agent.
     * If the catalog item was pruned by a re-sync mid-queue, falls back to the stored URL (a Stalker
     * cmd will then fail → FAILED, which is the honest outcome).
     */
    private suspend fun resolveTarget(d: DownloadEntity): Pair<String, String> {
        val (sourceId, episode) = when (d.mediaType) {
            MediaType.EPISODE -> {
                val ep = seriesDao.getEpisodeById(d.itemId)
                val show = ep?.let { seriesDao.getSeriesById(it.seriesId) }
                show?.sourceId to ep?.episodeNumber
            }
            else -> movieDao.getById(d.itemId)?.sourceId to null
        }
        val source = sourceId?.let { sourceDao.getById(it) }
        if (source == null || !streamUrlResolver.needsResolve(source)) {
            return d.streamUrl to HttpClient.DEFAULT_USER_AGENT
        }
        val ua = source.userAgent?.takeIf { it.isNotBlank() } ?: StalkerClient.DEFAULT_MAG_USER_AGENT
        return streamUrlResolver.resolve(source, d.streamUrl, vod = true, episode = episode) to ua
    }

    /** Keep the real partial byte count — a 90%-then-failed download showing 0 bytes is misleading,
     *  and the partial file IS still on disk (resume/retry can use it). */
    private suspend fun markFailed(id: Long, downloaded: Long, total: Long) {
        downloadDao.updateProgress(id, DownloadStatus.FAILED, downloaded.coerceAtLeast(0), total, System.currentTimeMillis())
    }

    private companion object {
        const val TAG = "DownloadManager"

        /** Attempts per runDownload — attempt 2/3 re-resolve the URL and resume via Range (D-3). */
        const val MAX_ATTEMPTS = 3
        const val RETRY_DELAY_MS = 2_000L
    }
}
