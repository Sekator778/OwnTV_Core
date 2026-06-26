package tv.own.owntv.core.sync

import kotlin.math.roundToInt

/** Live progress of an import (active stage + overall aggregate). */
data class ImportStage(
    val label: String,
    val processed: Int,
    val total: Int?,
    val overallPercent: Int = 0,
    val breakdown: String = "",
) {
    val fraction: Float?
        get() = total?.takeIf { it > 0 }?.let { processed.toFloat() / it }

    val overallFraction: Float
        get() = overallPercent.coerceIn(0, 100) / 100f
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

internal data class SyncPhaseProgress(
    val phase: SyncPhase,
    val processed: Int,
    val total: Int?,
    val bytesRead: Long,
    val bytesTotal: Long?,
    val done: Boolean,
) {
    val fraction: Float
        get() = when {
            total?.let { it > 0 } == true -> (processed.toFloat() / total.orZero()).coerceIn(0f, 1f)
            bytesTotal?.let { it > 0 } == true -> (bytesRead.toFloat() / bytesTotal.toFloat()).coerceIn(0f, 1f)
            done -> 1f
            else -> 0f
        }

    val percent: Int
        get() = (fraction * 100f).roundToInt()

    val weight: Float
        get() = when {
            total?.let { it > 0 } == true -> total.toFloat()
            bytesTotal?.let { it > 0 } == true -> bytesTotal.toFloat()
            else -> 1f
        }
}

internal class SyncProgressTracker(
    contentTypes: SyncContentTypes,
    private val onProgress: (ImportStage) -> Unit,
) {
    private val lock = Any()
    private val phaseOrder = buildList {
        if (contentTypes.live) add(SyncPhase.LIVE)
        if (contentTypes.movies) add(SyncPhase.MOVIES)
        if (contentTypes.series) add(SyncPhase.SERIES)
    }
    private val phaseStates = linkedMapOf<SyncPhase, PhaseState>()
    private var lastOverallFraction = 0f
    private var lastActivePhase: SyncPhase? = phaseOrder.firstOrNull()
    private var lastLabel: String = phaseOrder.firstOrNull()?.label.orEmpty()

    init {
        phaseOrder.forEach { phaseStates[it] = PhaseState() }
    }

    fun start(
        phase: SyncPhase,
        label: String,
        processed: Int = 0,
        total: Int? = null,
        bytesRead: Long = 0,
        bytesTotal: Long? = null,
    ): ImportStage = report(phase, label, processed = processed, total = total, bytesRead = bytesRead, bytesTotal = bytesTotal, done = false)

    fun update(
        phase: SyncPhase,
        label: String,
        processed: Int? = null,
        total: Int? = null,
        bytesRead: Long? = null,
        bytesTotal: Long? = null,
    ): ImportStage = report(phase, label, processed = processed, total = total, bytesRead = bytesRead, bytesTotal = bytesTotal, done = false)

    fun finish(
        phase: SyncPhase,
        label: String,
        processed: Int? = null,
        total: Int? = null,
        bytesRead: Long? = null,
        bytesTotal: Long? = null,
    ): ImportStage = report(phase, label, processed = processed, total = total, bytesRead = bytesRead, bytesTotal = bytesTotal, done = true)

    fun updateBytes(
        phase: SyncPhase,
        label: String,
        bytesRead: Long,
        bytesTotal: Long? = null,
    ): ImportStage = report(phase, label, bytesRead = bytesRead, bytesTotal = bytesTotal, done = false)

    fun completeAll(): ImportStage? {
        val snapshot = synchronized(lock) {
            if (phaseStates.isEmpty()) return null
            // Emit the latest snapshot without forcing unfinished phases to 100%.
            buildSnapshot(
                activeLabel = lastLabel.ifBlank { lastActivePhase?.label.orEmpty() },
                activePhase = lastActivePhase,
            )
        }
        onProgress(snapshot)
        return snapshot
    }

    private fun report(
        phase: SyncPhase,
        label: String,
        processed: Int? = null,
        total: Int? = null,
        bytesRead: Long? = null,
        bytesTotal: Long? = null,
        done: Boolean,
    ): ImportStage {
        val snapshot = synchronized(lock) {
            val current = phaseStates.getOrPut(phase) { PhaseState() }
            val nextProcessed = processed?.let { maxOf(current.processed, it) } ?: current.processed
            val nextTotal = total ?: current.total
            val nextBytesRead = bytesRead?.let { maxOf(current.bytesRead, it) } ?: current.bytesRead
            val nextBytesTotal = bytesTotal ?: current.bytesTotal
            phaseStates[phase] = current.copy(
                processed = nextProcessed,
                total = nextTotal,
                bytesRead = nextBytesRead,
                bytesTotal = nextBytesTotal,
                done = current.done || done,
            )
            lastActivePhase = phase
            lastLabel = label
            buildSnapshot(label, phase)
        }
        onProgress(snapshot)
        return snapshot
    }

    private fun buildSnapshot(activeLabel: String, activePhase: SyncPhase?): ImportStage {
        val phases = phaseOrder.mapNotNull { phase ->
            val state = phaseStates[phase] ?: return@mapNotNull null
            SyncPhaseProgress(
                phase = phase,
                processed = state.processed,
                total = state.total,
                bytesRead = state.bytesRead,
                bytesTotal = state.bytesTotal,
                done = state.done,
            )
        }
        val weightsAndFractions = phases.map { phase -> phase.weight to phase.fraction }
        val totalWeight = weightsAndFractions.sumOf { it.first.toDouble() }.toFloat()
        val rawOverallFraction = if (totalWeight > 0f) {
            weightsAndFractions.sumOf { (weight, fraction) -> (weight * fraction).toDouble() }.toFloat() / totalWeight
        } else {
            0f
        }
        val overallFraction = maxOf(lastOverallFraction, rawOverallFraction).coerceIn(0f, 1f)
        lastOverallFraction = overallFraction

        return ImportStage(
            label = activeLabel,
            processed = activePhase?.let { phaseStates[it]?.processed } ?: 0,
            total = activePhase?.let { phaseStates[it]?.total },
            overallPercent = (overallFraction * 100f).roundToInt(),
            breakdown = phases.joinToString(" • ") { "${it.phase.label} ${it.percent}%" },
        )
    }

    private data class PhaseState(
        val processed: Int = 0,
        val total: Int? = null,
        val bytesRead: Long = 0,
        val bytesTotal: Long? = null,
        val done: Boolean = false,
    )
}

private fun Int?.orZero(): Int = this ?: 0
