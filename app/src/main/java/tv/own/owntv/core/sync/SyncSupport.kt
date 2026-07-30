package tv.own.owntv.core.sync

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import tv.own.owntv.core.customize.CustomizationStore
import tv.own.owntv.core.customize.CustomizeKeys
import tv.own.owntv.core.database.BulkInsertHelper
import tv.own.owntv.core.database.dao.CategoryDao
import tv.own.owntv.core.database.dao.ChannelDao
import tv.own.owntv.core.database.dao.MovieDao
import tv.own.owntv.core.database.dao.SeriesDao
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.database.entity.CategoryEntity
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.database.entity.ContentHashProjection
import tv.own.owntv.core.database.entity.MovieEntity
import tv.own.owntv.core.database.entity.SeriesEntity
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.database.entity.computeContentHash
import tv.own.owntv.core.model.MediaType
import tv.own.owntv.features.settings.data.SettingsRepository
import kotlin.coroutines.CoroutineContext

/**
 * Shared building blocks for the per-source-type syncers ([XtreamSyncer], [M3uSyncer], later
 * StalkerSyncer): the chunked streaming inserter with cross-pass dedupe, the hash-diffed stable
 * upsert / fresh insert pair, category refresh + stable upsert, and stale-row pruning. Extracted
 * from SyncManager so each syncer stays focused on its own protocol flow while any fix to the
 * shared machinery lands once.
 */
internal class SyncSupport(
    private val categoryDao: CategoryDao,
    channelDao: ChannelDao,
    movieDao: MovieDao,
    seriesDao: SeriesDao,
    val sourceDao: SourceDao,
    private val customize: CustomizationStore,
    private val settings: SettingsRepository,
) {
    val channelAdapter = ContentAdapter<ChannelEntity>(
        remoteIdOf = { it.remoteId },
        hashOf = { it.computeContentHash() },
        sortOrderOf = { it.sortOrder },
        copyWith = { row, id, hash -> if (id != null) row.copy(id = id, contentHash = hash) else row.copy(contentHash = hash) },
        updateAll = { channelDao.updateAll(it) },
        insertAll = { channelDao.insertAll(it) },
        remoteIdsForSource = { channelDao.remoteIdsForSource(it) },
        deleteByRemoteIds = { src, ids -> channelDao.deleteByRemoteIds(src, ids) },
        loadHashes = { channelDao.contentHashesForSource(it) },
        remoteIdsInCategories = { src, cats -> channelDao.remoteIdsInCategories(src, cats) },
    )

    val movieAdapter = ContentAdapter<MovieEntity>(
        remoteIdOf = { it.remoteId },
        hashOf = { it.computeContentHash() },
        sortOrderOf = { it.sortOrder },
        copyWith = { row, id, hash -> if (id != null) row.copy(id = id, contentHash = hash) else row.copy(contentHash = hash) },
        updateAll = { movieDao.updateAll(it) },
        insertAll = { movieDao.insertAll(it) },
        remoteIdsForSource = { movieDao.remoteIdsForSource(it) },
        deleteByRemoteIds = { src, ids -> movieDao.deleteByRemoteIds(src, ids) },
        loadHashes = { movieDao.contentHashesForSource(it) },
        countsByCategory = { src -> movieDao.countsByCategoryOnce(src).associate { it.categoryId to it.itemCount } },
        remoteIdsForCategory = { src, cat -> movieDao.remoteIdsForCategory(src, cat) },
        remoteIdsInCategories = { src, cats -> movieDao.remoteIdsInCategories(src, cats) },
    )

    val seriesAdapter = ContentAdapter<SeriesEntity>(
        remoteIdOf = { it.remoteId },
        hashOf = { it.computeContentHash() },
        sortOrderOf = { it.sortOrder },
        copyWith = { row, id, hash -> if (id != null) row.copy(id = id, contentHash = hash) else row.copy(contentHash = hash) },
        updateAll = { seriesDao.updateSeries(it) },
        insertAll = { seriesDao.insertSeries(it) },
        remoteIdsForSource = { seriesDao.remoteIdsForSource(it) },
        deleteByRemoteIds = { src, ids -> seriesDao.deleteByRemoteIds(src, ids) },
        loadHashes = { seriesDao.contentHashesForSource(it) },
        countsByCategory = { src -> seriesDao.countsByCategoryOnce(src).associate { it.categoryId to it.itemCount } },
        remoteIdsForCategory = { src, cat -> seriesDao.remoteIdsForCategory(src, cat) },
        remoteIdsInCategories = { src, cats -> seriesDao.remoteIdsInCategories(src, cats) },
    )

    /**
     * Hash-diffed stable upsert: unchanged rows are skipped, changed rows keep their local id.
     *
     * There are three outcomes, not two (S1). `sortOrder` is deliberately not part of
     * `computeContentHash()` — folding it in would change every stored hash at once, so the first
     * resync after such a change rewrites a 170k-row catalog end to end. Instead the stored
     * `sortOrder` travels alongside the hash and is compared separately: a row whose content is
     * identical but whose provider position moved is written (so browse order follows the provider)
     * and counted as `moved`, while a row that matches on both is still skipped entirely. Manual
     * reorder is unaffected — it lives in `content_order` and is applied over the top of this.
     */
    suspend fun <T> upsertStable(
        rows: List<T>,
        hashDeferred: Deferred<Map<String, StoredRow>>,
        adapter: ContentAdapter<T>,
    ): UpsertStats = upsertStable(rows, hashDeferred.await(), adapter)

    /** First-ever import: no diffing, just hash + insert. */
    suspend fun <T> insertFresh(rows: List<T>, adapter: ContentAdapter<T>): UpsertStats {
        val hashed = rows.map { adapter.copyWith(it, null, adapter.hashOf(it)) }
        adapter.insertAll(hashed)
        return UpsertStats(inserted = hashed.size)
    }

    private fun List<ContentHashProjection>.toHashLookup(): Map<String, StoredRow> =
        associateBy({ it.remoteId }, { StoredRow(it.id, it.contentHash, it.sortOrder) })

    fun asyncHashLoad(
        scope: CoroutineScope,
        label: String,
        sourceId: Long,
        load: suspend () -> List<ContentHashProjection>,
    ): Deferred<Map<String, StoredRow>> = scope.async {
        val start = SystemClock.elapsedRealtime()
        load().toHashLookup().also {
            Log.d(TAG, "$label hash map loaded sourceId=$sourceId size=${it.size} ms=${SystemClock.elapsedRealtime() - start}")
        }
    }

    /**
     * Catalog-shrink guard. A pass that *looks* complete but saw only a fraction of the catalog (a
     * provider serving a truncated list, a panel briefly answering with a tiny payload) would
     * otherwise delete most of the user's content — and with it every favorite, history entry and
     * resume position keyed to those rows. So when a source already holds a meaningful number of
     * rows and this pass would remove more than half of them, the prune is skipped and the run
     * reports a warning instead. A fresh/small source (≤ [PRUNE_MIN_ROWS]) prunes normally, and a
     * user-requested force-clean sync ([SyncStatsCollector.forcePrune]) bypasses the guard entirely
     * for the case where the catalog really did shrink.
     *
     * Returns true when the prune may proceed.
     */
    private fun pruneAllowed(label: String, sourceId: Long, stored: Int, stale: Int, stats: SyncStatsCollector): Boolean {
        if (shouldPrune(stored, stale, stats.forcePrune)) return true
        val percent = (stale * 100) / stored
        Log.w(TAG, "$label prune skipped sourceId=$sourceId reason=catalog_shrink stored=$stored stale=$stale")
        stats.addWarning(
            SyncWarning(
                phase = label,
                kind = SyncWarningKind.CATALOG_SHRINK(stored = stored, percentFewer = percent),
            ),
        )
        return false
    }

    suspend fun pruneCategories(sourceId: Long, type: MediaType, seenRemoteIds: Set<String>, label: String, stats: SyncStatsCollector) {
        val start = SystemClock.elapsedRealtime()
        val existing = categoryDao.remoteIdsForSource(sourceId, type)
        val stale = existing.filterNot(seenRemoteIds::contains)
        if (!pruneAllowed("$label categories", sourceId, existing.size, stale.size, stats)) return
        stale.chunked(QUERY_CHUNK).forEach { categoryDao.deleteByRemoteIds(sourceId, type, it) }
        if (stale.isNotEmpty()) stats.processedCounts.merge(CATEGORIES_REMOVED_KEY, stale.size, Int::plus)
        Log.i(TAG, "$label category prune sourceId=$sourceId type=$type stale=${stale.size} ms=${SystemClock.elapsedRealtime() - start}")
    }

    suspend fun pruneRemoteIds(
        label: String,
        sourceId: Long,
        seenRemoteIds: Set<String>,
        stats: SyncStatsCollector,
        loadExisting: suspend (Long) -> List<String>,
        deleteRemoteIds: suspend (Long, List<String>) -> Unit,
    ) {
        val start = SystemClock.elapsedRealtime()
        val existing = loadExisting(sourceId)
        val stale = existing.filterNot(seenRemoteIds::contains)
        if (!pruneAllowed(label, sourceId, existing.size, stale.size, stats)) return
        stale.chunked(QUERY_CHUNK).forEach { deleteRemoteIds(sourceId, it) }
        Log.i(TAG, "$label content prune sourceId=$sourceId stale=${stale.size} ms=${SystemClock.elapsedRealtime() - start}")
    }

    suspend fun refreshCategories(
        s: SourceEntity,
        type: MediaType,
        parsed: List<tv.own.owntv.core.parser.XtCategory>,
        stats: SyncStatsCollector,
    ): CategoryRefresh {
        val start = SystemClock.elapsedRealtime()
        // s.lastSyncAt never changes during a sync run (SyncManager stamps it only after the syncer
        // returns), so this is safe to derive here rather than threading a freshSource param through
        // every call site.
        val freshSource = s.lastSyncAt == null
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
        // Everything is technically "new" on a fresh source's first sync — neither the hide-by-default
        // preference nor the sync summary is meaningful there, only on a genuine resync.
        if (!freshSource && upsert.newRows.isNotEmpty()) {
            stats.processedCounts.merge(CATEGORIES_ADDED_KEY, upsert.newRows.size, Int::plus)
            applyHideNewCategoriesDefault(s.id, type, upsert.newRows)
        }
        // C5: ids come straight from the upsert (existing rows + returned insert rowids) — the old
        // second existingCategoriesByRemoteId round-trip only re-fetched just-upserted rows.
        return CategoryRefresh(idsByRemoteId = upsert.idsByRemoteId, seenRemoteIds = uniqueCategories.mapTo(HashSet()) { it.id }).also {
            Log.d(TAG, "refreshCategories end sourceId=${s.id} type=$type mapped=${it.idsByRemoteId.size} totalMs=${SystemClock.elapsedRealtime() - start}")
        }
    }

    private suspend fun existingCategoriesByRemoteId(sourceId: Long, type: MediaType, remoteIds: List<String>): Map<String, CategoryEntity> =
        remoteIds.distinct().chunked(QUERY_CHUNK).flatMap { categoryDao.findByRemoteIds(sourceId, type, it) }
            .mapNotNull { category -> category.remoteId?.let { it to category } }
            .toMap()

    private class CategoryUpsert(val stats: UpsertStats, val idsByRemoteId: Map<String, Long>, val newRows: List<CategoryEntity>)

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
                row != current -> { updates.add(row); ids[row.remoteId] = current.id }
                else -> { skipped++; ids[row.remoteId] = current.id }
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
            newRows = inserts,
        )
    }

    /** Applies each profile's own "hide new categories" preference (same across Live/Movies/Series) to
     *  categories just discovered on a resync — a source can be shared by several profiles.
     *  Non-private: M3uSyncer discovers categories incrementally during the stream, so it applies
     *  this itself at end-of-parse instead of going through [refreshCategories]. */
    suspend fun applyHideNewCategoriesDefault(sourceId: Long, type: MediaType, newRows: List<CategoryEntity>) {
        val profileIds = sourceDao.profileIdsForSource(sourceId)
        if (profileIds.isEmpty()) return
        val keys = newRows.map { CustomizeKeys.category(it) }
        profileIds.forEach { profileId ->
            if (settings.hideNewCategoriesDefault(profileId).first()) {
                customize.setCategoriesHidden(profileId, type, keys, hidden = true)
            }
        }
    }

    /**
     * Drives a push-stream [producer] that feeds items into [add]; flushes to the DB via [insert] in
     * chunks of [BulkInsertHelper.CHUNK], reporting progress. Inserts are awaited to provide sequential back-pressure,
     * and cancellation is checked each chunk.
     */
    suspend fun <T, R> chunked(
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
                    "$label chunk skipped phase=${phase.name} chunk=$chunkIndex raw=$rawCount skipped=$skipped " +
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
                    "$label chunk applied phase=${phase.name} chunk=$chunkIndex raw=$rawCount accepted=${rows.size} " +
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
            "$label stream done phase=${phase.name} chunks=$chunkIndex totalUnique=${total[0]} " +
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

    companion object {
        /** Shared log tag — kept as "SyncManager" across the split so existing logcat filters still work. */
        const val TAG = "SyncManager"
        const val QUERY_CHUNK = 500

        /** Below this many stored rows a prune is always allowed (fresh/small sources). */
        private const val PRUNE_MIN_ROWS = 100

        /** A prune may remove at most this fraction of the stored rows before the guard trips. */
        private const val PRUNE_MAX_SHRINK = 0.5

        /**
         * The catalog-shrink decision on its own, free of DAOs and logging so it can be unit tested:
         * true when deleting [stale] of [stored] rows is a plausible provider update rather than a
         * truncated response.
         */
        /**
         * The hash-diff itself, free of DAOs and logging so it can be unit tested. See the instance
         * overload for why `sortOrder` is compared here rather than folded into the content hash.
         */
        suspend fun <T> upsertStable(
            rows: List<T>,
            stored: Map<String, StoredRow>,
            adapter: ContentAdapter<T>,
        ): UpsertStats {
            val inserts = ArrayList<T>()
            val updates = ArrayList<T>()
            var skipped = 0
            var moved = 0
            rows.forEach { row ->
                val existing = adapter.remoteIdOf(row)?.let { stored[it] }
                val hash = adapter.hashOf(row)
                when {
                    existing == null -> inserts.add(adapter.copyWith(row, null, hash))
                    hash != existing.contentHash -> updates.add(adapter.copyWith(row, existing.id, hash))
                    adapter.sortOrderOf(row) != existing.sortOrder -> {
                        moved++
                        updates.add(adapter.copyWith(row, existing.id, hash))
                    }
                    else -> skipped++
                }
            }
            if (updates.isNotEmpty()) adapter.updateAll(updates)
            if (inserts.isNotEmpty()) adapter.insertAll(inserts)
            return UpsertStats(inserted = inserts.size, updated = updates.size, skippedUnchanged = skipped, moved = moved)
        }

        fun shouldPrune(stored: Int, stale: Int, force: Boolean): Boolean =
            force || stored <= PRUNE_MIN_ROWS || stale <= stored * PRUNE_MAX_SHRINK
        const val CATEGORY_REQUEST_DELAY_MS = 150L // pace per-category fallback requests (avoid HTTP 429)
        private const val SLOW_INSERT_LOG_MS = 250L
        val IgnoreByteProgress: (Long, Long?) -> Unit = { _, _ -> }

        /** [SyncStatsCollector.processedCounts] keys aggregating category changes across all phases,
         *  for the sync-complete summary — not shown at all when a fresh source makes every category "new". */
        const val CATEGORIES_ADDED_KEY = "categoriesAdded"
        const val CATEGORIES_REMOVED_KEY = "categoriesRemoved"
    }
}

/**
 * Per-entity DAO/mapping lambdas so ONE [SyncSupport.upsertStable]/[SyncSupport.insertFresh]/prune
 * implementation serves channels, movies and series (C5) — any fix to the hash-diff/prune logic now
 * lands once.
 */
internal class ContentAdapter<T>(
    val remoteIdOf: (T) -> String?,
    val hashOf: (T) -> Int,
    /** Provider position, compared against the stored one outside the hash — see [SyncSupport.upsertStable]. */
    val sortOrderOf: (T) -> Int,
    /** Copy with contentHash set; a non-null [id] rekeys the row to the existing local row. */
    val copyWith: (row: T, id: Long?, hash: Int) -> T,
    val updateAll: suspend (List<T>) -> Unit,
    val insertAll: suspend (List<T>) -> Unit,
    val remoteIdsForSource: suspend (Long) -> List<String>,
    val deleteByRemoteIds: suspend (Long, List<String>) -> Unit,
    val loadHashes: suspend (Long) -> List<ContentHashProjection>,
    /** Re-sync delta check (paged catalogs): current per-category item counts; null = not supported. */
    val countsByCategory: (suspend (sourceId: Long) -> Map<Long, Int>)? = null,
    /** RemoteIds of one category's existing rows — protects a delta-skipped category from pruning. */
    val remoteIdsForCategory: (suspend (sourceId: Long, categoryId: Long) -> List<String>)? = null,
    /** RemoteIds across a set of categories — the prune scope after a per-category fallback (S2). */
    val remoteIdsInCategories: (suspend (sourceId: Long, categoryIds: List<Long>) -> List<String>)? = null,
)

/** What the DB already holds for one remote id: local row id, content hash, and provider position. */
internal data class StoredRow(val id: Long, val contentHash: Int, val sortOrder: Int)

internal data class UpsertStats(
    val inserted: Int = 0,
    val updated: Int = 0,
    val skippedUnchanged: Int = 0,
    /** Subset of [updated] written only because the provider moved the row (content identical). */
    val moved: Int = 0,
)

/**
 * Result of an Xtream per-category fallback pass (S2). [succeededCategoryRemoteIds] are the
 * categories whose list was fetched *completely* — a truncated, failed, skipped or never-reached
 * category is absent, so its rows are left alone. Top-level (not nested in XtreamSyncer) so
 * [pruneScope] can be unit tested.
 */
internal class FallbackOutcome(
    val succeededCategoryRemoteIds: List<String>,
    val aborted: Boolean,
    val stoppedEarly: Boolean,
    val attempted: Int,
) {
    val complete: Boolean get() = !aborted && !stoppedEarly && succeededCategoryRemoteIds.size == attempted

    /**
     * Local category ids a prune may touch: only the fully-fetched categories, mapped through the
     * refresh's remoteId→id table. Empty means prune nothing. Rows with no category are never in
     * scope by construction — a per-category request can't return them, so a source-wide prune here
     * would delete every uncategorized item.
     */
    fun pruneScope(idsByRemoteId: Map<String, Long>): List<Long> =
        succeededCategoryRemoteIds.mapNotNull(idsByRemoteId::get).distinct()
}

internal data class CategoryRefresh(
    val idsByRemoteId: Map<String, Long>,
    val seenRemoteIds: Set<String>,
)

internal class SyncCounters(
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
    private val warningFacts = java.util.concurrent.CopyOnWriteArrayList<SyncWarning>()
    @Volatile var usedFallback = false

    /**
     * Set by a user-requested "force clean sync": bypasses the catalog-shrink prune guard so a
     * genuinely shrunken provider catalog can be trimmed down. Off for every automatic sync.
     */
    @Volatile var forcePrune = false

    fun addWarning(warning: SyncWarning) { warningFacts += warning }

    fun warnings(): List<SyncWarning> = warningFacts.toList() + phaseErrors.map { (phase, message) -> SyncWarning(phase, message) }

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
