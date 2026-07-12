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
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.model.SourceType
import tv.own.owntv.core.network.HttpClient
import tv.own.owntv.core.parser.M3uParser
import tv.own.owntv.core.parser.XtreamClient

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
) {
    private val support = SyncSupport(categoryDao, channelDao, movieDao, seriesDao)
    private val xtreamSyncer = XtreamSyncer(xtream, bulkInsertHelper, support)
    private val m3uSyncer = M3uSyncer(context, sourceDao, categoryDao, channelDao, movieDao, seriesDao, m3u, http, bulkInsertHelper)

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
                    SourceType.XTREAM -> xtreamSyncer.sync(source, progress, stats, contentTypes)
                    SourceType.M3U -> m3uSyncer.sync(source, progress, stats)
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
