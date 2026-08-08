package tv.own.owntv.core.trending

import android.os.SystemClock
import android.util.Log
import java.util.UUID
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import tv.own.owntv.core.database.dao.MovieDao
import tv.own.owntv.core.database.dao.SeriesDao
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.database.dao.TrendingDao
import tv.own.owntv.core.database.entity.TrendingAttemptStatus
import tv.own.owntv.core.database.entity.TrendingItemEntity
import tv.own.owntv.core.database.entity.TrendingSnapshotEntity
import tv.own.owntv.core.database.entity.TrendingSnapshotStatus
import tv.own.owntv.core.metadata.MetadataProvider
import tv.own.owntv.core.metadata.MetadataType
import tv.own.owntv.core.metadata.MovieDetails
import tv.own.owntv.core.model.MediaType
import tv.own.owntv.core.repository.SeriesRepository
import tv.own.owntv.core.sync.SyncContentTypes
import tv.own.owntv.features.settings.data.SettingsRepository
import tv.own.owntv.features.home.HomeRow

class TrendingRepository(
    private val sourceDao: SourceDao,
    private val movieDao: MovieDao,
    private val seriesDao: SeriesDao,
    private val trendingDao: TrendingDao,
    private val metadataProvider: MetadataProvider,
    private val settings: SettingsRepository,
    private val seriesRepository: SeriesRepository,
) {
    suspend fun recordUnexpectedFailure(sourceId: Long): TrendingRefreshOutcome.PreservedFailure =
        preserveFailure(
            sourceId = sourceId,
            stage = "unexpected error",
            language = settings.metadataConfig().resolvedLanguage,
            startedAt = SystemClock.elapsedRealtime(),
        )

    suspend fun refresh(
        sourceId: Long,
        onProgress: (TrendingRefreshProgress) -> Unit = {},
    ): TrendingRefreshOutcome {
        val totalStarted = SystemClock.elapsedRealtime()
        val config = settings.metadataConfig()
        if (!config.enabled) return TrendingRefreshOutcome.SkippedProviderMode
        val source = sourceDao.getById(sourceId) ?: return TrendingRefreshOutcome.SourceMissing
        val trendingVisible = sourceDao.profileIdsForSource(sourceId).any { profileId ->
            HomeRow.TRENDING !in settings.homeConfig(profileId).first().hidden
        }
        if (!trendingVisible) return TrendingRefreshOutcome.SkippedHidden
        val enabled = SyncContentTypes.enabledFor(source)
        if (!enabled.movies && !enabled.series) {
            val completedAt = System.currentTimeMillis()
            trendingDao.writeBelowThreshold(
                TrendingSnapshotEntity(
                    sourceId = sourceId,
                    status = TrendingSnapshotStatus.BELOW_THRESHOLD,
                    metadataLanguage = config.resolvedLanguage,
                    refreshedAt = completedAt,
                    candidateFetchedAt = 0,
                    generationId = UUID.randomUUID().toString(),
                    itemCount = 0,
                    matchedItemCount = 0,
                    lastAttemptAt = completedAt,
                    lastAttemptStatus = TrendingAttemptStatus.BELOW_THRESHOLD,
                    failureStage = "no VOD content",
                ),
            )
            return TrendingRefreshOutcome.NoVodScope
        }

        onProgress(TrendingRefreshProgress.Fetching)
        val fetchStarted = SystemClock.elapsedRealtime()
        val (movieCandidates, seriesCandidates) = coroutineScope {
            val movies = async { if (enabled.movies) metadataProvider.trendingMovies() else emptyList() }
            val series = async { if (enabled.series) metadataProvider.trendingTv() else emptyList() }
            movies.await() to series.await()
        }
        val fetchMs = SystemClock.elapsedRealtime() - fetchStarted
        if (movieCandidates == null) return preserveFailure(sourceId, "movie candidates", config.resolvedLanguage, totalStarted)
        if (seriesCandidates == null) return preserveFailure(sourceId, "TV candidates", config.resolvedLanguage, totalStarted)
        onProgress(TrendingRefreshProgress.CandidatesReceived(movieCandidates.size, seriesCandidates.size))
        val candidateFetchedAt = System.currentTimeMillis()

        val movieBackfillTotal = if (enabled.movies) movieDao.trendingMetadataBackfillCount(sourceId) else 0
        val seriesBackfillTotal = if (enabled.series) seriesDao.trendingMetadataBackfillCount(sourceId) else 0
        val backfillTotal = movieBackfillTotal + seriesBackfillTotal
        var backfillProcessed = 0
        onProgress(TrendingRefreshProgress.PreparingCatalog(0, backfillTotal))
        val preparationStarted = SystemClock.elapsedRealtime()
        val backfilledMovies = if (enabled.movies) backfillMovies(sourceId) { processed ->
            backfillProcessed = processed
            onProgress(TrendingRefreshProgress.PreparingCatalog(backfillProcessed, backfillTotal))
        } else 0
        val backfilledSeries = if (enabled.series) backfillSeries(sourceId) { processed ->
            onProgress(TrendingRefreshProgress.PreparingCatalog(backfillProcessed + processed, backfillTotal))
        } else 0
        val preparationMs = SystemClock.elapsedRealtime() - preparationStarted
        if (backfilledMovies + backfilledSeries > 0) {
            Log.i(TAG, "sourceId=$sourceId provider metadata backfilled movies=$backfilledMovies series=$backfilledSeries ms=$preparationMs")
        }

        onProgress(TrendingRefreshProgress.MatchingMovies(0, 0, movieCandidates.size, TrendingMatcher.MAX_PER_MEDIA_TYPE))
        var movieMatchCount = 0
        var seriesMatchCount = 0
        val movieResult = if (enabled.movies) {
            TrendingMatcher.matchMedia(
                candidates = movieCandidates,
                mediaType = MediaType.MOVIE,
                preferredLanguage = config.resolvedLanguage,
                limit = TrendingMatcher.MAX_PER_MEDIA_TYPE,
                exactLookup = { movieDao.trendingExact(sourceId, it) },
                ftsLookup = { query, limit -> movieDao.trendingFts(sourceId, query, limit) },
            ) { checked, match ->
                if (match != null) movieMatchCount++
                onProgress(
                    TrendingRefreshProgress.MatchingMovies(
                        checked = checked,
                        matched = movieMatchCount,
                        candidates = movieCandidates.size,
                        target = TrendingMatcher.MAX_PER_MEDIA_TYPE,
                    ),
                )
            }
        } else TrendingMatchResult(emptyList(), 0, 0, 0)

        val seriesTarget = if (movieResult.selections.size >= BALANCED_TARGET) {
            BALANCED_TARGET
        } else {
            TrendingMatcher.MAX_TOTAL - movieResult.selections.size
        }
        onProgress(TrendingRefreshProgress.MatchingSeries(0, 0, seriesCandidates.size, seriesTarget))
        val seriesResult = if (enabled.series && seriesTarget > 0) {
            TrendingMatcher.matchMedia(
                candidates = seriesCandidates,
                mediaType = MediaType.SERIES,
                preferredLanguage = config.resolvedLanguage,
                limit = seriesTarget,
                exactLookup = { seriesDao.trendingExact(sourceId, it) },
                ftsLookup = { query, limit -> seriesDao.trendingFts(sourceId, query, limit) },
            ) { checked, match ->
                if (match != null) seriesMatchCount++
                onProgress(
                    TrendingRefreshProgress.MatchingSeries(
                        checked = checked,
                        matched = seriesMatchCount,
                        candidates = seriesCandidates.size,
                        target = seriesTarget,
                    ),
                )
            }
        } else TrendingMatchResult(emptyList(), 0, 0, 0)

        val selections = TrendingMatcher.assemble(movieResult.selections, seriesResult.selections)
        val completedAt = System.currentTimeMillis()
        val generationId = UUID.randomUUID().toString()
        if (selections.size < TrendingDao.MIN_ELIGIBLE_ITEMS) {
            val writeStarted = SystemClock.elapsedRealtime()
            trendingDao.writeBelowThreshold(
                TrendingSnapshotEntity(
                    sourceId = sourceId,
                    status = TrendingSnapshotStatus.BELOW_THRESHOLD,
                    metadataLanguage = config.resolvedLanguage,
                    refreshedAt = completedAt,
                    candidateFetchedAt = candidateFetchedAt,
                    generationId = generationId,
                    itemCount = 0,
                    matchedItemCount = selections.size,
                    lastAttemptAt = completedAt,
                    lastAttemptStatus = TrendingAttemptStatus.BELOW_THRESHOLD,
                ),
            )
            logResult(sourceId, movieCandidates.size, seriesCandidates.size, movieResult, seriesResult, selections.size, "below-threshold", fetchMs, preparationMs, 0, SystemClock.elapsedRealtime() - writeStarted, totalStarted)
            return TrendingRefreshOutcome.Replaced(itemCount = selections.size, eligible = false)
        }

        // Provider season inventory is deliberately lazy for Xtream/Stalker. Load it only for the
        // final Trending series, using the normal stable-ID merge so history and resume stay attached.
        // M3U is already populated during sync, and the repository turns this into a cheap cache hit.
        val selectedSeries = selections.filter { it.variant.item.mediaType == MediaType.SERIES }
        onProgress(TrendingRefreshProgress.LoadingProviderSeasons(0, selectedSeries.size))
        selectedSeries.forEachIndexed { index, selection ->
            val seriesId = selection.variant.item.id
            val loaded = runCatching {
                seriesDao.getSeriesById(seriesId)?.let { seriesRepository.loadEpisodes(it) } == true
            }.onFailure {
                Log.w(TAG, "sourceId=$sourceId provider season load failed seriesId=$seriesId", it)
            }.getOrDefault(false)
            val seasonCount = runCatching { seriesDao.storedSeasonCount(seriesId) }.getOrDefault(0)
            Log.i(
                TAG,
                "sourceId=$sourceId provider seasons checked=${index + 1}/${selectedSeries.size} " +
                    "seriesId=$seriesId loaded=$loaded seasons=$seasonCount",
            )
            onProgress(TrendingRefreshProgress.LoadingProviderSeasons(index + 1, selectedSeries.size))
        }

        onProgress(TrendingRefreshProgress.Enriching(selections.size))
        val enrichmentStarted = SystemClock.elapsedRealtime()
        val semaphore = Semaphore(ENRICHMENT_CONCURRENCY)
        val details: List<Pair<TrendingSelection, MovieDetails?>> = coroutineScope {
            selections.map { selection ->
                async {
                    selection to semaphore.withPermit {
                        when (selection.candidate.type) {
                            MetadataType.MOVIE -> metadataProvider.movieDetails(selection.candidate.tmdbId)
                            MetadataType.TV -> metadataProvider.tvDetails(selection.candidate.tmdbId)
                            MetadataType.EPISODE -> null
                        }
                    }
                }
            }.awaitAll()
        }
        val enrichmentMs = SystemClock.elapsedRealtime() - enrichmentStarted
        val detailFailures = details.count { it.second == null }
        if (detailFailures > 0) Log.w(TAG, "sourceId=$sourceId detail fallback count=$detailFailures")

        val items = details.mapIndexed { position, (selection, detail) ->
            val candidate = selection.candidate
            val variant = selection.variant
            TrendingItemEntity(
                sourceId = sourceId,
                position = position,
                tmdbId = candidate.tmdbId,
                mediaType = variant.item.mediaType,
                trendingRank = candidate.trendingRank,
                providerItemId = variant.item.id,
                providerRemoteId = variant.item.remoteId,
                providerStableKey = variant.stableKey,
                providerRawName = variant.item.name,
                canonicalTitle = variant.canonicalTitle,
                providerLanguage = variant.language,
                advertisedQuality = variant.quality.label,
                advertisedCapabilities = variant.capabilities.takeIf { it.isNotEmpty() }?.joinToString(" • "),
                localizedTitle = candidate.localizedTitle,
                originalTitle = candidate.originalTitle,
                year = detail?.year ?: candidate.year,
                overview = detail?.overview ?: candidate.overview,
                posterPath = detail?.posterPath ?: candidate.posterPath,
                backdropPath = detail?.backdropPath ?: candidate.backdropPath,
                rating = detail?.rating ?: candidate.rating,
                trailerKey = detail?.trailerKey,
                generationId = generationId,
                refreshedAt = completedAt,
            )
        }
        onProgress(TrendingRefreshProgress.Publishing(items.size))
        val writeStarted = SystemClock.elapsedRealtime()
        trendingDao.replaceSnapshot(
            TrendingSnapshotEntity(
                sourceId = sourceId,
                status = TrendingSnapshotStatus.ELIGIBLE,
                metadataLanguage = config.resolvedLanguage,
                refreshedAt = completedAt,
                candidateFetchedAt = candidateFetchedAt,
                generationId = generationId,
                itemCount = items.size,
                matchedItemCount = items.size,
                lastAttemptAt = completedAt,
                lastAttemptStatus = TrendingAttemptStatus.SUCCESS,
            ),
            items,
        )
        val writeMs = SystemClock.elapsedRealtime() - writeStarted
        logResult(sourceId, movieCandidates.size, seriesCandidates.size, movieResult, seriesResult, items.size, "published", fetchMs, preparationMs, enrichmentMs, writeMs, totalStarted)
        return TrendingRefreshOutcome.Replaced(itemCount = items.size, eligible = true)
    }

    private suspend fun backfillMovies(sourceId: Long, onProgress: (Int) -> Unit): Int {
        var count = 0
        while (true) {
            val rows = movieDao.trendingMetadataBackfill(sourceId, BACKFILL_BATCH)
            if (rows.isEmpty()) return count
            movieDao.updateAll(rows)
            count += rows.size
            onProgress(count)
        }
    }

    private suspend fun backfillSeries(sourceId: Long, onProgress: (Int) -> Unit): Int {
        var count = 0
        while (true) {
            val rows = seriesDao.trendingMetadataBackfill(sourceId, BACKFILL_BATCH)
            if (rows.isEmpty()) return count
            seriesDao.updateSeries(rows)
            count += rows.size
            onProgress(count)
        }
    }

    private suspend fun preserveFailure(sourceId: Long, stage: String, language: String, startedAt: Long): TrendingRefreshOutcome.PreservedFailure {
        val attemptAt = System.currentTimeMillis()
        trendingDao.recordFailure(
            TrendingSnapshotEntity(
                sourceId = sourceId,
                status = TrendingSnapshotStatus.NEVER_BUILT,
                metadataLanguage = language,
                refreshedAt = 0,
                candidateFetchedAt = 0,
                generationId = "",
                itemCount = 0,
                lastAttemptAt = attemptAt,
                lastAttemptStatus = TrendingAttemptStatus.FAILED,
                failureStage = stage,
            ),
            stage,
        )
        Log.w(TAG, "Preserve old snapshot sourceId=$sourceId failedStage=$stage totalMs=${SystemClock.elapsedRealtime() - startedAt}")
        return TrendingRefreshOutcome.PreservedFailure(stage)
    }

    private fun logResult(
        sourceId: Long,
        movieCandidates: Int,
        seriesCandidates: Int,
        movies: TrendingMatchResult,
        series: TrendingMatchResult,
        finalCount: Int,
        decision: String,
        fetchMs: Long,
        preparationMs: Long,
        enrichmentMs: Long,
        writeMs: Long,
        startedAt: Long,
    ) {
        Log.i(TAG, "sourceId=$sourceId candidates=$movieCandidates/$seriesCandidates matches=${movies.selections.size}/${series.selections.size} final=$finalCount decision=$decision")
        Log.i(TAG, "sourceId=$sourceId timing fetchMs=$fetchMs preparationMs=$preparationMs exactMs=${movies.exactLookupMs + series.exactLookupMs} ftsMs=${movies.ftsLookupMs + series.ftsLookupMs} ftsFallbacks=${movies.ftsFallbacks + series.ftsFallbacks} enrichmentMs=$enrichmentMs snapshotWriteMs=$writeMs totalMs=${SystemClock.elapsedRealtime() - startedAt}")
    }

    companion object {
        private const val TAG = "TrendingRepository"
        private const val BALANCED_TARGET = 5
        private const val BACKFILL_BATCH = 1_000
        private const val ENRICHMENT_CONCURRENCY = 3
    }
}

sealed interface TrendingRefreshOutcome {
    data object SkippedProviderMode : TrendingRefreshOutcome
    data object SkippedHidden : TrendingRefreshOutcome
    data object SourceMissing : TrendingRefreshOutcome
    data object NoVodScope : TrendingRefreshOutcome
    data class PreservedFailure(val stage: String) : TrendingRefreshOutcome
    data class Replaced(val itemCount: Int, val eligible: Boolean) : TrendingRefreshOutcome
}

sealed interface TrendingRefreshProgress {
    data object Fetching : TrendingRefreshProgress
    data class CandidatesReceived(val movies: Int, val series: Int) : TrendingRefreshProgress {
        val total: Int get() = movies + series
    }
    data class PreparingCatalog(val processed: Int, val total: Int) : TrendingRefreshProgress
    data class MatchingMovies(val checked: Int, val matched: Int, val candidates: Int, val target: Int) : TrendingRefreshProgress
    data class MatchingSeries(val checked: Int, val matched: Int, val candidates: Int, val target: Int) : TrendingRefreshProgress
    data class LoadingProviderSeasons(val processed: Int, val total: Int) : TrendingRefreshProgress
    data class Enriching(val itemCount: Int) : TrendingRefreshProgress
    data class Publishing(val itemCount: Int) : TrendingRefreshProgress
}
