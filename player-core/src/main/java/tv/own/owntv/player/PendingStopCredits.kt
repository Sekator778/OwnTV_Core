package tv.own.owntv.player

import java.util.concurrent.atomic.AtomicInteger

/**
 * Classification credits for the cleanup `END_FILE` events that OwnTV's *own* `loadfile`/`stop`
 * commands produce. mpv's Kotlin wrapper doesn't expose `mpv_event_end_file.reason`, so the player
 * cannot otherwise tell "the app replaced this file" apart from "the stream died".
 *
 * The contract is one credit per app-issued command, consumed by exactly one `END_FILE`:
 *
 * - Credits are **never bulk-cleared**. A cleanup `END_FILE` can arrive *after* the next file's
 *   `FILE_LOADED`, and clearing on `FILE_LOADED` made that late event look like a playback failure —
 *   a spurious retry or hard reset in the middle of healthy playback.
 * - The count is **capped**, so a command whose `END_FILE` never arrives leaks at most a bounded
 *   number of credits instead of accumulating until real failures start being swallowed.
 *
 * Safe to call from any thread: the player increments on its command worker and consumes on mpv's
 * event thread.
 */
internal class PendingStopCredits(private val max: Int) {

    private val count = AtomicInteger(0)

    /** Outstanding credits — for diagnostics only; never branch playback on a racy read. */
    fun peek(): Int = count.get()

    /** Record an app-issued command. Returns the count after capping. */
    fun credit(): Int {
        val credits = count.incrementAndGet()
        if (credits > max) {
            count.set(max)
            return max
        }
        return credits
    }

    /** Claim one credit for an `END_FILE`. True when this event was app-caused, false when it's real. */
    fun consume(): Boolean {
        while (true) {
            val current = count.get()
            if (current <= 0) return false
            if (count.compareAndSet(current, current - 1)) return true
        }
    }

    /** Drop one credit that was never issued — the command threw before mpv saw it. */
    fun rollback() {
        consume()
    }

    /** A genuinely fresh start (new mpv core): nothing is owed. */
    fun reset() {
        count.set(0)
    }
}
