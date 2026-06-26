package tv.own.owntv.core.sync

import tv.own.owntv.core.model.SourceType

data class SyncProgressCounts(
    val live: Int,
    val movies: Int,
    val series: Int,
    val liveActive: Boolean,
    val moviesActive: Boolean,
    val seriesActive: Boolean,
) {
    val hasItems: Boolean
        get() = live > 0 || movies > 0 || series > 0

    fun label(): String = buildList {
        if (liveActive) add("${syncCount(live)} channels")
        if (moviesActive) add("${syncCount(movies)} movies")
        if (seriesActive) add("${syncCount(series)} series")
    }.joinToString(" · ")
}

data class SyncProgressDisplay(
    val title: String,
    val percent: String,
    val detail: String,
)

fun ImportStage.progressCounts(): SyncProgressCounts = SyncProgressCounts(
    live = liveProcessed,
    movies = moviesProcessed,
    series = seriesProcessed,
    liveActive = liveActive,
    moviesActive = moviesActive,
    seriesActive = seriesActive,
)

fun ImportStage.importProgressDisplay(): SyncProgressDisplay =
    syncProgressDisplay(overallPercent, progressCounts())

fun syncProgressCountsForSource(
    sourceType: SourceType,
    liveProcessed: Int,
    moviesProcessed: Int,
    seriesProcessed: Int,
    liveActive: Boolean,
    moviesActive: Boolean,
    seriesActive: Boolean,
): SyncProgressCounts = when (sourceType) {
    SourceType.M3U -> SyncProgressCounts(
        live = liveProcessed,
        movies = 0,
        series = 0,
        liveActive = true,
        moviesActive = false,
        seriesActive = false,
    )
    SourceType.XTREAM -> {
        val hasActivePhase = liveActive || moviesActive || seriesActive
        SyncProgressCounts(
            live = liveProcessed,
            movies = moviesProcessed,
            series = seriesProcessed,
            liveActive = if (hasActivePhase) liveActive else true,
            moviesActive = if (hasActivePhase) moviesActive else true,
            seriesActive = if (hasActivePhase) seriesActive else true,
        )
    }
    SourceType.LOCAL_BACKUP -> SyncProgressCounts(
        live = 0,
        movies = 0,
        series = 0,
        liveActive = false,
        moviesActive = false,
        seriesActive = false,
    )
}

fun syncProgressDisplay(overallPercent: Int?, counts: SyncProgressCounts?): SyncProgressDisplay =
    SyncProgressDisplay(
        title = "Importing catalog…",
        percent = overallPercent?.let { "${it.coerceIn(0, 100)}%" } ?: "Connecting…",
        detail = counts?.takeIf { it.hasItems }?.label()?.ifBlank { null } ?: "Preparing catalog",
    )

fun syncProgressRowText(overallPercent: Int, counts: SyncProgressCounts): String =
    if (counts.hasItems) {
        "Syncing ${overallPercent.coerceIn(0, 100)}% • ${counts.label()}"
    } else {
        "Preparing catalog"
    }

private fun syncCount(count: Int): String = when {
    count >= 1_000_000 -> scaledCount(count / 1_000_000.0, "M")
    count >= 1_000 -> scaledCount(count / 1_000.0, "K")
    else -> count.toString()
}

private fun scaledCount(value: Double, suffix: String): String =
    "%.1f".format(value).removeSuffix(".0") + suffix
