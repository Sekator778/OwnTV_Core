package tv.own.owntv.features.settings.data

/**
 * How close to the live edge to play Live TV, trading latency against stability. Applied on the next
 * channel open (live streams only — VOD is unaffected):
 *  - ExoPlayer live → a `MediaItem.LiveConfiguration` target offset (the main HLS latency lever);
 *  - mpv live → `demuxer-readahead-secs`.
 *
 * [BALANCED] is the default and applies no override — the engines keep their existing behaviour, so
 * it can never regress a stream that already works.
 */
enum class LiveLatency(val label: String) {
    LOW("Low latency"),
    BALANCED("Balanced"),
    STABLE("Stable"),
    CUSTOM("Custom");

    companion object {
        val DEFAULT = BALANCED
        fun fromName(name: String?): LiveLatency = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

/** Maps a [LiveLatency] choice to an effective buffer size in seconds (null = keep engine defaults). */
object LiveBuffer {
    const val LOW_SECS = 2
    const val STABLE_SECS = 15
    const val CUSTOM_MIN = 1
    const val CUSTOM_MAX = 60
    const val CUSTOM_DEFAULT = 8

    /** Below this many seconds counts as "lower than Balanced" — the low-latency warning threshold. */
    const val WARN_BELOW_SECS = CUSTOM_DEFAULT

    fun clampCustom(secs: Int): Int = secs.coerceIn(CUSTOM_MIN, CUSTOM_MAX)

    /** True when [secs] is a real, below-Balanced buffer worth warning the user about. */
    fun isLowLatency(secs: Int?): Boolean = secs != null && secs < WARN_BELOW_SECS

    /** Effective live buffer in seconds for [mode]; null for [LiveLatency.BALANCED] (no override). */
    fun effectiveSeconds(mode: LiveLatency, customSecs: Int): Int? = when (mode) {
        LiveLatency.LOW -> LOW_SECS
        LiveLatency.STABLE -> STABLE_SECS
        LiveLatency.CUSTOM -> clampCustom(customSecs)
        LiveLatency.BALANCED -> null
    }
}
