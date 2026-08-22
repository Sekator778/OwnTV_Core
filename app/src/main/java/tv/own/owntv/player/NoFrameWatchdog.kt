package tv.own.owntv.player

import android.os.Handler
import android.os.Looper

/**
 * The "connected, but no picture ever arrived" watchdog shared by the ExoPlayer-based engines.
 *
 * A stream that opens, buffers and even reports READY without ever rendering a frame produces no
 * error of its own, so nothing but a deadline can tell the engine that the item is not going to
 * play. Each engine used to hand-roll the same three lines — a main-looper [Handler], a one-shot
 * Runnable, and a `removeCallbacks` on first frame / error / stop / release — and the bug that shape
 * invites is a missed cancel, which fires the fallback over a stream that is playing perfectly.
 *
 * The timeout is passed in at [arm] time rather than owned here, because the budget depends on when
 * the deadline starts and on how the item decodes. The values, and why they are what they are:
 *
 * - **12 s, armed at load** — [HeroPreviewEngine] and [ExoSubtitleEngine]. Both arm before the
 *   player has connected, so the budget has to cover DNS, the HTTP open, the first segments and the
 *   decoder's own start-up before a frame can exist at all. The handoff engine used to allow 8 s
 *   here purely because it was written separately; on a slow provider that is inside the normal
 *   opening time for a 4K file, and tripping early costs a needless software-decode restart or an
 *   "audio, no picture" error over an item that was about to play. 12 s is the value that has been
 *   in production on the hero path, so it is the measured one of the two.
 * - **25 s, armed at load, software + mid-GOP** — [ExoSubtitleEngine] for a catch-up archive. It
 *   decodes in software AND starts mid-GOP, so the decoder must chew inter-frames up to the next
 *   keyframe before it can render. This is not an inconsistency to be unified away.
 * - **8 s, armed at STATE_READY** — `LivePreviewEngine`'s "audio plays, no picture" check. It runs
 *   inside that engine's existing polled health watchdog rather than here: the poll is shared with
 *   four other checks and is device-tiered, so giving this one its own timer would add back a
 *   wake-up that was deliberately removed. Its 8 s starts once the stream is already READY, which is
 *   a later reference point than the 12 s above, not a shorter total.
 *
 * Main looper, so it shares ExoPlayer's application thread and holds nothing alive past [disarm].
 */
class NoFrameWatchdog(private val onExpired: () -> Unit) {
    private val handler = Handler(Looper.getMainLooper())
    private val fire = Runnable { onExpired() }

    /** Start (or restart) the deadline. Any previously armed deadline is dropped. */
    fun arm(timeoutMs: Long) {
        handler.removeCallbacks(fire)
        handler.postDelayed(fire, timeoutMs)
    }

    /** Cancel the deadline. Safe to call when nothing is armed, and from inside [onExpired]. */
    fun disarm() {
        handler.removeCallbacks(fire)
    }
}
