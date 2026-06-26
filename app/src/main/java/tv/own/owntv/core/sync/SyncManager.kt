package tv.own.owntv.core.sync

import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import tv.own.owntv.core.database.dao.CategoryDao
import tv.own.owntv.core.database.dao.ChannelDao
import tv.own.owntv.core.database.dao.MovieDao
import tv.own.owntv.core.database.dao.SeriesDao
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.database.entity.CategoryEntity
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.database.entity.MovieEntity
import tv.own.owntv.core.database.entity.SeriesEntity
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.model.MediaType
import tv.own.owntv.core.model.SourceType
import tv.own.owntv.core.network.HttpClient
import tv.own.owntv.core.parser.M3uParser
import tv.own.owntv.core.parser.XtCategory
import tv.own.owntv.core.parser.XtLiveStream
import tv.own.owntv.core.parser.XtSeries
import tv.own.owntv.core.parser.XtVod
import tv.own.owntv.core.parser.XtreamClient
import tv.own.owntv.core.network.withProgress
import kotlin.coroutines.CoroutineContext

/**
 * Imports a source into the database. Uses a clear-then-insert refresh per source/type (avoids the
 * @Insert REPLACE cascade pitfall), batches inserts in chunks of [CHUNK], and reports progress via a
 * non-suspend [onProgress] callback (callers push it into a StateFlow for the UI).
 *
 * Series episodes are intentionally fetched lazily later (Phase 9), not during sync.
 */
class SyncManager(
    private val context: android.content.Context,
    private val sourceDao: SourceDao,
    private val categoryDao: CategoryDao,
    private val channelDao: ChannelDao,
    private val movieDao: MovieDao,
    private val seriesDao: SeriesDao,
    private val xtream: XtreamClient,
    private val m3u: M3uParser,
    private val http: HttpClient,
) {
    private val lastSyncStats = java.util.concurrent.ConcurrentHashMap<Long, SyncRunStats>()

    fun getLastSyncStats(sourceId: Long): SyncRunStats? = lastSyncStats[sourceId]

    suspend fun sync(source: SourceEntity, onProgress: (ImportStage) -> Unit, contentTypes: SyncContentTypes = SyncContentTypes()): Pair<SyncResult, SyncRunStats> =
        withContext(Dispatchers.IO) {
            val stats = SyncStatsCollector(source.id)
            val trackedContentTypes = when (source.type) {
                SourceType.XTREAM -> contentTypes
                SourceType.M3U, SourceType.LOCAL_BACKUP -> SyncContentTypes(live = true, movies = false, series = false)
            }
            val progress = SyncProgressTracker(trackedContentTypes, onProgress)
            val result = try {
                when (source.type) {
                    SourceType.XTREAM -> syncXtream(source, progress, stats, contentTypes)
                    SourceType.M3U -> syncM3u(source, progress, stats)
                    SourceType.LOCAL_BACKUP -> Unit
                }
                if (source.type != SourceType.XTREAM || contentTypes == SyncContentTypes()) {
                    sourceDao.markSynced(source.id, System.currentTimeMillis())
                }
                progress.completeAll()
                SyncResult.Success(stats.warnings())
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                SyncResult.Failed(e.message ?: "Sync failed")
            }
            val runStats = stats.build(result)
            lastSyncStats[source.id] = runStats
            logStats(runStats)
            result to runStats
        }

    // ---------------- Xtream ----------------
    private suspend fun syncXtream(s: SourceEntity, progress: SyncProgressTracker, stats: SyncStatsCollector, contentTypes: SyncContentTypes) {
        val semaphore = Semaphore(2)
        coroutineScope {
            if (contentTypes.live) async { semaphore.withPermit { syncLive(s, progress, stats) } }
            if (contentTypes.movies) async { semaphore.withPermit { syncMovies(s, progress, stats) } }
            if (contentTypes.series) async { semaphore.withPermit { syncSeries(s, progress, stats) } }
        }
    }

    private suspend fun syncLive(s: SourceEntity, progress: SyncProgressTracker, stats: SyncStatsCollector) {
        val ctx = currentCoroutineContext()
        val liveStart = System.currentTimeMillis()
        val reportBytes = progress.bytesReporter(SyncPhase.LIVE)
        progress.start(SyncPhase.LIVE, SyncPhase.LIVE.label)
        val liveCats = xtream.liveCategories(s, reportBytes)
        val liveMap = refreshCategories(s, MediaType.LIVE, liveCats)
        channelDao.clearSource(s.id)
        var liveOrder = 0
        val toChannel = { item: XtLiveStream ->
            ChannelEntity(
                sourceId = s.id, categoryId = liveMap[item.categoryId], name = item.name,
                logoUrl = item.icon, streamUrl = xtream.liveUrl(s, item.streamId),
                epgChannelId = item.epgChannelId, number = item.num, remoteId = item.streamId,
                sortOrder = liveOrder++,
                catchup = item.archive, catchupDays = item.archiveDays,
            )
        }
        val liveTotal = intArrayOf(0)
        val liveDone = bulkOrFallback(SyncPhase.LIVE.label) {
            chunked<ChannelEntity, Boolean>(ctx, SyncPhase.LIVE, SyncPhase.LIVE.label, progress, { channelDao.upsertAll(it) }, liveTotal) { add ->
                xtream.streamLive(s, onItem = { add(toChannel(it)) }, onProgress = reportBytes)
            }
        }
        if (!liveDone) {
            stats.usedFallback = true
            sliceByCategory(ctx, SyncPhase.LIVE, SyncPhase.LIVE.label, progress, liveCats, { channelDao.upsertAll(it) }, liveTotal, liveTotal[0]) { cat, add ->
                xtream.streamLive(s, cat.id, onItem = { add(toChannel(it)) }, onProgress = reportBytes)
            }
        }
        progress.finish(SyncPhase.LIVE, SyncPhase.LIVE.label, liveTotal[0])
        stats.phaseTiming["live"] = System.currentTimeMillis() - liveStart
        stats.processedCounts["channels"] = liveTotal[0]
    }

    private suspend fun syncMovies(s: SourceEntity, progress: SyncProgressTracker, stats: SyncStatsCollector) {
        val ctx = currentCoroutineContext()
        guardStep("movies", stats) {
            val reportBytes = progress.bytesReporter(SyncPhase.MOVIES)
            progress.start(SyncPhase.MOVIES, SyncPhase.MOVIES.label)
            val vodCats = xtream.vodCategories(s, reportBytes)
            val vodMap = refreshCategories(s, MediaType.MOVIE, vodCats)
            movieDao.clearSource(s.id)
            var vodOrder = 0
            val toMovie = { item: XtVod ->
                MovieEntity(
                    sourceId = s.id, categoryId = vodMap[item.categoryId], name = item.name,
                    posterUrl = item.icon, rating = item.rating, plot = item.plot,
                    streamUrl = xtream.movieUrl(s, item.streamId, item.containerExt),
                    containerExt = item.containerExt, remoteId = item.streamId, addedAt = item.added,
                    sortOrder = vodOrder++,
                )
            }
            val vodTotal = intArrayOf(0)
            val vodDone = bulkOrFallback("Movies") {
                chunked<MovieEntity, Boolean>(ctx, SyncPhase.MOVIES, SyncPhase.MOVIES.label, progress, { movieDao.upsertAll(it) }, vodTotal) { add ->
                    xtream.streamVod(s, onItem = { add(toMovie(it)) }, onProgress = reportBytes)
                }
            }
            if (!vodDone) {
                stats.usedFallback = true
                sliceByCategory(ctx, SyncPhase.MOVIES, SyncPhase.MOVIES.label, progress, vodCats, { movieDao.upsertAll(it) }, vodTotal, vodTotal[0]) { cat, add ->
                    xtream.streamVod(s, cat.id, onItem = { add(toMovie(it)) }, onProgress = reportBytes)
                }
            }
            progress.finish(SyncPhase.MOVIES, SyncPhase.MOVIES.label, vodTotal[0])
            stats.processedCounts["movies"] = vodTotal[0]
        }
    }

    private suspend fun syncSeries(s: SourceEntity, progress: SyncProgressTracker, stats: SyncStatsCollector) {
        val ctx = currentCoroutineContext()
        guardStep("series", stats) {
            val reportBytes = progress.bytesReporter(SyncPhase.SERIES)
            progress.start(SyncPhase.SERIES, SyncPhase.SERIES.label)
            val seriesCats = xtream.seriesCategories(s, reportBytes)
            val seriesMap = refreshCategories(s, MediaType.SERIES, seriesCats)
            seriesDao.clearSource(s.id)
            var seriesOrder = 0
            val toSeries = { item: XtSeries ->
                SeriesEntity(
                    sourceId = s.id, categoryId = seriesMap[item.categoryId], name = item.name,
                    posterUrl = item.cover, plot = item.plot, rating = item.rating,
                    year = item.year, remoteId = item.seriesId,
                    sortOrder = seriesOrder++,
                )
            }
            val seriesTotal = intArrayOf(0)
            val seriesDone = bulkOrFallback("Series") {
                chunked<SeriesEntity, Boolean>(ctx, SyncPhase.SERIES, SyncPhase.SERIES.label, progress, { seriesDao.upsertSeries(it) }, seriesTotal) { add ->
                    xtream.streamSeries(s, onItem = { add(toSeries(it)) }, onProgress = reportBytes)
                }
            }
            if (!seriesDone) {
                stats.usedFallback = true
                sliceByCategory(ctx, SyncPhase.SERIES, SyncPhase.SERIES.label, progress, seriesCats, { seriesDao.upsertSeries(it) }, seriesTotal, seriesTotal[0]) { cat, add ->
                    xtream.streamSeries(s, cat.id, onItem = { add(toSeries(it)) }, onProgress = reportBytes)
                }
            }
            progress.finish(SyncPhase.SERIES, SyncPhase.SERIES.label, seriesTotal[0])
            stats.processedCounts["series"] = seriesTotal[0]
        }
    }

    private suspend inline fun guardStep(phase: String, stats: SyncStatsCollector, block: suspend () -> Unit) {
        val start = System.currentTimeMillis()
        try {
            block()
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            android.util.Log.w("SyncManager", "$phase import failed — keeping the rest of the import", e)
            stats.phaseErrors[phase] = e.message ?: "unknown"
        } finally {
            stats.phaseTiming[phase] = System.currentTimeMillis() - start
        }
    }

    /**
     * Run a bulk list fetch; if it ERRORS (not just truncates), return false so the caller drops to the
     * smaller per-category requests. Some panels (e.g. peoplestv) return a non-standard HTTP 512 on the giant
     * full `get_series` / `get_vod_streams` response but serve the per-category (`&category_id=X`) requests
     * fine — without this, the bulk error skipped straight past the per-category fallback.
     */
    private suspend inline fun bulkOrFallback(label: String, bulk: suspend () -> Boolean): Boolean =
        try {
            bulk()
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            android.util.Log.w("SyncManager", "$label bulk fetch failed (${e.message}) — falling back to per-category requests", e)
            false
        }

    /**
     * Fallback for when a provider truncates the single bulk list (issue #15): re-fetch one category at
     * a time (`&category_id=X`, tiny payloads) and upsert progressively. The table is NOT cleared first,
     * so any items the partial bulk already inserted (incl. uncategorized ones missing from the category
     * list) survive — the unique `(sourceId, remoteId)` index dedupes the overlap. [total] is shared with
     * the bulk pass so progress keeps climbing instead of resetting per category.
     *
     * Some panels IGNORE `category_id` and return the whole (truncating) list for every category — that
     * would loop forever re-fetching the same data. If a single category returns ~the bulk's whole count
     * and still truncates (or several categories can't be served), we stop and keep what we have.
     */
    private suspend fun <T> sliceByCategory(
        ctx: CoroutineContext,
        phase: SyncPhase,
        label: String,
        progress: SyncProgressTracker,
        categories: List<XtCategory>,
        insert: suspend (List<T>) -> Unit,
        total: IntArray,
        bulkPartial: Int,
        stream: suspend (cat: XtCategory, add: suspend (T) -> Unit) -> Boolean,
    ) {
        var truncations = 0
        categories.forEachIndexed { index, cat ->
            ctx.ensureActive()
            // Gentle pacing between the many small requests so we don't trip a rate-limiter (HTTP 429)
            // while looping through every category.
            if (index > 0) delay(CATEGORY_REQUEST_DELAY_MS)
            val before = total[0]
            // A single failing category (e.g. it 512s/429s) is skipped — keep importing the other categories
            // rather than losing the whole section.
            val complete = try {
                chunked<T, Boolean>(ctx, phase, label, progress, insert, total) { add -> stream(cat) { add(it) } }
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                android.util.Log.w("SyncManager", "$label: category ${cat.id} failed (${e.message}) — skipping it", e)
                return@forEachIndexed
            }
            if (!complete) {
                truncations++
                val delta = total[0] - before
                if ((bulkPartial > 0 && delta >= bulkPartial) || truncations >= 3) {
                    android.util.Log.w(
                        "SyncManager",
                        "$label: per-category fetch still truncating (panel likely ignores category_id) — stopping fallback after ${total[0]} items",
                    )
                    return // stop the fallback entirely
                }
            }
        }
    }

    private suspend fun refreshCategories(
        s: SourceEntity,
        type: MediaType,
        parsed: List<tv.own.owntv.core.parser.XtCategory>,
    ): Map<String, Long> {
        categoryDao.clear(s.id, type)
        // sortOrder = provider index, so the rail follows the provider's category order.
        val entities = parsed.mapIndexed { i, c -> CategoryEntity(sourceId = s.id, mediaType = type, name = c.name, remoteId = c.id, sortOrder = i) }
        val ids = categoryDao.upsertAll(entities)
        return parsed.mapIndexedNotNull { i, c -> ids.getOrNull(i)?.let { c.id to it } }.toMap()
    }

    // ---------------- M3U ----------------
    private suspend fun syncM3u(s: SourceEntity, progress: SyncProgressTracker, stats: SyncStatsCollector) {
        val channelsStart = System.currentTimeMillis()
        val ctx = currentCoroutineContext()
        val reportBytes = progress.bytesReporter(SyncPhase.LIVE)
        // A locally-picked playlist file (in-app StorageBrowser gives an absolute path; also tolerate
        // file://content:// URIs) is read straight from the device; a normal URL is downloaded. Same parser.
        val isLocal = s.url.startsWith("/") || s.url.startsWith("file://") || s.url.startsWith("content://")
        val localPlaylist = if (isLocal) openLocalPlaylist(s.url) else null
        progress.start(SyncPhase.LIVE, SyncPhase.LIVE.label, bytesTotal = localPlaylist?.second)

        categoryDao.clear(s.id, MediaType.LIVE)
        channelDao.clearSource(s.id)

        val groupToCategoryId = HashMap<String, Long>()
        val pendingCategoryGroups = LinkedHashSet<String>()
        val pendingCategories = ArrayList<CategoryEntity>(CHUNK)
        val buffer = ArrayList<PendingM3uChannel>(CHUNK)
        var processed = 0
        var order = 0 // playlist position — lets "Playlist order" sorting replay the file's order
        var categoryOrder = 0

        fun queueCategory(group: String) {
            if (groupToCategoryId.containsKey(group) || !pendingCategoryGroups.add(group)) return
            pendingCategories.add(
                CategoryEntity(
                    sourceId = s.id,
                    mediaType = MediaType.LIVE,
                    name = group,
                    remoteId = group,
                    sortOrder = categoryOrder++,
                ),
            )
        }

        suspend fun flushCategories() {
            if (pendingCategories.isEmpty()) return
            ctx.ensureActive()
            val groups = pendingCategoryGroups.toList()
            val categories = pendingCategories.toList()
            val ids = categoryDao.upsertAll(categories)
            groups.forEachIndexed { index, group ->
                ids.getOrNull(index)?.let { groupToCategoryId[group] = it }
            }
            pendingCategoryGroups.clear()
            pendingCategories.clear()
        }

        suspend fun flushChannels() {
            if (buffer.isEmpty()) return
            flushCategories()
            ctx.ensureActive()
            val channels = buffer.map { item ->
                val entry = item.entry
                ChannelEntity(
                    sourceId = s.id,
                    categoryId = entry.groupTitle?.let { groupToCategoryId[it] },
                    name = entry.name,
                    logoUrl = entry.logo,
                    streamUrl = entry.streamUrl,
                    epgChannelId = entry.tvgId,
                    number = entry.tvgChno,
                    remoteId = null, // M3U has no stable id; rely on clear-then-insert
                    sortOrder = item.order,
                    catchup = entry.catchup != null,
                    catchupDays = entry.catchupDays ?: 0,
                    catchupSource = entry.catchupSource,
                )
            }
            channelDao.upsertAll(channels)
            processed += channels.size
            buffer.clear()
            progress.update(SyncPhase.LIVE, SyncPhase.LIVE.label, processed)
        }

        val onEntry: suspend (tv.own.owntv.core.parser.M3uEntry) -> Unit = { e ->
            e.groupTitle?.let(::queueCategory)
            buffer.add(PendingM3uChannel(order = order++, entry = e))
            if (buffer.size >= CHUNK) {
                flushChannels()
            }
        }
        val header = if (isLocal) {
            localPlaylist!!.useProgress(reportBytes) { input ->
                m3u.parse(input, onEntry)
            }
        } else {
            http.get(s.url, s.userAgent, reportBytes) { input -> m3u.parse(input, onEntry) }
        }
        if (buffer.isNotEmpty()) {
            flushChannels()
        }

        // Persist the playlist's EPG url (url-tvg) for the EPG engine if the source didn't have one.
        if (!header.urlTvg.isNullOrBlank() && s.epgUrl.isNullOrBlank()) {
            sourceDao.update(s.copy(epgUrl = header.urlTvg))
        }
        progress.finish(SyncPhase.LIVE, SyncPhase.LIVE.label, processed)
        stats.phaseTiming["channels"] = System.currentTimeMillis() - channelsStart
        stats.processedCounts["channels"] = processed
    }

    private data class PendingM3uChannel(
        val order: Int,
        val entry: tv.own.owntv.core.parser.M3uEntry,
    )

    /**
     * Drives a push-stream [producer] that feeds items into [add]; flushes to the DB via [insert] in
     * chunks of [CHUNK], reporting progress. Inserts are awaited to provide sequential back-pressure,
     * and cancellation is checked each chunk.
     */
    private suspend fun <T, R> chunked(
        ctx: CoroutineContext,
        phase: SyncPhase,
        label: String,
        progress: SyncProgressTracker,
        insert: suspend (List<T>) -> Unit,
        total: IntArray, // shared [0] running count for the whole media type, so progress never resets
        producer: suspend (add: suspend (T) -> Unit) -> R,
    ): R {
        val buffer = ArrayList<T>(CHUNK)
        suspend fun flush() {
            if (buffer.isEmpty()) return
            ctx.ensureActive()
            insert(buffer.toList())
            total[0] += buffer.size
            buffer.clear()
            progress.update(phase, label, total[0])
        }
        val result = producer { item ->
            buffer.add(item)
            if (buffer.size >= CHUNK) flush()
        }
        flush()
        return result
    }

    private fun SyncProgressTracker.bytesReporter(phase: SyncPhase): (Long, Long?) -> Unit =
        { bytesRead, bytesTotal -> updateBytes(phase, phase.label, bytesRead, bytesTotal) }

    private suspend fun <T> Pair<InputStream, Long?>.useProgress(
        reportBytes: (Long, Long?) -> Unit,
        block: suspend (InputStream) -> T,
    ): T {
        val (input, totalBytes) = this
        reportBytes(0, totalBytes)
        val progressInput = input.withProgress(totalBytes, reportBytes)
        return try {
            block(progressInput)
        } finally {
            progressInput.close()
        }
    }

    private fun openLocalPlaylist(url: String): Pair<InputStream, Long?> = when {
        url.startsWith("/") -> {
            val file = File(url)
            file.inputStream() to file.length().takeIf { it >= 0 }
        }
        url.startsWith("file://") -> {
            val uri = Uri.parse(url)
            val file = File(uri.path ?: throw java.io.IOException("Couldn't open the playlist file. Re-pick it (it may have moved.)"))
            file.inputStream() to file.length().takeIf { it >= 0 }
        }
        url.startsWith("content://") -> {
            val uri = Uri.parse(url)
            val totalBytes = runCatching {
                context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                    afd.length.takeIf { it >= 0 }
                }
            }.getOrNull()
            val input = context.contentResolver.openInputStream(uri)
                ?: throw java.io.IOException("Couldn't open the playlist file. Re-pick it (it may have moved.)")
            input to totalBytes
        }
        else -> throw java.io.IOException("Unsupported local playlist path")
    }

    private fun logStats(stats: SyncRunStats) {
        val tag = "SyncManager"
        val duration = stats.finishedAt - stats.startedAt
        val result = when (stats.result) {
            is SyncResult.Success -> {
                if (stats.result.warnings.isEmpty()) "Success" else "Success with ${stats.result.warnings.size} warning(s)"
            }
            SyncResult.Cancelled -> "Cancelled"
            is SyncResult.Failed -> "Failed: ${stats.result.message}"
        }
        android.util.Log.i(tag, "── Sync stats for source ${stats.sourceId} ──")
        android.util.Log.i(tag, "Result: $result | Duration: ${duration}ms | Fallback: ${stats.usedFallback}")
        if (stats.phaseTiming.isNotEmpty()) {
            android.util.Log.i(tag, "Phases: ${stats.phaseTiming.entries.joinToString { "${it.key}=${it.value}ms" }}")
        }
        if (stats.processedCounts.isNotEmpty()) {
            android.util.Log.i(tag, "Counts: ${stats.processedCounts.entries.joinToString { "${it.key}=${it.value}" }}")
        }
        if (stats.phaseErrors.isNotEmpty()) {
            android.util.Log.w(tag, "Phase errors: ${stats.phaseErrors.entries.joinToString { "${it.key}=${it.value}" }}")
        }
    }

    internal class SyncStatsCollector(val sourceId: Long) {
        val startedAt = System.currentTimeMillis()
        val phaseTiming = java.util.concurrent.ConcurrentHashMap<String, Long>()
        val processedCounts = java.util.concurrent.ConcurrentHashMap<String, Int>()
        val phaseErrors = java.util.concurrent.ConcurrentHashMap<String, String>()
        @Volatile var usedFallback = false

        fun warnings() = phaseErrors.map { (phase, message) -> SyncWarning(phase, message) }

        fun build(result: SyncResult) = SyncRunStats(
            sourceId = sourceId,
            startedAt = startedAt,
            finishedAt = System.currentTimeMillis(),
            result = result,
            phaseTiming = phaseTiming.toMap(),
            processedCounts = processedCounts.toMap(),
            phaseErrors = phaseErrors.toMap(),
            usedFallback = usedFallback,
        )
    }

    companion object {
        const val CHUNK = 500
        private const val CATEGORY_REQUEST_DELAY_MS = 150L // pace per-category fallback requests (avoid HTTP 429)
    }
}
