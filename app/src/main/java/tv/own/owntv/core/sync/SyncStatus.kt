package tv.own.owntv.core.sync

import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.model.SourceType

/**
 * Per-section choice on Add (Now / Later / Off). Edit uses only On(=Now) / Off — now-vs-later
 * only matters during the first import.
 */
enum class SyncScopeChoice {
    Now,
    Later,
    Off,
    ;

    companion object {
        fun parse(raw: String?, default: SyncScopeChoice = Now): SyncScopeChoice = when (raw?.trim()?.lowercase()) {
            "now", "true", "on", "1", "yes" -> Now
            "later" -> Later
            "off", "false", "0", "no" -> Off
            else -> default
        }
    }
}

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
    data class Success(
        val warnings: List<SyncWarning> = emptyList(),
        /** Category churn on a resync (always 0 on a source's first sync, where everything is "new"). */
        val categoriesAdded: Int = 0,
        val categoriesRemoved: Int = 0,
    ) : SyncResult {
        fun warningSummary(): String? =
            warnings.takeIf { it.isNotEmpty() }?.joinToString(
                prefix = "Imported with warnings: ",
                separator = " · ",
            ) { "${it.label} failed" }

        fun categoryChangeSummary(): String {
            return "$categoriesAdded categories added, $categoriesRemoved removed"
        }
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

    fun intersect(other: SyncContentTypes) = SyncContentTypes(
        live = live && other.live,
        movies = movies && other.movies,
        series = series && other.series,
    )

    /** enabled - priority: the background remainder AFTER the foreground pass (enabled-relative). */
    fun remainderAfter(priority: SyncContentTypes) = SyncContentTypes(
        live = !priority.live && live,
        movies = !priority.movies && movies,
        series = !priority.series && series,
    )

    /** M3U/backup are single-stream (live-only) regardless of the persisted flags. */
    fun constrainedTo(type: SourceType) = when (type) {
        SourceType.M3U, SourceType.LOCAL_BACKUP -> copy(movies = false, series = false)
        else -> this
    }

    /** True iff this pass covered every section in [target]. Compare against [enabledFor], NOT [enabledOf]. */
    fun isCompleteFor(target: SyncContentTypes): Boolean = intersect(target) == target

    /** The single derivation used at every sync boundary: request ∩ enabled, then type-constrained. */
    fun effectiveFor(source: SourceEntity): SyncContentTypes =
        intersect(enabledOf(source)).constrainedTo(source.type)

    /** "Movies & Series" / "Live TV" … — for user-facing "still syncing in the background" notes. */
    fun label(): String = listOfNotNull(
        "Live TV".takeIf { live },
        "Movies".takeIf { movies },
        "Series".takeIf { series },
    ).joinToString(" & ")

    companion object {
        /** Raw persisted enabledScope (Off = false). */
        fun enabledOf(s: SourceEntity) = SyncContentTypes(s.syncLive, s.syncMovies, s.syncSeries)

        /** enabledScope constrained by source type — the completion TARGET (M3U → live-only). */
        fun enabledFor(s: SourceEntity) = enabledOf(s).constrainedTo(s.type)

        fun fromChoices(live: SyncScopeChoice, movies: SyncScopeChoice, series: SyncScopeChoice) = SyncContentTypes(
            live = live != SyncScopeChoice.Off,
            movies = movies != SyncScopeChoice.Off,
            series = series != SyncScopeChoice.Off,
        )

        fun priorityFromChoices(live: SyncScopeChoice, movies: SyncScopeChoice, series: SyncScopeChoice) = SyncContentTypes(
            live = live == SyncScopeChoice.Now,
            movies = movies == SyncScopeChoice.Now,
            series = series == SyncScopeChoice.Now,
        )
    }
}

/**
 * Appends a "the rest is still coming" line to a staged import's success summary. Without it the
 * "All set!" screen reads as if the whole catalog were only what the foreground pass imported
 * (e.g. a Stalker add shows just live channels), and users report the sync as broken instead of
 * noticing the background pill.
 */
fun String.withRemainderNote(remainder: SyncContentTypes): String =
    if (!remainder.hasAny) this
    else "$this\n${remainder.label()} are still syncing in the background — you can start watching now. " +
        "Progress shows in the small pill at the bottom of the screen."

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
