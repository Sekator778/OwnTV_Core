package tv.own.owntv.core.settings

import kotlin.math.roundToInt

/** Percentage split between the pinned Guide channel column and its scrollable EPG timeline. */
data class GuideWidthShares(
    val channels: Int,
    val epg: Int,
) {
    val total: Int get() = channels + epg
    val isValid: Boolean
        get() = channels in GuideWidthLimits.MIN..GuideWidthLimits.MAX &&
            epg in GuideWidthLimits.MIN..GuideWidthLimits.MAX &&
            total == GuideWidthLimits.TOTAL
}

object GuideWidthLimits {
    const val MIN = 10
    const val MAX = 90
    const val STEP = 5
    const val TOTAL = 100
    const val DEFAULT_CHANNELS = 10
    const val DEFAULT_EPG = 90

    fun snap(value: Int): Int =
        ((value.toFloat() / STEP).roundToInt() * STEP).coerceIn(MIN, MAX)

    val defaults = GuideWidthShares(DEFAULT_CHANNELS, DEFAULT_EPG)
}

/** Repairs stale/imported preferences while preserving the channel share whenever possible. */
fun normalizeGuideWidths(shares: GuideWidthShares): GuideWidthShares {
    val channels = GuideWidthLimits.snap(shares.channels)
    return GuideWidthShares(channels, GuideWidthLimits.TOTAL - channels)
}
