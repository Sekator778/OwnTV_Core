package tv.own.owntv.player

/**
 * The cross-engine fallback ladder for one live tune: which engine/format combinations are still worth
 * trying for the channel currently on screen, in what order, and which have already been spent.
 *
 * Extracted from `LiveViewModel` so the sequencing — the most consequential logic in playback — can be
 * pinned by unit tests without a ViewModel, a database or a player. Behaviour is unchanged: the order,
 * the filtering and the per-engine "no HLS variant" lessons are the same ones that shipped.
 *
 * The caller keeps everything with a side effect on the outside world: logging, the failure record, the
 * actual engine handoff, and the `.ts` pin ExoPlayer needs. This class only decides.
 */
class LiveLadder {

    /**
     * One combination of engine and stream format. A live tune walks these in order, each **at most
     * once**, and that finiteness is the whole safety property: without it an ExoPlayer failure that
     * hands over to mpv and an mpv failure that hands back could bounce a channel between engines
     * forever.
     */
    enum class Rung(val onMpv: Boolean, val isHls: Boolean) {
        EXO_HLS(onMpv = false, isHls = true),
        EXO_TS(onMpv = false, isHls = false),
        MPV_HLS(onMpv = true, isHls = true),
        MPV_TS(onMpv = true, isHls = false),
    }

    /** The stream URL this ladder was armed for; a newer tune of another channel supersedes it. */
    var url: String? = null
        private set

    private var order: List<Rung> = emptyList()
    private val spent = mutableSetOf<Rung>()

    /** The rung playback is on right now — the reference for "the same engine" when a refusal makes the
     *  same engine's other extension pointless. */
    private var current: Rung? = null

    /** The rungs this tune will walk, after filtering — exposed for tests and diagnostics. */
    val plan: List<Rung> get() = order

    fun isSpent(rung: Rung): Boolean = rung in spent

    /**
     * Reset the ladder for a fresh tune of [streamUrl]. Rungs already climbed are forgotten — a new tune
     * is a new chance, including for a channel that ended the last one on its final rung.
     *
     * Order follows where the tune *started*, so the engine the user (or a pin) asked for gets both of
     * its formats tried before the other engine is considered at all:
     *
     *  - started on ExoPlayer → `exo HLS → exo TS → mpv HLS → mpv TS`
     *  - started on mpv       → `mpv HLS → mpv TS → exo HLS → exo TS`
     *
     * An "only" [preference] keeps the first pair and drops the second: the chosen engine still gets both
     * of its formats — that step rescues most channels — but a stream it cannot play stays as a visible
     * failure instead of paying for a handover the user has said will not help. That is the whole
     * difference between the "first" and "only" modes here; see [EnginePreference].
     *
     * The HLS rungs are dropped entirely when this channel has no HLS/TS distinction — "Prefer HLS" off
     * for the playlist, a Stalker cmd, or a URL that is natively `.m3u8` — leaving the plain
     * `exo → mpv` (or `mpv → exo`) pair, which is what happened before any of this existed. A rung is
     * dropped for one engine alone when *that* engine has already learned this session that the
     * channel's `.m3u8` does not work for it.
     *
     * Why per-format rungs at all: a channel's `.m3u8` and its `.ts` are different muxes, and an engine
     * that chokes on one can play the other. Traced on a 4K channel whose HLS audio ExoPlayer can never
     * start (buffer full, `audio=false`), while mpv plays that very same `.m3u8` — so a ladder that
     * skipped `mpv HLS` after ExoPlayer's HLS failure was skipping the combination that worked.
     *
     * [hasHlsAlternative] is suspended deliberately: resolving it hits the source row, and it must run
     * at the same point in the sequence it always did — after this ladder has claimed [streamUrl].
     */
    suspend fun arm(
        streamUrl: String,
        preference: EnginePreference,
        hasHlsAlternative: suspend () -> Boolean,
    ) {
        url = streamUrl
        spent.clear()
        val base = if (preference.startsOnMpv) {
            listOf(Rung.MPV_HLS, Rung.MPV_TS, Rung.EXO_HLS, Rung.EXO_TS)
        } else {
            listOf(Rung.EXO_HLS, Rung.EXO_TS, Rung.MPV_HLS, Rung.MPV_TS)
        }.filter { preference.allowsHandover || it.onMpv == preference.startsOnMpv }
        // Drop the HLS rungs this channel has no use for: no swap happened at all, or that engine has
        // already learned in this session that its `.m3u8` doesn't work. Keeping the ladder honest
        // matters — a rung the tune code would silently turn into the `.ts` one anyway would otherwise
        // cost a duplicate attempt before the real next rung.
        val hasHls = hasHlsAlternative()
        order = base.filterNot {
            it.isHls && (
                !hasHls ||
                    (it.onMpv && LiveStreamQuirks.lacksHlsVariantMpv(streamUrl)) ||
                    (!it.onMpv && LiveStreamQuirks.lacksHlsVariant(streamUrl))
                )
        }
        // The rung we are on right now is the first one of that order.
        current = order.firstOrNull()
        current?.let { spent += it }
    }

    /** False once a newer tune owns the ladder, so a late failure from the old one cannot advance it. */
    fun owns(streamUrl: String): Boolean = url == streamUrl

    /**
     * The next untried rung, marked spent, or null when the ladder is exhausted (the caller then leaves
     * the failure on screen — there is genuinely nothing left).
     *
     * Advancing onto an engine's `.ts` rung after that engine's `.m3u8` rung was tried records the
     * format lesson for it, so the NEXT tune of this channel skips the dead rung instead of paying for
     * it again. Never recorded when there was no swap to blame.
     *
     * [failureWasAboutFormat] is the second half of that guard, and it changes two things. A panel that
     * refuses the *request* — an account-busy 458, a 403, a rate limit — says nothing whatsoever about
     * whether its `.m3u8` works, so:
     *
     *  1. **Nothing is learned.** Recording a format lesson there taught the app a fact it had no
     *     evidence for, and the lesson outlived the lockout that produced it.
     *  2. **The same engine's other extension is skipped**, because a different file extension cannot
     *     answer an account-level refusal — it is a guaranteed-dead attempt. The *other engine* is still
     *     tried, and deliberately so: handing over takes a few seconds and releases the session the
     *     previous engine was holding, which on a one-session panel is often exactly what was needed for
     *     the channel to open. (Owner-observed; the handoff is the fix, not a formality.)
     *
     * If only same-engine rungs remain, one is taken anyway — a doomed attempt beats giving up early.
     */
    fun advance(failureWasAboutFormat: Boolean = true): Rung? {
        val here = current
        val next = when {
            failureWasAboutFormat || here == null -> order.firstOrNull { it !in spent }
            else -> order.firstOrNull { it !in spent && it.onMpv != here.onMpv }
                ?: order.firstOrNull { it !in spent }
        } ?: return null
        spent += next
        current = next
        val streamUrl = url
        if (failureWasAboutFormat && streamUrl != null && !next.isHls) {
            if (next.onMpv && Rung.MPV_HLS in spent) LiveStreamQuirks.rememberNoHlsVariantMpv(streamUrl)
            if (!next.onMpv && Rung.EXO_HLS in spent) LiveStreamQuirks.rememberNoHlsVariant(streamUrl)
        }
        return next
    }

    /** Log label for a rung — Logcat and the playback error log only, never shown to a user. */
    fun label(rung: Rung): String = when (rung) {
        Rung.EXO_HLS -> "ExoPlayer + HLS"
        Rung.EXO_TS -> "ExoPlayer + TS"
        Rung.MPV_HLS -> "mpv + HLS"
        Rung.MPV_TS -> "mpv + TS"
    }
}
