package tv.own.owntv.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * What the player HUD needs from "whichever engine is currently playing" — mpv ([OwnTVPlayer], via
 * [MpvPlaybackEngine]) or the ExoPlayer live engine ([LivePreviewEngine] when a Live preview is promoted to
 * full-screen). VOD-only controls (seek/speed/prev-next/position) have no-op defaults so a live engine need
 * only implement the live-relevant members.
 */
interface PlaybackEngine {
    val isPlaying: StateFlow<Boolean>
    val buffering: StateFlow<Boolean>
    val error: StateFlow<PlaybackFailure?>
    /** The structured underlying failure (plain reason • media spec • raw engine text), shown under the
     *  friendly message so users can report the real cause without adb/logcat. Null when none. */
    val errorInfo: StateFlow<ErrorInfo?> get() = NULL_ERROR
    /** Set while the engine is sitting out a wait the provider asked for (HTTP 429 + `Retry-After`) and
     *  will re-ask for the identical stream by itself. The HUD shows it as a spinner with a live countdown
     *  instead of an error screen. Null when nothing is pending. */
    val providerBackOff: StateFlow<ProviderBackOff?> get() = NO_BACKOFF
    val videoRes: StateFlow<String?>
    /** Up-to-4 mini stream chips (aspect · resolution · fps · audio) for the player top bar. */
    val streamChips: StateFlow<List<String>> get() = NO_CHIPS
    /** Re-check [streamChips] now. */
    fun refreshStreamChips() {}
    /** Bitrate is only ever displayed in the debug overlay — enable tracking only while it's open. */
    fun setBitrateTrackingEnabled(enabled: Boolean) {}
    /** Short label of the engine currently decoding ("MPV" / "EXO"), shown as the first top-bar chip.
     *  Null = don't show one. */
    val engineChip: StateFlow<String?> get() = NULL_STRING
    val volume: StateFlow<Int>
    val zoomMode: StateFlow<ZoomMode>
    val audioCount: StateFlow<Int>
    val subCount: StateFlow<Int>
    val currentMeta: StateFlow<MediaMeta>
    val isLiveContent: Boolean

    /** True while the engine decodes audio only (video output stopped to save power) — Audio Mode. */
    val audioOnly: StateFlow<Boolean> get() = FALSE_FLOW
    /** Stop the video decoder/output but keep audio playing at position (Audio Mode enter). No-op if
     *  already audio-only. Audio is uninterrupted — mpv drops the video track (`vid=no`), ExoPlayer
     *  releases its surface. */
    fun enterAudioOnly() {}
    /** Resume video output (Audio Mode exit → back to fullscreen/mini). No-op if not audio-only. */
    fun exitAudioOnly() {}

    fun togglePlayPause()
    fun setZoomMode(mode: ZoomMode)
    fun adjustVolume(delta: Int)
    fun toggleMute()
    fun retry()
    fun selectAudio(id: Int)
    fun selectSubtitle(id: Int)
    fun disableSubtitles()
    /** Attach + select an external subtitle file (OpenSubtitles/local). VOD only (mpv sub-add, or an
     *  ExoPlayer side-load re-prepare); a live engine ignores it (subtitle plan §3.4). */
    fun addExternalSubtitle(path: String, title: String, lang: String?) {}
    fun audioTracks(): List<TrackOption>
    fun textTracks(): List<TrackOption>

    /** Live technical readout (label → value) for the stream-info overlay — codec, resolution, fps, HDR,
     *  bitrate, decoder, audio, buffer, source. A snapshot; the overlay re-reads it periodically. */
    fun streamInfo(): List<StreamInfoRow> = emptyList()

    // VOD-only — sensible no-op / empty defaults for a live engine.
    val position: StateFlow<Long> get() = ZERO_LONG
    val duration: StateFlow<Long> get() = ZERO_LONG
    val speed: StateFlow<Double> get() = ONE_DOUBLE
    val nav: StateFlow<NavState> get() = NO_NAV
    /** Title of the next queued item (in-season next episode), for the HUD next-episode countdown card.
     *  Null when there is no next item — a live engine leaves it null. */
    val nextUpTitle: StateFlow<String?> get() = NULL_STRING
    /** In-player A/V-sync nudge (ms) — mpv only; an ExoPlayer engine leaves it at 0. */
    val audioDelayMs: StateFlow<Int> get() = ZERO_INT
    /** True when this engine can shift audio against video (mpv's `audio-delay`). ExoPlayer cannot, so
     *  the HUD hides the nudge there. mpv supports it on live too — provider A/V drift is real (F19e). */
    fun audioDelayAvailable(): Boolean = false
    /** Subtitle-timing offset (ms) for the ACTIVE subtitle — VOD only (subtitle plan §8). */
    val subDelayMs: StateFlow<Int> get() = ZERO_INT
    fun setSpeed(speed: Double) {}
    fun adjustAudioDelay(deltaMs: Int) {}
    fun adjustSubtitleDelay(deltaMs: Int) {}
    fun resetSubtitleDelay() {}
    /** True when timing adjustment applies to the active subtitle on this engine (plan §8.1). */
    fun subtitleTimingAvailable(): Boolean = false
    fun previous() {}
    fun next() {}
    fun seekBy(deltaMs: Long) {}
    /** HUD "Cancel" on the next-episode countdown — suppress the automatic advance for the current item. */
    fun cancelAutoNext() {}

    companion object {
        private val ZERO_INT: StateFlow<Int> = MutableStateFlow(0)
        private val ZERO_LONG: StateFlow<Long> = MutableStateFlow(0L)
        private val ONE_DOUBLE: StateFlow<Double> = MutableStateFlow(1.0)
        private val NO_NAV: StateFlow<NavState> = MutableStateFlow(NavState(hasPrev = false, hasNext = false))
        private val NULL_ERROR: StateFlow<ErrorInfo?> = MutableStateFlow(null)
        private val NO_BACKOFF: StateFlow<ProviderBackOff?> = MutableStateFlow(null)
        private val NO_CHIPS: StateFlow<List<String>> = MutableStateFlow(emptyList())
        private val NULL_STRING: StateFlow<String?> = MutableStateFlow(null)
        private val FALSE_FLOW: StateFlow<Boolean> = MutableStateFlow(false)
    }
}

/** Adapts the full mpv player to [PlaybackEngine] (delegation only — keeps [OwnTVPlayer] untouched). */
class MpvPlaybackEngine(private val p: OwnTVPlayer) : PlaybackEngine {
    override val isPlaying get() = p.isPlaying
    override val buffering get() = p.buffering
    override val error get() = p.error
    override val errorInfo get() = p.errorInfo
    override val videoRes get() = p.videoRes
    override val streamChips get() = p.streamChips
    override val engineChip get() = p.engineChip
    override val volume get() = p.volume
    override val zoomMode get() = p.zoomMode
    override val audioCount get() = p.audioCount
    override val subCount get() = p.subCount
    override val currentMeta get() = p.currentMeta
    override val isLiveContent get() = p.isLiveContent
    override val audioOnly get() = p.audioOnly
    override fun enterAudioOnly() = p.enterAudioOnly()
    override fun exitAudioOnly() = p.exitAudioOnly()
    override val position get() = p.position
    override val duration get() = p.duration
    override val speed get() = p.speed
    override val nav get() = p.nav
    override val nextUpTitle get() = p.nextUpTitle
    override val audioDelayMs get() = p.audioDelayMs
    override fun audioDelayAvailable() = true
    override val subDelayMs get() = p.subDelayMs
    override fun adjustSubtitleDelay(deltaMs: Int) = p.adjustSubtitleDelay(deltaMs)
    override fun resetSubtitleDelay() = p.resetSubtitleDelay()
    override fun subtitleTimingAvailable() = p.subtitleTimingAvailable()
    override fun togglePlayPause() = p.togglePlayPause()
    override fun setZoomMode(mode: ZoomMode) = p.setZoomMode(mode)
    override fun adjustVolume(delta: Int) = p.adjustVolume(delta)
    override fun toggleMute() = p.toggleMute()
    override fun retry() = p.retry()
    override fun selectAudio(id: Int) = p.selectAudio(id)
    override fun selectSubtitle(id: Int) = p.selectSubtitle(id)
    override fun disableSubtitles() = p.disableSubtitles()
    override fun addExternalSubtitle(path: String, title: String, lang: String?) = p.addExternalSubtitle(path, title, lang)
    override fun audioTracks() = p.audioTracks()
    override fun textTracks() = p.textTracks()
    override fun streamInfo() = p.streamInfo()
    override fun setBitrateTrackingEnabled(enabled: Boolean) = p.setBitrateTrackingEnabled(enabled)
    override fun refreshStreamChips() = p.refreshStreamChips()
    override fun setSpeed(speed: Double) = p.setSpeed(speed)
    override fun adjustAudioDelay(deltaMs: Int) = p.adjustAudioDelay(deltaMs)
    override fun previous() = p.previous()
    override fun next() = p.next()
    override fun seekBy(deltaMs: Long) = p.seekBy(deltaMs)
    override fun cancelAutoNext() = p.cancelAutoNext()
}
