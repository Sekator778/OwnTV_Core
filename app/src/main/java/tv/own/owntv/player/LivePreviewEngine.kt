package tv.own.owntv.player

import android.content.Context
import android.view.Surface
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import tv.own.owntv.core.network.HttpClient

/**
 * ExoPlayer (Media3) that drives the muted **in-pane Live preview**. ExoPlayer starts HLS far faster than
 * mpv (which full-probes ~5 s before the first frame), so scrolling the channel list feels responsive.
 *
 * The **full** player stays on mpv (4K/HDR direct path, broad IPTV/raw-TS compatibility) — going fullscreen
 * [stop]s this engine and hands the channel to mpv. Preview and fullscreen use separate SurfaceViews on
 * separate screens, so the two decoders never share a surface. A single long-lived instance (Koin single),
 * like [OwnTVPlayer]; it's [stop]ped (not released) whenever the preview isn't on screen.
 *
 * All calls must be on the main thread (ExoPlayer is single-threaded): the VM invokes [play]/[stop]/
 * [setMuted] from the UI thread and the Compose surface invokes [setSurface] from the holder callback.
 */
@UnstableApi
class LivePreviewEngine(
    private val context: Context,
    private val okHttpClient: OkHttpClient,
) : PlaybackEngine {
    enum class State { IDLE, LOADING, PLAYING, ERROR }

    private var player: ExoPlayer? = null
    private var surface: Surface? = null
    private var muted: Boolean = true

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state.asStateFlow()
    private val _videoHeight = MutableStateFlow<Int?>(null)
    val videoHeight: StateFlow<Int?> = _videoHeight.asStateFlow()

    // --- PlaybackEngine: lets the full-screen HUD drive a promoted preview (play/pause, state, volume) ---
    private val _isPlaying = MutableStateFlow(false)
    override val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()
    private val _buffering = MutableStateFlow(false)
    override val buffering: StateFlow<Boolean> = _buffering.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    override val error: StateFlow<String?> = _error.asStateFlow()
    private val _videoRes = MutableStateFlow<String?>(null)
    override val videoRes: StateFlow<String?> = _videoRes.asStateFlow()
    private val _volume = MutableStateFlow(100)
    override val volume: StateFlow<Int> = _volume.asStateFlow()
    private val _zoomMode = MutableStateFlow(ZoomMode.FIT)
    override val zoomMode: StateFlow<ZoomMode> = _zoomMode.asStateFlow()
    override val audioCount: StateFlow<Int> = MutableStateFlow(0)
    override val subCount: StateFlow<Int> = MutableStateFlow(0)
    private val _currentMeta = MutableStateFlow(MediaMeta())
    override val currentMeta: StateFlow<MediaMeta> = _currentMeta.asStateFlow()
    override val isLiveContent: Boolean = true

    /** URL the preview is currently on (null when stopped) — lets the VM skip a redundant reload. */
    var currentUrl: String? = null
        private set

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_BUFFERING -> { _state.value = State.LOADING; _buffering.value = true }
                Player.STATE_READY -> { _state.value = State.PLAYING; _buffering.value = false }
                else -> _buffering.value = false // IDLE/ENDED handled by stop()/error
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) { _isPlaying.value = isPlaying }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (videoSize.height > 0) {
                _videoHeight.value = videoSize.height
                _videoRes.value = "${videoSize.height}p"
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            // A preview that ExoPlayer can't open just shows the channel logo; the full player (mpv) can
            // still play it. Not all IPTV streams (raw MPEG-TS, odd containers) are ExoPlayer-friendly.
            android.util.Log.w(TAG, "preview ExoPlayer error: ${error.errorCodeName}", error)
            _state.value = State.ERROR
            _isPlaying.value = false
            _buffering.value = false
            _error.value = "Couldn't play this channel."
        }
    }

    /** Attach the preview SurfaceView's surface, or null when it's destroyed. */
    fun setSurface(s: Surface?) {
        surface = s
        if (s != null) player?.setVideoSurface(s) else player?.clearVideoSurface()
    }

    /** Start (or switch to) [url] as a muted/unmuted preview. Never throws — a stream ExoPlayer can't set
     *  up just falls back to the channel logo (the full mpv player can still play it). [meta] populates the
     *  full-screen HUD title when this preview is promoted. */
    fun play(url: String, muted: Boolean, meta: MediaMeta = MediaMeta()) {
        this.muted = muted
        currentUrl = url
        _videoHeight.value = null
        _videoRes.value = null
        _error.value = null
        _currentMeta.value = meta
        _volume.value = if (muted) 0 else 100
        _state.value = State.LOADING
        _buffering.value = true
        runCatching {
            val p = player ?: build().also { player = it }
            surface?.let { p.setVideoSurface(it) }
            p.volume = if (muted) 0f else 1f
            p.setMediaItem(MediaItem.fromUri(url))
            p.prepare()
            p.playWhenReady = true
        }.onFailure {
            android.util.Log.w(TAG, "preview play() failed for $url", it)
            _state.value = State.ERROR
            _error.value = "Couldn't play this channel."
        }
    }

    fun setMuted(m: Boolean) {
        muted = m
        player?.volume = if (m) 0f else 1f
        _volume.value = if (m) 0 else 100
    }

    /** Stop playback and free the decoder/connection (e.g. before mpv takes over for fullscreen). Keeps the
     *  ExoPlayer instance alive for the next preview. */
    fun stop() {
        currentUrl = null
        _videoHeight.value = null
        _state.value = State.IDLE
        player?.run { stop(); clearMediaItems() }
    }

    fun release() {
        player?.run { removeListener(listener); release() }
        player = null
        surface = null
        currentUrl = null
        _state.value = State.IDLE
    }

    // --- PlaybackEngine controls (full-screen HUD) ---
    override fun togglePlayPause() {
        val p = player ?: return
        if (p.isPlaying) p.pause() else p.play()
    }

    override fun setZoomMode(mode: ZoomMode) { _zoomMode.value = mode } // surface scaling is Phase 3; renders FIT

    override fun adjustVolume(delta: Int) {
        val v = (_volume.value + delta).coerceIn(0, 100)
        _volume.value = v
        muted = v == 0
        player?.volume = v / 100f
    }

    override fun toggleMute() = setMuted(!muted)
    override fun retry() { currentUrl?.let { play(it, muted, _currentMeta.value) } }
    override fun selectAudio(id: Int) {}
    override fun selectSubtitle(id: Int) {}
    override fun disableSubtitles() {}
    override fun audioTracks(): List<TrackOption> = emptyList()
    override fun textTracks(): List<TrackOption> = emptyList()

    private fun build(): ExoPlayer {
        val dataSource = OkHttpDataSource.Factory(okHttpClient).setUserAgent(HttpClient.DEFAULT_USER_AGENT)
        // Shallow buffers — a preview only needs to start quickly, not buffer deep.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(2_000, 8_000, 1_000, 2_000)
            .build()
        return ExoPlayer.Builder(context)
            .setRenderersFactory(DefaultRenderersFactory(context))
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource))
            .setLoadControl(loadControl)
            .build()
            .apply { addListener(listener) }
    }

    companion object { private const val TAG = "LivePreviewEngine" }
}
