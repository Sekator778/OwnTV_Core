package tv.own.owntv.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.shareIn

/**
 * One shared 1 Hz tick for everything cosmetic the player displays.
 *
 * The position readout, the stream-info overlay and the fps chip were three separate timers producing
 * a single visual frame — 500 ms, 1,000 ms and a 1,500 ms one-shot. Together with the watchdogs that is
 * six to eight coroutines waking on sub-three-second intervals during live playback, each doing real
 * work (mpv property reads across JNI, decoder counters, Compose recompositions), on hardware where
 * that is not free.
 *
 * Everything here reads the SAME beat, so two readers cost one wake-up instead of two. 1 Hz because
 * nobody can see a 500 ms position update on a television from three metres away.
 *
 * **This is for cosmetics only. Watchdogs keep their own timers** — they are the safety net, they must
 * not be able to stop because the last bit of chrome left the screen, and several of them deliberately
 * poll faster than a second.
 *
 * The flow only runs while something is collecting it ([SharingStarted.WhileSubscribed]), so a HUD that
 * is not on screen costs nothing at all. `replay = 1` hands a new reader the current beat immediately,
 * so an overlay fills in on the frame it appears rather than a second later.
 */
object PlayerHeartbeat {

    /** The interval every cosmetic reader shares. */
    const val PERIOD_MS = 1_000L

    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

    /** Emits an incrementing counter once per [PERIOD_MS] while anything is collecting. */
    val beat: Flow<Long> = flow {
        var n = 0L
        while (true) {
            emit(n++)
            delay(PERIOD_MS)
        }
    }.shareIn(scope, SharingStarted.WhileSubscribed(), replay = 1)
}
