package tv.own.owntv.core.sync

/** Live progress of an import (e.g. "Channels", 128430, null → indeterminate total). */
data class ImportStage(val label: String, val processed: Int, val total: Int?) {
    val fraction: Float?
        get() = total?.takeIf { it > 0 }?.let { processed.toFloat() / it }
}

/** Terminal result of a sync run. */
sealed interface SyncResult {
    data object Success : SyncResult
    data object Cancelled : SyncResult
    data class Failed(val message: String) : SyncResult
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
