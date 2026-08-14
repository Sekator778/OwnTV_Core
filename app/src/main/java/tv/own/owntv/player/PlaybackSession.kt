package tv.own.owntv.player

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * The app's single point of contact with the rest of the system's audio: **audio focus** and a
 * **MediaSession** (F27). Before this, OwnTV neither requested focus nor published a session, so two
 * apps could play over each other and the TV's transport keys / Assistant "pause" never reached the
 * player.
 *
 * It is engine-agnostic on purpose. OwnTV plays through mpv *and* two ExoPlayer engines, and which one
 * owns the speaker changes with the content; all three already implement [PlaybackEngine], so this class
 * only ever talks to that interface. `OwnTVShell` is the one place that knows which engine is live, so it
 * [attach]es it and passes `null` when the player closes.
 *
 * ### Focus policy: duck, don't pause
 * A TV is not a phone. A navigation prompt, a doorbell camera notification or an Assistant reply must not
 * stop a live channel — a paused live stream falls behind the edge and comes back either late or as a
 * fresh reconnect, and a paused film loses the moment being watched. So:
 *
 * | Focus change | What we do |
 * |---|---|
 * | `LOSS_TRANSIENT_CAN_DUCK` | nothing — `setWillPauseWhenDucked(false)` means the platform attenuates our stream itself, with no HUD-visible volume change |
 * | `LOSS_TRANSIENT` | duck to [DUCK_PERCENT] ourselves and restore on the next gain |
 * | `LOSS` (permanent) | **pause**, and abandon focus. This one is another app taking the speaker for good; continuing is the "two apps playing at once" bug |
 * | `GAIN` | restore the pre-duck volume; playback that we paused stays paused (the user chose the other app) |
 */
class PlaybackSession(private val context: Context) {

    private val scope = CoroutineScope(Dispatchers.Main.immediate)
    private val audioManager by lazy { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    private var engine: PlaybackEngine? = null
    private var collectJob: Job? = null
    private var session: MediaSession? = null

    private var hasFocus = false
    /** Volume the user had set before we ducked, or null when not ducked. */
    private var preDuckVolume: Int? = null
    /** The level the duck actually set, so [unduck] can tell "still where we left it" from "the user
     *  changed the volume while ducked". */
    private var duckedTo: Int? = null

    /**
     * Make [engine] the one this session represents, or `null` when nothing is playing any more (the
     * player closed). Safe to call repeatedly with the same engine.
     */
    fun attach(engine: PlaybackEngine?) {
        if (this.engine === engine) return
        collectJob?.cancel()
        // A different engine (or none) starts a fresh session as far as controllers are concerned, so the
        // next publish must send the metadata even if the title happens to match.
        lastMetaKey = null
        this.engine = engine
        if (engine == null) {
            unduck()
            abandonFocus()
            session?.apply { isActive = false; release() }
            session = null
            return
        }
        val s = session ?: createSession().also { session = it }
        s.isActive = true
        collectJob = combine(
            engine.isPlaying,
            engine.currentMeta,
            engine.position,
            engine.duration,
        ) { playing, meta, position, duration -> State(playing, meta, position, duration, engine.isLiveContent) }
            .onEach(::publish)
            .launchIn(scope)
    }

    /** The parts of [State] that actually reach `MediaMetadata`; see [publish]. */
    private data class MetaKey(val title: String, val subtitle: String, val durationMs: Long)

    private var lastMetaKey: MetaKey? = null

    private data class State(
        val playing: Boolean,
        val meta: MediaMeta,
        val positionMs: Long,
        val durationMs: Long,
        val live: Boolean,
    )

    private fun publish(state: State) {
        // Focus is held only while we are actually making sound. Keeping it through a pause left every
        // other app on the TV ducked (or locked out) for as long as the user left the player paused.
        if (state.playing) requestFocus() else abandonFocus()
        val s = session ?: return
        runCatching {
            // Metadata only when it actually changed (A-F10/F-F9). This is driven by a combine() that also
            // carries the position, so it fires on every position tick — but the title, subtitle and
            // duration only change when the ITEM does. Rebuilding and re-publishing MediaMetadata dozens
            // of times a second was pure waste, and every controller on the TV had to process each one.
            // The playback state below still updates every tick: a controller needs the moving position.
            val metaKey = MetaKey(
                title = state.meta.title.orEmpty(),
                subtitle = state.meta.subtitle.orEmpty(),
                // Live has no meaningful duration; -1 tells a controller "not seekable/unknown".
                durationMs = if (state.live) -1L else state.durationMs,
            )
            if (metaKey != lastMetaKey) {
                lastMetaKey = metaKey
                s.setMetadata(
                    MediaMetadata.Builder()
                        .putString(MediaMetadata.METADATA_KEY_TITLE, metaKey.title)
                        .putString(MediaMetadata.METADATA_KEY_ARTIST, metaKey.subtitle)
                        .putLong(MediaMetadata.METADATA_KEY_DURATION, metaKey.durationMs)
                        .build(),
                )
            }
            var actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_STOP or
                PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS
            if (!state.live) {
                actions = actions or PlaybackState.ACTION_SEEK_TO or
                    PlaybackState.ACTION_FAST_FORWARD or PlaybackState.ACTION_REWIND
            }
            s.setPlaybackState(
                PlaybackState.Builder()
                    .setActions(actions)
                    .setState(
                        if (state.playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                        state.positionMs,
                        if (state.playing) 1f else 0f,
                    )
                    .build(),
            )
        }
    }

    private fun createSession(): MediaSession = MediaSession(context, SESSION_TAG).apply {
        setCallback(object : MediaSession.Callback() {
            override fun onPlay() = withEngine { if (!it.isPlaying.value) it.togglePlayPause() }
            override fun onPause() = withEngine { if (it.isPlaying.value) it.togglePlayPause() }
            // Nothing here may tear the player down — this session doesn't own the UI. "Stop" from a
            // system control therefore means "silence it", which is a pause the user can undo.
            override fun onStop() = withEngine { if (it.isPlaying.value) it.togglePlayPause() }
            override fun onSeekTo(pos: Long) = withEngine {
                if (!it.isLiveContent) it.seekBy(pos - it.position.value)
            }
            override fun onFastForward() = withEngine { if (!it.isLiveContent) it.seekBy(SEEK_STEP_MS) }
            override fun onRewind() = withEngine { if (!it.isLiveContent) it.seekBy(-SEEK_STEP_MS) }
            override fun onSkipToNext() = withEngine { it.next() }
            override fun onSkipToPrevious() = withEngine { it.previous() }
        })
    }

    /** Callbacks arrive on a binder thread; every engine here is main-thread-only. */
    private fun withEngine(block: (PlaybackEngine) -> Unit) {
        val e = engine ?: return
        scope.launch { runCatching { block(e) } }
    }

    // --- Audio focus ------------------------------------------------------------------------------

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> unduck()
            // Transient without ducking allowed by the *requester* — we still duck rather than pause
            // (see the class doc): losing a few seconds of a live stream costs more than a quiet moment.
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> duck()
            AudioManager.AUDIOFOCUS_LOSS -> {
                hasFocus = false
                unduck()
                withEngine { if (it.isPlaying.value) it.togglePlayPause() }
            }
            // AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK: the platform attenuates us, nothing to do.
        }
    }

    private val focusRequest by lazy {
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build(),
            )
            // false = let the platform duck us automatically instead of handing us a callback to pause on.
            .setWillPauseWhenDucked(false)
            .setOnAudioFocusChangeListener(focusListener)
            .build()
    }

    private fun requestFocus() {
        if (hasFocus) return
        val granted = runCatching { audioManager.requestAudioFocus(focusRequest) }
            .getOrDefault(AudioManager.AUDIOFOCUS_REQUEST_FAILED) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        // A refusal is not a reason to refuse to play: some TV builds deny focus to background-capable
        // apps and playing silently-unmanaged is still better than not playing.
        hasFocus = granted
    }

    private fun abandonFocus() {
        if (!hasFocus) return
        hasFocus = false
        runCatching { audioManager.abandonAudioFocusRequest(focusRequest) }
    }

    // --- Ducking ----------------------------------------------------------------------------------

    private fun duck() {
        val e = engine ?: return
        if (preDuckVolume != null) return
        val current = e.volume.value
        preDuckVolume = current
        val target = (current * DUCK_PERCENT / 100).coerceAtLeast(0)
        duckedTo = target
        withEngine { it.adjustVolume(target - current) }
    }

    private fun unduck() {
        val previous = preDuckVolume ?: return
        val ducked = duckedTo
        preDuckVolume = null
        duckedTo = null
        withEngine { e ->
            // If the volume is no longer where the duck left it, the user changed it while we were
            // ducked. That is a newer decision than the level saved before ducking, so restoring the old
            // one would silently undo it.
            if (ducked != null && e.volume.value != ducked) return@withEngine
            e.adjustVolume(previous - e.volume.value)
        }
    }

    private companion object {
        const val SESSION_TAG = "OwnTV"
        const val SEEK_STEP_MS = 30_000L
        /** How far down a manual duck goes — quiet enough to talk over, loud enough not to look broken. */
        const val DUCK_PERCENT = 25
    }
}
