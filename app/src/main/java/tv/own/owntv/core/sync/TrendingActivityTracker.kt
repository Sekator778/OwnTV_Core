package tv.own.owntv.core.sync

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** App-wide observational state for the post-catalog Now Trending build. */
class TrendingActivityTracker {
    enum class Stage { STARTING, RECEIVED, PREPARING, MATCHING_MOVIES, MATCHING_SERIES, LOADING_SEASONS, ENRICHING, PUBLISHING }

    data class ActiveBuild(
        val sourceId: Long,
        val sourceName: String,
        val stage: Stage = Stage.STARTING,
        val candidates: Int = 0,
        val movieCandidates: Int = 0,
        val seriesCandidates: Int = 0,
        val preparationProcessed: Int = 0,
        val preparationTotal: Int = 0,
        val movieChecked: Int = 0,
        val seriesChecked: Int = 0,
        val movieTarget: Int = 10,
        val seriesTarget: Int = 0,
        val finalItems: Int = 0,
        val matched: Int = 0,
        val movieMatches: Int = 0,
        val seriesMatches: Int = 0,
        val seasonsProcessed: Int = 0,
        val seasonsTotal: Int = 0,
    )

    data class CompletedBuild(
        val sourceId: Long,
        val sourceName: String,
        val itemCount: Int,
        val eligible: Boolean,
        val preservedFailure: Boolean,
        val movieCandidates: Int,
        val seriesCandidates: Int,
        val movieMatches: Int,
        val seriesMatches: Int,
        val timestamp: Long,
    )

    private val _active = MutableStateFlow<Map<Long, ActiveBuild>>(emptyMap())
    private val _lastCompleted = MutableStateFlow<CompletedBuild?>(null)

    val active: StateFlow<Map<Long, ActiveBuild>> = _active.asStateFlow()
    val lastCompleted: StateFlow<CompletedBuild?> = _lastCompleted.asStateFlow()

    fun started(sourceId: Long, sourceName: String) {
        _active.value = _active.value + (sourceId to ActiveBuild(sourceId, sourceName))
    }

    fun progress(
        sourceId: Long,
        stage: Stage,
        candidates: Int? = null,
        movieCandidates: Int? = null,
        seriesCandidates: Int? = null,
        preparationProcessed: Int? = null,
        preparationTotal: Int? = null,
        movieChecked: Int? = null,
        seriesChecked: Int? = null,
        movieTarget: Int? = null,
        seriesTarget: Int? = null,
        finalItems: Int? = null,
        matched: Int? = null,
        movieMatches: Int? = null,
        seriesMatches: Int? = null,
        seasonsProcessed: Int? = null,
        seasonsTotal: Int? = null,
    ) {
        val current = _active.value[sourceId] ?: return
        _active.value = _active.value + (
            sourceId to current.copy(
                stage = stage,
                candidates = candidates ?: current.candidates,
                movieCandidates = movieCandidates ?: current.movieCandidates,
                seriesCandidates = seriesCandidates ?: current.seriesCandidates,
                preparationProcessed = preparationProcessed ?: current.preparationProcessed,
                preparationTotal = preparationTotal ?: current.preparationTotal,
                movieChecked = movieChecked ?: current.movieChecked,
                seriesChecked = seriesChecked ?: current.seriesChecked,
                movieTarget = movieTarget ?: current.movieTarget,
                seriesTarget = seriesTarget ?: current.seriesTarget,
                finalItems = finalItems ?: current.finalItems,
                matched = matched ?: current.matched,
                movieMatches = movieMatches ?: current.movieMatches,
                seriesMatches = seriesMatches ?: current.seriesMatches,
                seasonsProcessed = seasonsProcessed ?: current.seasonsProcessed,
                seasonsTotal = seasonsTotal ?: current.seasonsTotal,
            )
        )
    }

    fun finished(
        sourceId: Long,
        sourceName: String,
        itemCount: Int,
        eligible: Boolean,
        preservedFailure: Boolean = false,
    ) {
        val active = _active.value[sourceId]
        _active.value = _active.value - sourceId
        _lastCompleted.value = CompletedBuild(
            sourceId = sourceId,
            sourceName = sourceName,
            itemCount = itemCount,
            eligible = eligible,
            preservedFailure = preservedFailure,
            movieCandidates = active?.movieCandidates ?: 0,
            seriesCandidates = active?.seriesCandidates ?: 0,
            movieMatches = active?.movieMatches ?: 0,
            seriesMatches = active?.seriesMatches ?: 0,
            timestamp = android.os.SystemClock.elapsedRealtime(),
        )
    }

    fun consumeCompleted(timestamp: Long) {
        if (_lastCompleted.value?.timestamp == timestamp) _lastCompleted.value = null
    }

    fun dismiss(sourceId: Long) {
        _active.value = _active.value - sourceId
    }
}
