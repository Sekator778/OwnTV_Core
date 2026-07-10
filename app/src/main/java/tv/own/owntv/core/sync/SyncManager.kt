package tv.own.owntv.core.sync

import android.net.Uri
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Deferred
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
import tv.own.owntv.core.database.entity.ContentHashProjection
import tv.own.owntv.core.database.entity.EpisodeEntity
import tv.own.owntv.core.database.entity.MovieEntity
import tv.own.owntv.core.database.entity.SeasonEntity
import tv.own.owntv.core.database.entity.SeriesEntity
import tv.own.owntv.core.database.entity.computeContentHash
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.database.BulkInsertHelper
import tv.own.owntv.core.model.MediaType
import tv.own.owntv.core.model.SourceType
import tv.own.owntv.core.network.HttpClient
import tv.own.owntv.core.parser.M3uParser
import tv.own.owntv.core.parser.XtCategory
import tv.own.owntv.core.parser.XtreamClient
import kotlin.coroutines.CoroutineContext

/**
 * Imports a source into the database. Xtream re-syncs preserve existing rows until a phase succeeds:
 * rows are matched by provider remote id, unchanged rows are skipped, changed rows keep their local id,
 * and stale rows are pruned only after the full phase completes. M3U still uses clear-then-insert
 * because playlists do not provide stable item ids.
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
    private val bulkInsertHelper: BulkInsertHelper,
) {
    private val lastSyncStats = java.util.concurrent.ConcurrentHashMap<Long, SyncRunStats>()

    fun getLastSyncStats(sourceId: Long): SyncRunStats? = lastSyncStats[sourceId]

    suspend fun sync(source: SourceEntity, onProgress: (ImportStage) -> Unit, contentTypes: SyncContentTypes = SyncContentTypes()): Pair<SyncResult, SyncRunStats> =
        withContext(Dispatchers.IO) {
            val syncStartedAt = SystemClock.elapsedRealtime()
            val stats = SyncStatsCollector(source.id)
            val trackedContentTypes = when (source.type) {
                SourceType.XTREAM -> contentTypes
                SourceType.M3U, SourceType.LOCAL_BACKUP -> SyncContentTypes(live = true, movies = false, series = false)
            }
            Log.i(
                TAG,
                "sync start sourceId=${source.id} name=${source.name} type=${source.type} " +
                    "requestedContentTypes=$contentTypes trackedContentTypes=$trackedContentTypes",
            )
            val progress = SyncCounters(trackedContentTypes, onProgress)
            val result = try {
                when (source.type) {
                    SourceType.XTREAM -> syncXtream(source, progress, stats, contentTypes)
                    SourceType.M3U -> syncM3u(source, progress, stats)
                    SourceType.LOCAL_BACKUP -> Unit
                }
                if (source.type != SourceType.XTREAM || contentTypes == SyncContentTypes()) {
                    val markStartedAt = SystemClock.elapsedRealtime()
                    sourceDao.markSynced(source.id, System.currentTimeMillis())
                    Log.d(TAG, "markSynced sourceId=${source.id} ms=${SystemClock.elapsedRealtime() - markStartedAt}")
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
            Log.i(TAG, "sync end sourceId=${source.id} totalElapsedMs=${SystemClock.elapsedRealtime() - syncStartedAt}")
            logStats(runStats)
            result to runStats
        }

    // ---------------- Xtream ----------------
    private suspend fun syncXtream(s: SourceEntity, progress: SyncCounters, stats: SyncStatsCollector, contentTypes: SyncContentTypes) {
        val semaphore = Semaphore(2)
        Log.i(TAG, "Xtream sync scheduling sourceId=${s.id} contentTypes=$contentTypes concurrency=2")
        coroutineScope {
            if (contentTypes.live) async { semaphore.withPermit { syncLive(s, progress, stats) } }
            if (contentTypes.movies) async { semaphore.withPermit { syncMovies(s, progress, stats) } }
            if (contentTypes.series) async { semaphore.withPermit { syncSeries(s, progress, stats) } }
        }
    }

    // C5: the three Xtream phases share one generic scaffolding (syncXtreamPhase) — they differ only
    // in phase/table names, the entity mapper, and per-entity DAO lambdas (ContentAdapter). Live is
    // NOT wrapped in guardStep (a live failure fails the whole sync, as before); movies/series are.

    private suspend fun syncLive(s: SourceEntity, progress: SyncCounters, stats: SyncStatsCollector) =
        syncXtreamPhase(
            s, progress, stats,
            XtreamPhase(
                phase = SyncPhase.LIVE, type = MediaType.LIVE,
                table = "channels", ftsTable = "channels_fts",
                countsKey = "channels", timingKey = "live",
                adapter = channelAdapter,
                fetchCategories = { report -> xtream.liveCategories(s, report) },
                makeStreams = { catMap ->
                    var order = 0
                    val toChannel = {
                        streamId: String,
                        name: String,
                        icon: String?,
                        epgChannelId: String?,
                        categoryId: String?,
                        num: Int?,
                        archive: Boolean,
                        archiveDays: Int ->
                        ChannelEntity(
                            sourceId = s.id, categoryId = catMap[categoryId], name = name,
                            logoUrl = icon, streamUrl = xtream.liveUrl(s, streamId),
                            epgChannelId = epgChannelId, number = num, remoteId = streamId,
                            sortOrder = order++,
                            catchup = archive, catchupDays = archiveDays,
                        )
                    }
                    XtreamStreams(
                        bulk = { add -> xtream.streamLive(s, transform = toChannel, onItem = add, onProgress = IgnoreByteProgress) },
                        byCategory = { cat, add -> xtream.streamLive(s, cat.id, transform = toChannel, onItem = add, onProgress = IgnoreByteProgress) },
                    )
                },
            ),
        )

    private suspend fun syncMovies(s: SourceEntity, progress: SyncCounters, stats: SyncStatsCollector) {
        guardStep("movies", stats) {
            syncXtreamPhase(
                s, progress, stats,
                XtreamPhase(
                    phase = SyncPhase.MOVIES, type = MediaType.MOVIE,
                    table = "movies", ftsTable = "movies_fts",
                    countsKey = "movies",
                    adapter = movieAdapter,
                    fetchCategories = { report -> xtream.vodCategories(s, report) },
                    makeStreams = { catMap ->
                        var order = 0
                        val toMovie = {
                            streamId: String,
                            name: String,
                            icon: String?,
                            rating: Double?,
                            plot: String?,
                            categoryId: String?,
                            containerExt: String?,
                            added: Long? ->
                            MovieEntity(
                                sourceId = s.id, categoryId = catMap[categoryId], name = name,
                                posterUrl = icon, rating = rating, plot = plot,
                                streamUrl = xtream.movieUrl(s, streamId, containerExt),
                                containerExt = containerExt, remoteId = streamId, addedAt = added,
                                sortOrder = order++,
                            )
                        }
                        XtreamStreams(
                            bulk = { add -> xtream.streamVod(s, transform = toMovie, onItem = add, onProgress = IgnoreByteProgress) },
                            byCategory = { cat, add -> xtream.streamVod(s, cat.id, transform = toMovie, onItem = add, onProgress = IgnoreByteProgress) },
                        )
                    },
                ),
            )
        }
    }

    private suspend fun syncSeries(s: SourceEntity, progress: SyncCounters, stats: SyncStatsCollector) {
        guardStep("series", stats) {
            syncXtreamPhase(
                s, progress, stats,
                XtreamPhase(
                    phase = SyncPhase.SERIES, type = MediaType.SERIES,
                    table = "series", ftsTable = "series_fts",
                    countsKey = "series",
                    adapter = seriesAdapter,
                    fetchCategories = { report -> xtream.seriesCategories(s, report) },
                    makeStreams = { catMap ->
                        var order = 0
                        val toSeries = {
                            seriesId: String,
                            name: String,
                            cover: String?,
                            plot: String?,
                            rating: Double?,
                            categoryId: String?,
                            year: Int? ->
                            SeriesEntity(
                                sourceId = s.id, categoryId = catMap[categoryId], name = name,
                                posterUrl = cover, plot = plot, rating = rating,
                                year = year, remoteId = seriesId,
                                sortOrder = order++,
                            )
                        }
                        XtreamStreams(
                            bulk = { add -> xtream.streamSeries(s, transform = toSeries, onItem = add, onProgress = IgnoreByteProgress) },
                            byCategory = { cat, add -> xtream.streamSeries(s, cat.id, transform = toSeries, onItem = add, onProgress = IgnoreByteProgress) },
                        )
                    },
                ),
            )
        }
    }

    /** Per-phase wiring for [syncXtreamPhase]: names/keys plus the entity mapper and streams. */
    private class XtreamPhase<T>(
        val phase: SyncPhase,
        val type: MediaType,
        val table: String,
        val ftsTable: String,
        val countsKey: String,
        /** Only Live records its own timing key; movies/series get theirs from [guardStep]. */
        val timingKey: String? = null,
        val adapter: ContentAdapter<T>,
        val fetchCategories: suspend (report: (Long, Long?) -> Unit) -> List<XtCategory>,
        /** Built AFTER the category refresh so the mapper can resolve category remote ids → db ids.
         *  The mapper's running sortOrder is shared between bulk and fallback (as before). */
        val makeStreams: (catMap: Map<String, Long>) -> XtreamStreams<T>,
    )

    private class XtreamStreams<T>(
        val bulk: suspend (add: suspend (T) -> Unit) -> Boolean,
        val byCategory: suspend (cat: XtCategory, add: suspend (T) -> Unit) -> Boolean,
    )

    /**
     * One Xtream content phase (C5 — extracted from the three near-identical copies):
     * category refresh → bulk stream (fresh insert or hash-diffed stable upsert) → per-category
     * fallback when the bulk list errors/truncates → prune (only after a COMPLETE bulk pass).
     */
    private suspend fun <T> syncXtreamPhase(
        s: SourceEntity,
        progress: SyncCounters,
        stats: SyncStatsCollector,
        p: XtreamPhase<T>,
    ) = coroutineScope {
        val ctx = currentCoroutineContext()
        val freshSource = s.lastSyncAt == null
        val phaseStart = System.currentTimeMillis()
        val elapsedStart = SystemClock.elapsedRealtime()
        val label = p.phase.label
        Log.i(TAG, "$label phase start sourceId=${s.id} fresh=$freshSource")
        progress.update(p.phase, 0)
        val hashDeferred = if (!freshSource) asyncHashLoad(label, s.id) { p.adapter.loadHashes(s.id) } else null
        val categoriesStart = SystemClock.elapsedRealtime()
        val cats = p.fetchCategories(IgnoreByteProgress)
        Log.d(TAG, "$label categories fetched sourceId=${s.id} count=${cats.size} ms=${SystemClock.elapsedRealtime() - categoriesStart}")
        val refreshStart = SystemClock.elapsedRealtime()
        val categories = refreshCategories(s, p.type, cats)
        Log.d(TAG, "$label categories refreshed sourceId=${s.id} mapped=${categories.idsByRemoteId.size} ms=${SystemClock.elapsedRealtime() - refreshStart}")
        val streams = p.makeStreams(categories.idsByRemoteId)
        val insertFn: suspend (List<T>) -> UpsertStats = if (freshSource) {
            { rows -> insertFresh(rows, p.adapter) }
        } else {
            { rows -> upsertStable(rows, hashDeferred!!, p.adapter) }
        }
        val total = intArrayOf(0)
        val remoteIds = if (freshSource) null else HashSet<String>()
        bulkInsertHelper.withOptimizedBulkInsert(
            p.table,
            p.ftsTable,
            eligible = freshSource,
            ftsOnly = true,
        ) {
            val bulkStart = SystemClock.elapsedRealtime()
            Log.i(TAG, "$label bulk start sourceId=${s.id}")
            val chunkSize = if (freshSource) BulkInsertHelper.CHUNK_FRESH else BulkInsertHelper.CHUNK
            val done = bulkOrFallback(label) {
                chunked<T, Boolean>(ctx, p.phase, label, progress, insertFn, total, remoteIds, p.adapter.remoteIdOf, chunkSize) { add ->
                    streams.bulk(add)
                }
            }
            Log.i(TAG, "$label bulk end sourceId=${s.id} complete=$done unique=${total[0]} ms=${SystemClock.elapsedRealtime() - bulkStart}")
            if (!done) {
                stats.usedFallback = true
                val fallbackStart = SystemClock.elapsedRealtime()
                Log.i(TAG, "$label fallback start sourceId=${s.id} categories=${cats.size} bulkPartial=${total[0]}")
                sliceByCategory(ctx, p.phase, label, progress, cats, insertFn, total, total[0], remoteIds, p.adapter.remoteIdOf) { cat, add ->
                    streams.byCategory(cat, add)
                }
                Log.i(TAG, "$label fallback end sourceId=${s.id} unique=${total[0]} ms=${SystemClock.elapsedRealtime() - fallbackStart}")
            }
            if (!freshSource && done) {
                pruneRemoteIds(label, s.id, remoteIds!!, p.adapter.remoteIdsForSource, p.adapter.deleteByRemoteIds)
                pruneCategories(s.id, p.type, categories.seenRemoteIds, label)
            } else if (!freshSource) {
                Log.i(TAG, "$label prune skipped sourceId=${s.id} reason=incomplete_bulk")
            }
        }
        progress.update(p.phase, total[0])
        p.timingKey?.let { stats.phaseTiming[it] = System.currentTimeMillis() - phaseStart }
        stats.processedCounts[p.countsKey] = total[0]
        Log.i(TAG, "$label phase end sourceId=${s.id} unique=${total[0]} ms=${SystemClock.elapsedRealtime() - elapsedStart}")
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
        progress: SyncCounters,
        categories: List<XtCategory>,
        insert: suspend (List<T>) -> UpsertStats,
        total: IntArray,
        bulkPartial: Int,
        seenKeys: MutableSet<String>? = null,
        uniqueKey: ((T) -> String?)? = null,
        stream: suspend (cat: XtCategory, add: suspend (T) -> Unit) -> Boolean,
    ) {
        var truncations = 0
        Log.i(TAG, "$label fallback categories begin count=${categories.size} bulkPartial=$bulkPartial currentUnique=${total[0]}")
        categories.forEachIndexed { index, cat ->
            ctx.ensureActive()
            // Gentle pacing between the many small requests so we don't trip a rate-limiter (HTTP 429)
            // while looping through every category.
            if (index > 0) delay(CATEGORY_REQUEST_DELAY_MS)
            val categoryStart = SystemClock.elapsedRealtime()
            val before = total[0]
            Log.d(TAG, "$label fallback category start index=${index + 1}/${categories.size} id=${cat.id} name=${cat.name} beforeUnique=$before")
            // A single failing category (e.g. it 512s/429s) is skipped — keep importing the other categories
            // rather than losing the whole section.
            val complete = try {
                chunked<T, Boolean>(ctx, phase, label, progress, insert, total, seenKeys, uniqueKey) { add -> stream(cat) { add(it) } }
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                // HTTP errors (like 512 "response too large") are specific to one category — skip it and
                // try the next. Network errors (timeout, DNS, connection refused) mean the server is
                // unreachable — abort the entire fallback so we don't spin for minutes retrying every
                // category against a dead server.
                val isServerError = e.message?.startsWith("HTTP") == true
                android.util.Log.w("SyncManager", "$label: category ${cat.id} failed (${e.message}) — ${if (isServerError) "skipping category" else "ABORTING fallback"}", e)
                if (isServerError) return@forEachIndexed
                else return
            }
            val delta = total[0] - before
            Log.d(
                TAG,
                "$label fallback category end index=${index + 1}/${categories.size} id=${cat.id} " +
                    "complete=$complete newUnique=$delta totalUnique=${total[0]} ms=${SystemClock.elapsedRealtime() - categoryStart}",
            )
            if (!complete) {
                truncations++
                if ((bulkPartial > 0 && delta >= bulkPartial) || truncations >= 3) {
                    android.util.Log.w(
                        "SyncManager",
                        "$label: per-category fetch still truncating (panel likely ignores category_id) — stopping fallback after ${total[0]} items",
                    )
                    return // stop the fallback entirely
                }
            }
        }
        Log.i(TAG, "$label fallback categories end totalUnique=${total[0]} truncations=$truncations")
    }

    private suspend fun refreshCategories(
        s: SourceEntity,
        type: MediaType,
        parsed: List<tv.own.owntv.core.parser.XtCategory>,
    ): CategoryRefresh {
        val start = SystemClock.elapsedRealtime()
        Log.d(TAG, "refreshCategories start sourceId=${s.id} type=$type count=${parsed.size}")
        val uniqueCategories = parsed.distinctBy { it.id }
        val existing = existingCategoriesByRemoteId(s.id, type, uniqueCategories.map { it.id })
        // sortOrder = provider index, so the rail follows the provider's category order.
        val entities = uniqueCategories.mapIndexed { i, c ->
            CategoryEntity(
                id = existing[c.id]?.id ?: 0,
                sourceId = s.id,
                mediaType = type,
                name = c.name,
                remoteId = c.id,
                sortOrder = i,
            )
        }
        val upsertStart = SystemClock.elapsedRealtime()
        val upsert = upsertCategoriesStable(s.id, type, entities, existing)
        Log.d(
            TAG,
            "refreshCategories upsert sourceId=${s.id} type=$type rows=${entities.size} " +
                "dbInserted=${upsert.stats.inserted} dbUpdated=${upsert.stats.updated} " +
                "dbSkipped=${upsert.stats.skippedUnchanged} ms=${SystemClock.elapsedRealtime() - upsertStart}",
        )
        // C5: ids come straight from the upsert (existing rows + returned insert rowids) — the old
        // second existingCategoriesByRemoteId round-trip only re-fetched just-upserted rows.
        return CategoryRefresh(idsByRemoteId = upsert.idsByRemoteId, seenRemoteIds = uniqueCategories.mapTo(HashSet()) { it.id }).also {
            Log.d(TAG, "refreshCategories end sourceId=${s.id} type=$type mapped=${it.idsByRemoteId.size} totalMs=${SystemClock.elapsedRealtime() - start}")
        }
    }

    private data class CategoryRefresh(
        val idsByRemoteId: Map<String, Long>,
        val seenRemoteIds: Set<String>,
    )

    private data class UpsertStats(
        val inserted: Int = 0,
        val updated: Int = 0,
        val skippedUnchanged: Int = 0,
    )

    private suspend fun existingCategoriesByRemoteId(sourceId: Long, type: MediaType, remoteIds: List<String>): Map<String, CategoryEntity> =
        remoteIds.distinct().chunked(QUERY_CHUNK).flatMap { categoryDao.findByRemoteIds(sourceId, type, it) }
            .mapNotNull { category -> category.remoteId?.let { it to category } }
            .toMap()

    private class CategoryUpsert(val stats: UpsertStats, val idsByRemoteId: Map<String, Long>)

    private suspend fun upsertCategoriesStable(
        sourceId: Long,
        type: MediaType,
        rows: List<CategoryEntity>,
        existingByRemoteId: Map<String, CategoryEntity>,
    ): CategoryUpsert {
        val inserts = ArrayList<CategoryEntity>()
        val updates = ArrayList<CategoryEntity>()
        var skipped = 0
        val ids = HashMap<String, Long>()
        rows.forEach { row ->
            val current = row.remoteId?.let(existingByRemoteId::get)
            when {
                current == null -> inserts.add(row)
                row != current -> { updates.add(row); row.remoteId?.let { ids[it] = current.id } }
                else -> { skipped++; row.remoteId?.let { ids[it] = current.id } }
            }
        }
        if (updates.isNotEmpty()) categoryDao.updateAll(updates)
        if (inserts.isNotEmpty()) {
            val rowIds = categoryDao.insertAll(inserts)
            val missed = ArrayList<String>()
            inserts.forEachIndexed { i, row ->
                val rid = row.remoteId ?: return@forEachIndexed
                val id = rowIds.getOrNull(i) ?: -1L
                if (id > 0) ids[rid] = id else missed.add(rid)
            }
            // IGNOREd conflicts return −1 (shouldn't happen — inserts were pre-checked by remoteId);
            // heal by re-fetching just those rows rather than everything.
            if (missed.isNotEmpty()) {
                existingCategoriesByRemoteId(sourceId, type, missed).forEach { (rid, cat) -> ids[rid] = cat.id }
            }
        }
        return CategoryUpsert(
            stats = UpsertStats(inserted = inserts.size, updated = updates.size, skippedUnchanged = skipped),
            idsByRemoteId = ids,
        )
    }

    /**
     * Per-entity DAO/mapping lambdas so ONE [upsertStable]/[insertFresh]/prune implementation serves
     * channels, movies and series (C5) — any fix to the hash-diff/prune logic now lands once.
     */
    private class ContentAdapter<T>(
        val remoteIdOf: (T) -> String?,
        val hashOf: (T) -> Int,
        /** Copy with contentHash set; a non-null [id] rekeys the row to the existing local row. */
        val copyWith: (row: T, id: Long?, hash: Int) -> T,
        val updateAll: suspend (List<T>) -> Unit,
        val insertAll: suspend (List<T>) -> Unit,
        val remoteIdsForSource: suspend (Long) -> List<String>,
        val deleteByRemoteIds: suspend (Long, List<String>) -> Unit,
        val loadHashes: suspend (Long) -> List<ContentHashProjection>,
    )

    private val channelAdapter = ContentAdapter<ChannelEntity>(
        remoteIdOf = { it.remoteId },
        hashOf = { it.computeContentHash() },
        copyWith = { row, id, hash -> if (id != null) row.copy(id = id, contentHash = hash) else row.copy(contentHash = hash) },
        updateAll = { channelDao.updateAll(it) },
        insertAll = { channelDao.insertAll(it) },
        remoteIdsForSource = { channelDao.remoteIdsForSource(it) },
        deleteByRemoteIds = { src, ids -> channelDao.deleteByRemoteIds(src, ids) },
        loadHashes = { channelDao.contentHashesForSource(it) },
    )

    private val movieAdapter = ContentAdapter<MovieEntity>(
        remoteIdOf = { it.remoteId },
        hashOf = { it.computeContentHash() },
        copyWith = { row, id, hash -> if (id != null) row.copy(id = id, contentHash = hash) else row.copy(contentHash = hash) },
        updateAll = { movieDao.updateAll(it) },
        insertAll = { movieDao.insertAll(it) },
        remoteIdsForSource = { movieDao.remoteIdsForSource(it) },
        deleteByRemoteIds = { src, ids -> movieDao.deleteByRemoteIds(src, ids) },
        loadHashes = { movieDao.contentHashesForSource(it) },
    )

    private val seriesAdapter = ContentAdapter<SeriesEntity>(
        remoteIdOf = { it.remoteId },
        hashOf = { it.computeContentHash() },
        copyWith = { row, id, hash -> if (id != null) row.copy(id = id, contentHash = hash) else row.copy(contentHash = hash) },
        updateAll = { seriesDao.updateSeries(it) },
        insertAll = { seriesDao.insertSeries(it) },
        remoteIdsForSource = { seriesDao.remoteIdsForSource(it) },
        deleteByRemoteIds = { src, ids -> seriesDao.deleteByRemoteIds(src, ids) },
        loadHashes = { seriesDao.contentHashesForSource(it) },
    )

    /** Hash-diffed stable upsert: unchanged rows are skipped, changed rows keep their local id. */
    private suspend fun <T> upsertStable(
        rows: List<T>,
        hashDeferred: Deferred<Map<String, Pair<Long, Int>>>,
        adapter: ContentAdapter<T>,
    ): UpsertStats {
        val hashMap = hashDeferred.await()
        val inserts = ArrayList<T>()
        val updates = ArrayList<T>()
        var skipped = 0
        rows.forEach { row ->
            val existing = adapter.remoteIdOf(row)?.let { hashMap[it] }
            val hash = adapter.hashOf(row)
            when {
                existing == null -> inserts.add(adapter.copyWith(row, null, hash))
                hash != existing.second -> updates.add(adapter.copyWith(row, existing.first, hash))
                else -> skipped++
            }
        }
        if (updates.isNotEmpty()) adapter.updateAll(updates)
        if (inserts.isNotEmpty()) adapter.insertAll(inserts)
        return UpsertStats(inserted = inserts.size, updated = updates.size, skippedUnchanged = skipped)
    }

    /** First-ever import: no diffing, just hash + insert. */
    private suspend fun <T> insertFresh(rows: List<T>, adapter: ContentAdapter<T>): UpsertStats {
        val hashed = rows.map { adapter.copyWith(it, null, adapter.hashOf(it)) }
        adapter.insertAll(hashed)
        return UpsertStats(inserted = hashed.size)
    }

    private fun List<ContentHashProjection>.toHashLookup(): Map<String, Pair<Long, Int>> =
        associateBy({ it.remoteId }, { it.id to it.contentHash })

    private fun CoroutineScope.asyncHashLoad(
        label: String,
        sourceId: Long,
        load: suspend () -> List<ContentHashProjection>,
    ): Deferred<Map<String, Pair<Long, Int>>> = async {
        val start = SystemClock.elapsedRealtime()
        load().toHashLookup().also {
            Log.d(TAG, "$label hash map loaded sourceId=$sourceId size=${it.size} ms=${SystemClock.elapsedRealtime() - start}")
        }
    }

    private suspend fun pruneCategories(sourceId: Long, type: MediaType, seenRemoteIds: Set<String>, label: String) {
        val start = SystemClock.elapsedRealtime()
        val stale = categoryDao.remoteIdsForSource(sourceId, type).filterNot(seenRemoteIds::contains)
        stale.chunked(QUERY_CHUNK).forEach { categoryDao.deleteByRemoteIds(sourceId, type, it) }
        Log.i(TAG, "$label category prune sourceId=$sourceId type=$type stale=${stale.size} ms=${SystemClock.elapsedRealtime() - start}")
    }

    private suspend fun pruneRemoteIds(
        label: String,
        sourceId: Long,
        seenRemoteIds: Set<String>,
        loadExisting: suspend (Long) -> List<String>,
        deleteRemoteIds: suspend (Long, List<String>) -> Unit,
    ) {
        val start = SystemClock.elapsedRealtime()
        val stale = loadExisting(sourceId).filterNot(seenRemoteIds::contains)
        stale.chunked(QUERY_CHUNK).forEach { deleteRemoteIds(sourceId, it) }
        Log.i(TAG, "$label content prune sourceId=$sourceId stale=${stale.size} ms=${SystemClock.elapsedRealtime() - start}")
    }

    // ---------------- M3U ----------------
    private suspend fun syncM3u(s: SourceEntity, progress: SyncCounters, stats: SyncStatsCollector) {
        val channelsStart = System.currentTimeMillis()
        val elapsedStart = SystemClock.elapsedRealtime()
        val ctx = currentCoroutineContext()
        val freshSource = s.lastSyncAt == null
        val chunkSize = if (freshSource) BulkInsertHelper.CHUNK_FRESH else BulkInsertHelper.CHUNK
        val reportBytes = IgnoreByteProgress
        // A locally-picked playlist file (in-app StorageBrowser gives an absolute path; also tolerate
        // file://content:// URIs) is read straight from the device; a normal URL is downloaded. Same parser.
        val isLocal = s.url.startsWith("/") || s.url.startsWith("file://") || s.url.startsWith("content://")
        val localPlaylist = if (isLocal) openLocalPlaylist(s.url) else null
        Log.i(TAG, "M3U phase start sourceId=${s.id} local=$isLocal bytesTotal=${localPlaylist?.second ?: -1}")
        progress.update(SyncPhase.LIVE, 0)

        var processed = 0
        var moviesProcessed = 0
        var seriesProcessed = 0
        val header = bulkInsertHelper.withOptimizedBulkInsert(
            "channels",
            "channels_fts",
            eligible = freshSource,
            ftsOnly = true,
        ) {
            // Deferred per-type clears — only wipe a type's old data once the first real row of that
            // type is about to be written, so a failed download never leaves the source empty and a
            // live-only playlist never touches previously-imported VOD rows (and vice versa).
            var channelsCleared = false
            var moviesCleared = false
            suspend fun ensureChannelsCleared() {
                if (channelsCleared) return
                channelsCleared = true
                val start = SystemClock.elapsedRealtime()
                channelDao.clearSource(s.id)
                categoryDao.clear(s.id, MediaType.LIVE)
                Log.d(TAG, "M3U clear channels+categories sourceId=${s.id} ms=${SystemClock.elapsedRealtime() - start}")
            }
            suspend fun ensureMoviesCleared() {
                if (moviesCleared) return
                moviesCleared = true
                val start = SystemClock.elapsedRealtime()
                movieDao.clearSource(s.id)
                categoryDao.clear(s.id, MediaType.MOVIE)
                Log.d(TAG, "M3U clear movies+categories sourceId=${s.id} ms=${SystemClock.elapsedRealtime() - start}")
            }
            var seriesCleared = false
            suspend fun ensureSeriesCleared() {
                if (seriesCleared) return
                seriesCleared = true
                val start = SystemClock.elapsedRealtime()
                seriesDao.clearSource(s.id) // seasons/episodes cascade
                categoryDao.clear(s.id, MediaType.SERIES)
                Log.d(TAG, "M3U clear series+categories sourceId=${s.id} ms=${SystemClock.elapsedRealtime() - start}")
            }

            // Categories are per-mediaType: the same group-title can exist for both live and VOD.
            val groupToCategoryId = HashMap<Pair<MediaType, String>, Long>()
            val pendingCategoryKeys = LinkedHashSet<Pair<MediaType, String>>()
            val pendingCategories = ArrayList<CategoryEntity>(chunkSize)
            val buffer = ArrayList<PendingM3uChannel>(chunkSize)
            val movieBuffer = ArrayList<PendingM3uChannel>(chunkSize)
            var order = 0 // playlist position — lets "Playlist order" sorting replay the file's order
            var categoryOrder = 0

            fun queueCategory(type: MediaType, group: String) {
                val key = type to group
                if (groupToCategoryId.containsKey(key) || !pendingCategoryKeys.add(key)) return
                pendingCategories.add(
                    CategoryEntity(
                        sourceId = s.id,
                        mediaType = type,
                        name = group,
                        remoteId = group,
                        sortOrder = categoryOrder++,
                    ),
                )
            }

            suspend fun flushCategories() {
                if (pendingCategories.isEmpty()) return
                ctx.ensureActive()
                // A type's deferred clear MUST run before that type's categories are inserted — a
                // later ensure*Cleared() would delete just-written category rows and leave content
                // pointing at dead ids (FOREIGN KEY constraint failed on the content insert).
                if (pendingCategories.any { it.mediaType == MediaType.LIVE }) ensureChannelsCleared()
                if (pendingCategories.any { it.mediaType == MediaType.MOVIE }) ensureMoviesCleared()
                if (pendingCategories.any { it.mediaType == MediaType.SERIES }) ensureSeriesCleared()
                val keys = pendingCategoryKeys.toList()
                val categories = pendingCategories.toList()
                val start = SystemClock.elapsedRealtime()
                val ids = categoryDao.upsertAll(categories)
                keys.forEachIndexed { index, key ->
                    ids.getOrNull(index)?.let { groupToCategoryId[key] = it }
                }
                Log.d(TAG, "M3U categories flush sourceId=${s.id} rows=${categories.size} mapped=${keys.size} ms=${SystemClock.elapsedRealtime() - start}")
                pendingCategoryKeys.clear()
                pendingCategories.clear()
            }

            suspend fun flushChannels() {
                if (buffer.isEmpty()) return
                ensureChannelsCleared()
                flushCategories()
                ctx.ensureActive()
                val channels = buffer.map { item ->
                    val entry = item.entry
                    ChannelEntity(
                        sourceId = s.id,
                        categoryId = entry.groupTitle?.let { groupToCategoryId[MediaType.LIVE to it] },
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
                val start = SystemClock.elapsedRealtime()
                channelDao.upsertAll(channels)
                processed += channels.size
                Log.d(TAG, "M3U channel flush sourceId=${s.id} rows=${channels.size} processed=$processed ms=${SystemClock.elapsedRealtime() - start}")
                buffer.clear()
                progress.update(SyncPhase.LIVE, processed)
            }

            suspend fun flushMovies() {
                if (movieBuffer.isEmpty()) return
                ensureMoviesCleared()
                flushCategories()
                ctx.ensureActive()
                val movies = movieBuffer.map { item ->
                    val entry = item.entry
                    MovieEntity(
                        sourceId = s.id,
                        categoryId = entry.groupTitle?.let { groupToCategoryId[MediaType.MOVIE to it] },
                        name = entry.name,
                        posterUrl = entry.logo,
                        streamUrl = entry.streamUrl,
                        remoteId = null, // M3U has no stable id; rely on clear-then-insert
                        sortOrder = item.order,
                    )
                }
                val start = SystemClock.elapsedRealtime()
                movieDao.upsertAll(movies)
                moviesProcessed += movies.size
                Log.d(TAG, "M3U movie flush sourceId=${s.id} rows=${movies.size} processed=$moviesProcessed ms=${SystemClock.elapsedRealtime() - start}")
                movieBuffer.clear()
                progress.update(SyncPhase.MOVIES, moviesProcessed)
            }

            // Series-tagged entries are per-EPISODE lines ("Show S01E05"); they're grouped by show
            // name into series → seasons → episodes and written once at the end of the parse (series
            // playlists are small — hundreds to a few thousand lines — so buffering them is cheap).
            val seriesAccumulator = LinkedHashMap<String, M3uShowAccumulator>()

            suspend fun flushSeries() {
                if (seriesAccumulator.isEmpty()) return
                ensureSeriesCleared()
                flushCategories()
                ctx.ensureActive()
                val start = SystemClock.elapsedRealtime()
                val shows = seriesAccumulator.values.toList()
                val seriesIds = seriesDao.upsertSeriesReturnIds(
                    shows.map { show ->
                        SeriesEntity(
                            sourceId = s.id,
                            categoryId = show.group?.let { groupToCategoryId[MediaType.SERIES to it] },
                            name = show.name,
                            posterUrl = show.logo,
                            remoteId = null, // M3U has no stable id; rely on clear-then-insert
                            sortOrder = show.order,
                        )
                    },
                )
                var episodesWritten = 0
                shows.forEachIndexed { index, show ->
                    val seriesId = seriesIds.getOrNull(index) ?: return@forEachIndexed
                    val seasonNumbers = show.episodes.map { it.season }.distinct().sorted()
                    val seasonIds = seriesDao.upsertSeasonsReturnIds(
                        seasonNumbers.map { n -> SeasonEntity(seriesId = seriesId, seasonNumber = n, name = "Season $n") },
                    )
                    val seasonIdByNumber = seasonNumbers.zip(seasonIds).toMap()
                    seriesDao.upsertEpisodes(
                        show.episodes.map { ep ->
                            EpisodeEntity(
                                seriesId = seriesId,
                                seasonId = seasonIdByNumber[ep.season],
                                seasonNumber = ep.season,
                                episodeNumber = ep.episode,
                                name = ep.title,
                                streamUrl = ep.streamUrl,
                            )
                        },
                    )
                    episodesWritten += show.episodes.size
                }
                seriesProcessed = shows.size
                Log.d(TAG, "M3U series flush sourceId=${s.id} shows=${shows.size} episodes=$episodesWritten ms=${SystemClock.elapsedRealtime() - start}")
                seriesAccumulator.clear()
                progress.update(SyncPhase.SERIES, seriesProcessed)
            }

            val onEntry: suspend (tv.own.owntv.core.parser.M3uEntry) -> Unit = { e ->
                when {
                    // type="series" / tvg-type="series" → grouped into the Series tab.
                    e.isSeries -> {
                        e.groupTitle?.let { queueCategory(MediaType.SERIES, it) }
                        val parsed = parseM3uEpisode(e.name)
                        val show = seriesAccumulator.getOrPut(parsed.show.lowercase()) {
                            M3uShowAccumulator(name = parsed.show, logo = e.logo, group = e.groupTitle, order = order++)
                        }
                        val episode = if (parsed.episode > 0) parsed.episode else show.episodes.count { it.season == parsed.season } + 1
                        show.episodes.add(
                            M3uEpisodeRow(
                                season = parsed.season,
                                episode = episode,
                                title = parsed.title ?: "Episode $episode",
                                streamUrl = e.streamUrl,
                            ),
                        )
                    }
                    // Other VOD tags (type="vod"/"movie", tvg-type="vod"/"movie") → the movie grid.
                    e.isVod -> {
                        e.groupTitle?.let { queueCategory(MediaType.MOVIE, it) }
                        movieBuffer.add(PendingM3uChannel(order = order++, entry = e))
                        if (movieBuffer.size >= chunkSize) {
                            flushMovies()
                        }
                    }
                    else -> {
                        e.groupTitle?.let { queueCategory(MediaType.LIVE, it) }
                        buffer.add(PendingM3uChannel(order = order++, entry = e))
                        if (buffer.size >= chunkSize) {
                            flushChannels()
                        }
                    }
                }
            }
            val header = if (isLocal) {
                localPlaylist!!.first.use { input -> m3u.parse(input, onEntry) }
            } else {
                http.get(s.url, s.userAgent, reportBytes) { input -> m3u.parse(input, onEntry) }
            }
            if (buffer.isNotEmpty()) {
                flushChannels()
            }
            if (movieBuffer.isNotEmpty()) {
                flushMovies()
            }
            flushSeries()
            header
        }
        // Persist the playlist's EPG url (url-tvg) for the EPG engine if the source didn't have one.
        if (!header.urlTvg.isNullOrBlank() && s.epgUrl.isNullOrBlank()) {
            sourceDao.update(s.copy(epgUrl = header.urlTvg))
        }
        progress.update(SyncPhase.LIVE, processed)
        if (moviesProcessed > 0) progress.update(SyncPhase.MOVIES, moviesProcessed)
        if (seriesProcessed > 0) progress.update(SyncPhase.SERIES, seriesProcessed)
        stats.phaseTiming["channels"] = System.currentTimeMillis() - channelsStart
        stats.processedCounts["channels"] = processed
        if (moviesProcessed > 0) stats.processedCounts["movies"] = moviesProcessed
        if (seriesProcessed > 0) stats.processedCounts["series"] = seriesProcessed
        Log.i(TAG, "M3U phase end sourceId=${s.id} processed=$processed movies=$moviesProcessed series=$seriesProcessed ms=${SystemClock.elapsedRealtime() - elapsedStart}")
    }

    private data class PendingM3uChannel(
        val order: Int,
        val entry: tv.own.owntv.core.parser.M3uEntry,
    )

    /** One M3U series-tagged show being accumulated during a playlist parse. */
    private class M3uShowAccumulator(
        val name: String,
        val logo: String?,
        val group: String?,
        val order: Int,
    ) {
        val episodes = ArrayList<M3uEpisodeRow>()
    }

    private data class M3uEpisodeRow(val season: Int, val episode: Int, val title: String, val streamUrl: String)

    private data class ParsedM3uEpisode(val show: String, val season: Int, val episode: Int, val title: String?)

    /**
     * Splits an M3U series entry title like "Stranger Things S01E05" / "Show 2x03 - Pilot" into
     * show + season + episode (+ optional episode title). Entries without a recognizable pattern
     * become season 1 with sequential episode numbers ("Tales From The Crypt (1989-90s)").
     */
    private fun parseM3uEpisode(rawName: String): ParsedM3uEpisode {
        val name = rawName.trim()
        M3U_EPISODE_SXXEYY.find(name)?.let { m ->
            val show = name.substring(0, m.range.first).trim(' ', '-', '.', '_', ':')
            val title = name.substring(m.range.last + 1).trim(' ', '-', '.', '_', ':').takeIf { it.isNotEmpty() }
            if (show.isNotEmpty()) {
                return ParsedM3uEpisode(show, m.groupValues[1].toInt(), m.groupValues[2].toInt(), title)
            }
        }
        M3U_EPISODE_NXN.find(name)?.let { m ->
            val show = name.substring(0, m.range.first).trim(' ', '-', '.', '_', ':')
            val title = name.substring(m.range.last + 1).trim(' ', '-', '.', '_', ':').takeIf { it.isNotEmpty() }
            if (show.isNotEmpty()) {
                return ParsedM3uEpisode(show, m.groupValues[1].toInt(), m.groupValues[2].toInt(), title)
            }
        }
        return ParsedM3uEpisode(show = name, season = 1, episode = 0, title = null) // episode 0 → sequential
    }

    /**
     * Drives a push-stream [producer] that feeds items into [add]; flushes to the DB via [insert] in
     * chunks of [BulkInsertHelper.CHUNK], reporting progress. Inserts are awaited to provide sequential back-pressure,
     * and cancellation is checked each chunk.
     */
    private suspend fun <T, R> chunked(
        ctx: CoroutineContext,
        phase: SyncPhase,
        label: String,
        progress: SyncCounters,
        insert: suspend (List<T>) -> UpsertStats,
        total: IntArray, // shared [0] running unique count for the whole media type, so progress never resets
        seenKeys: MutableSet<String>? = null,
        uniqueKey: ((T) -> String?)? = null,
        chunkSize: Int = BulkInsertHelper.CHUNK,
        producer: suspend (add: suspend (T) -> Unit) -> R,
    ): R {
        val buffer = ArrayList<T>(chunkSize)
        var chunkIndex = 0
        var skippedDuplicates = 0
        val chunkRunStart = SystemClock.elapsedRealtime()
        suspend fun flush() {
            if (buffer.isEmpty()) return
            ctx.ensureActive()
            chunkIndex++
            val rawCount = buffer.size
            val flushStart = SystemClock.elapsedRealtime()
            val pendingKeys = ArrayList<String>()
            val rows = buffer.toList().filterNewItems(seenKeys, uniqueKey, pendingKeys)
            val filterMs = SystemClock.elapsedRealtime() - flushStart
            buffer.clear()
            val skipped = rawCount - rows.size
            skippedDuplicates += skipped
            if (rows.isEmpty()) {
                Log.d(
                    TAG,
                    "$label chunk skipped phase=${phase.label} chunk=$chunkIndex raw=$rawCount skipped=$skipped " +
                        "totalSkipped=$skippedDuplicates totalUnique=${total[0]} filterMs=$filterMs elapsedMs=${SystemClock.elapsedRealtime() - chunkRunStart}",
                )
                return
            }
            val insertStart = SystemClock.elapsedRealtime()
            val upsertStats = insert(rows)
            val insertMs = SystemClock.elapsedRealtime() - insertStart
            seenKeys?.addAll(pendingKeys)
            total[0] += rows.size
            if (shouldLogChunk(chunkIndex, insertMs, skipped)) {
                Log.d(
                    TAG,
                    "$label chunk applied phase=${phase.label} chunk=$chunkIndex raw=$rawCount accepted=${rows.size} " +
                        "dbInserted=${upsertStats.inserted} dbUpdated=${upsertStats.updated} dbSkipped=${upsertStats.skippedUnchanged} " +
                        "dedupeSkipped=$skipped totalDedupeSkipped=$skippedDuplicates totalUnique=${total[0]} " +
                        "filterMs=$filterMs applyMs=$insertMs elapsedMs=${SystemClock.elapsedRealtime() - chunkRunStart}",
                )
            }
            progress.update(phase, total[0])
        }
        val result = producer { item ->
            buffer.add(item)
            if (buffer.size >= chunkSize) flush()
        }
        flush()
        Log.i(
            TAG,
            "$label stream done phase=${phase.label} chunks=$chunkIndex totalUnique=${total[0]} " +
                "skippedDuplicates=$skippedDuplicates elapsedMs=${SystemClock.elapsedRealtime() - chunkRunStart}",
        )
        return result
    }

    private fun shouldLogChunk(chunkIndex: Int, insertMs: Long, skipped: Int): Boolean =
        chunkIndex <= 3 || chunkIndex % 20 == 0 || insertMs >= SLOW_INSERT_LOG_MS || skipped > 0

    private fun <T> List<T>.filterNewItems(
        seenKeys: MutableSet<String>?,
        uniqueKey: ((T) -> String?)?,
        pendingKeys: MutableList<String>,
    ): List<T> {
        if (seenKeys == null || uniqueKey == null) return this
        val rows = ArrayList<T>(size)
        val batchKeys = HashSet<String>()
        forEach { item ->
            val key = uniqueKey(item)
            if (key == null) {
                rows.add(item)
            } else if (!seenKeys.contains(key) && batchKeys.add(key)) {
                pendingKeys.add(key)
                rows.add(item)
            }
        }
        return rows
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

    private class SyncCounters(
        contentTypes: SyncContentTypes,
        private val onProgress: (ImportStage) -> Unit,
    ) {
        private val lock = Any()
        private val liveActive = contentTypes.live
        private val moviesActive = contentTypes.movies
        private val seriesActive = contentTypes.series
        private var liveProcessed = 0
        private var moviesProcessed = 0
        private var seriesProcessed = 0

        fun update(phase: SyncPhase, count: Int): ImportStage {
            val snapshot = synchronized(lock) {
                when (phase) {
                    SyncPhase.LIVE -> liveProcessed = count
                    SyncPhase.MOVIES -> moviesProcessed = count
                    SyncPhase.SERIES -> seriesProcessed = count
                }
                snapshotLocked()
            }
            onProgress(snapshot)
            return snapshot
        }

        fun completeAll(): ImportStage {
            val snapshot = synchronized(lock) { snapshotLocked() }
            onProgress(snapshot)
            return snapshot
        }

        private fun snapshotLocked() = ImportStage(
            liveProcessed = liveProcessed,
            moviesProcessed = moviesProcessed,
            seriesProcessed = seriesProcessed,
            liveActive = liveActive,
            moviesActive = moviesActive,
            seriesActive = seriesActive,
        )
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
        private const val TAG = "SyncManager"

        /** "S01E05" / "s1 e5" — the common episode marker in M3U series playlists. */
        private val M3U_EPISODE_SXXEYY = Regex("""(?i)\bS(\d{1,2})\s*[.\-_ ]?\s*E(\d{1,3})\b""")

        /** "1x05" alternative marker. */
        private val M3U_EPISODE_NXN = Regex("""(?i)\b(\d{1,2})x(\d{1,3})\b""")
        private const val QUERY_CHUNK = 500
        private const val CATEGORY_REQUEST_DELAY_MS = 150L // pace per-category fallback requests (avoid HTTP 429)
        private const val SLOW_INSERT_LOG_MS = 250L
        private val IgnoreByteProgress: (Long, Long?) -> Unit = { _, _ -> }
    }
}
