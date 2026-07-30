package tv.own.owntv.core.sync

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tv.own.owntv.core.database.BulkInsertHelper
import tv.own.owntv.core.database.dao.CategoryDao
import tv.own.owntv.core.database.dao.ChannelDao
import tv.own.owntv.core.database.dao.MovieDao
import tv.own.owntv.core.database.dao.SeriesDao
import tv.own.owntv.core.customize.CustomizationStore
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.model.SourceType
import tv.own.owntv.core.network.HttpClient
import tv.own.owntv.core.parser.M3uParser
import tv.own.owntv.core.parser.XtreamClient
import tv.own.owntv.features.settings.data.SettingsRepository

/**
 * Imports a source into the database — a thin dispatcher over the per-source-type syncers
 * (Phase 0 of the Stalker plan split this file): [XtreamSyncer] preserves existing rows via
 * hash-diffed stable upserts; [M3uSyncer] uses clear-then-insert because playlists do not provide
 * stable item ids. Shared machinery (chunked inserts, upserts, pruning) lives in [SyncSupport].
 *
 * Series episodes are intentionally fetched lazily later (Phase 9), not during sync.
 */
class SyncManager(
    context: android.content.Context,
    private val sourceDao: SourceDao,
    categoryDao: CategoryDao,
    channelDao: ChannelDao,
    movieDao: MovieDao,
    seriesDao: SeriesDao,
    xtream: XtreamClient,
    m3u: M3uParser,
    http: HttpClient,
    bulkInsertHelper: BulkInsertHelper,
    stalkerClient: tv.own.owntv.core.stalker.StalkerClient,
    stalkerAuth: tv.own.owntv.core.stalker.StalkerAuthManager,
    private val activityTracker: SyncActivityTracker,
    customize: CustomizationStore,
    settings: SettingsRepository,
) {
    private val support = SyncSupport(categoryDao, channelDao, movieDao, seriesDao, sourceDao, customize, settings)
    private val xtreamSyncer = XtreamSyncer(xtream, bulkInsertHelper, support)
    private val m3uSyncer = M3uSyncer(context, sourceDao, categoryDao, channelDao, movieDao, seriesDao, m3u, http, bulkInsertHelper, support)
    private val stalkerSyncer = StalkerSyncer(stalkerClient, stalkerAuth, bulkInsertHelper, support, sourceDao)

    private val lastSyncStats = java.util.concurrent.ConcurrentHashMap<Long, SyncRunStats>()

    fun getLastSyncStats(sourceId: Long): SyncRunStats? = lastSyncStats[sourceId]

    suspend fun sync(
        source: SourceEntity,
        onProgress: (ImportStage) -> Unit,
        contentTypes: SyncContentTypes = SyncContentTypes(),
        /**
         * User-requested clean resync. Scoped to this one run — it is never persisted and never set
         * by an automatic sync, so the catalog-shrink guard protects every other pass as before.
         */
        forcePrune: Boolean = false,
    ): Pair<SyncResult, SyncRunStats> =
        withContext(Dispatchers.IO) {
            val syncStartedAt = SystemClock.elapsedRealtime()
            val stats = SyncStatsCollector(source.id).apply { this.forcePrune = forcePrune }
            // Single derivation: request ∩ enabledScope, then type-constrained (replaces the old
            // trackedContentTypes source-type switch). A stale enqueue can't revive an Off section.
            val effective = contentTypes.effectiveFor(source)
            val target = SyncContentTypes.enabledFor(source)
            Log.i(
                TAG,
                "sync start sourceId=${source.id} name=${source.name} type=${source.type} " +
                    "requestedContentTypes=$contentTypes effective=$effective target=$target forcePrune=$forcePrune",
            )
            activityTracker.started(source.id, source.name)
            val progress = SyncCounters(effective) { stage ->
                activityTracker.progress(source.id, stage)
                onProgress(stage)
            }
            var result: SyncResult = SyncResult.Cancelled
            try {
                if (!effective.hasAny) {
                    // Empty effective (e.g. Movies-later remainder after Movies turned Off): clean
                    // no-op — do not stamp lastSyncAt (scope edit enqueues a reconcile resync).
                    Log.i(TAG, "sync no-op empty effective sourceId=${source.id}")
                    progress.completeAll()
                    result = SyncResult.Success()
                } else {
                    // Concurrent catalog syncs are safe without an app-wide lock: the only cross-source
                    // race was BulkInsertHelper's pre-lock tableIsEmpty bypass (a second source writing
                    // into a half-indexed table while the first restored it). That is now closed inside
                    // BulkInsertHelper itself — a joining sync registers as a writer and index restore
                    // waits for the last writer — so sources fetch/parse/insert fully in parallel here.
                    when (source.type) {
                        SourceType.XTREAM -> xtreamSyncer.sync(source, progress, stats, effective)
                        SourceType.M3U -> m3uSyncer.sync(source, progress, stats)
                        SourceType.LOCAL_BACKUP -> Unit
                        SourceType.STALKER -> stalkerSyncer.sync(source, progress, stats, effective)
                    }
                    // Stamp lastSyncAt only when this pass covered every enabled+constrained section.
                    // A staged partial pass leaves it null; the background remainder worker stamps via
                    // completesInitialSync. Comparing against enabledFor (not enabledOf) keeps M3U
                    // live-only passes from never marking synced.
                    if (effective.isCompleteFor(target)) {
                        val markStartedAt = SystemClock.elapsedRealtime()
                        sourceDao.markSynced(source.id, System.currentTimeMillis())
                        Log.d(TAG, "markSynced sourceId=${source.id} ms=${SystemClock.elapsedRealtime() - markStartedAt}")
                    }
                    progress.completeAll()
                    result = SyncResult.Success(
                        warnings = stats.warnings(),
                        categoriesAdded = stats.processedCounts[SyncSupport.CATEGORIES_ADDED_KEY] ?: 0,
                        categoriesRemoved = stats.processedCounts[SyncSupport.CATEGORIES_REMOVED_KEY] ?: 0,
                    )
                }
            } catch (c: CancellationException) {
                result = SyncResult.Cancelled
                throw c
            } catch (e: Exception) {
                result = SyncResult.Failed(e.message.orEmpty())
            } finally {
                activityTracker.finished(source.id, source.name, result) // also on cancellation — never leave a stuck pill
            }
            val runStats = stats.build(result)
            lastSyncStats[source.id] = runStats
            Log.i(TAG, "sync end sourceId=${source.id} totalElapsedMs=${SystemClock.elapsedRealtime() - syncStartedAt}")
            logStats(runStats)
            result to runStats
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

    companion object {
        private const val TAG = SyncSupport.TAG
    }
}
