package tv.own.owntv.core.sync

/** Live source-sync progress reported as per-content counters. */
data class ImportStage(
    val liveProcessed: Int = 0,
    val moviesProcessed: Int = 0,
    val seriesProcessed: Int = 0,
    val liveActive: Boolean = false,
    val moviesActive: Boolean = false,
    val seriesActive: Boolean = false,
) {
    val totalProcessed: Int
        get() = liveProcessed + moviesProcessed + seriesProcessed
}

/** Xtream / M3U progress is reported in these three phases. */
enum class SyncPhase(val label: String) {
    LIVE("Live"),
    MOVIES("Movies"),
    SERIES("Series"),
}

/** Terminal result of a sync run. */
sealed interface SyncResult {
    data class Success(val warnings: List<SyncWarning> = emptyList()) : SyncResult {
        fun warningSummary(): String? =
            warnings.takeIf { it.isNotEmpty() }?.joinToString(
                prefix = "Imported with warnings: ",
                separator = " · ",
            ) { "${it.label} failed" }
    }

    data object Cancelled : SyncResult
    data class Failed(val message: String) : SyncResult
}

data class SyncWarning(val phase: String, val message: String) {
    val label: String
        get() = phase.replaceFirstChar { it.uppercase() }
}

data class SyncContentTypes(
    val live: Boolean = true,
    val movies: Boolean = true,
    val series: Boolean = true,
) {
    val hasAny: Boolean get() = live || movies || series

    fun remainderAfter(priority: SyncContentTypes) = SyncContentTypes(
        live = !priority.live && live,
        movies = !priority.movies && movies,
        series = !priority.series && series,
    )
}

data class SyncRunStats(
    val sourceId: Long,
    val startedAt: Long,
    val finishedAt: Long,
    val result: SyncResult,
    val phaseTiming: Map<String, Long>,
    val processedCounts: Map<String, Int>,
    val phaseErrors: Map<String, String>,
    val usedFallback: Boolean,
)
