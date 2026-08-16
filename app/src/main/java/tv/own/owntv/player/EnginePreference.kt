package tv.own.owntv.player

/**
 * Which playback engine a section starts on, and whether it may hand over to the other one.
 *
 * Both engines fail on streams the other plays fine, and neither is universally better: ExoPlayer opens
 * faster and is the only one with proper live closed captions, mpv carries the wider codec set (DTS,
 * TrueHD, odd containers) and re-reads a playlist with a fresh token where Media3 cannot. So the app
 * ships an automatic order and a preference, rather than a winner.
 *
 * The two "only" modes exist because a handover is not free: it costs a stop, a surface release and a
 * re-open — several seconds of black — and on a provider where the second engine was never going to
 * work, that is pure loss on every single channel. Anyone who has established that one engine is the
 * only one their box and panel agree on can say so and stop paying for the other's turn.
 *
 * They are still *modes*, not single attempts: an "only" mode keeps that engine's own `.m3u8` → `.ts`
 * step, which is what rescues most channels in practice. What it drops is the engine handover.
 *
 * The defaults deliberately differ by section — Live TV starts on ExoPlayer, Movies & Series on mpv —
 * so each screen names its own default in the UI. See `LiveLadder` for how a live tune walks these.
 */
enum class EnginePreference {
    /** ExoPlayer first, mpv as the automatic fallback. The Live TV default. */
    EXO_FIRST,

    /** mpv first, ExoPlayer as the automatic fallback. The Movies & Series default. */
    MPV_FIRST,

    /** ExoPlayer only — both of its formats, then give up rather than hand over to mpv. */
    EXO_ONLY,

    /** mpv only — both of its formats, then give up rather than hand over to ExoPlayer. */
    MPV_ONLY,
    ;

    /** Whether playback starts on mpv under this preference. */
    val startsOnMpv: Boolean get() = this == MPV_FIRST || this == MPV_ONLY

    /** Whether the other engine may be tried at all when the first one cannot play the stream. */
    val allowsHandover: Boolean get() = this == EXO_FIRST || this == MPV_FIRST

    companion object {
        /** The preference that starts on [onMpv] and keeps the automatic handover. */
        fun firstOn(onMpv: Boolean): EnginePreference = if (onMpv) MPV_FIRST else EXO_FIRST

        /** The preference that pins a tune to [onMpv] with no handover — an explicit per-item choice. */
        fun onlyOn(onMpv: Boolean): EnginePreference = if (onMpv) MPV_ONLY else EXO_ONLY
    }
}
