package tv.own.owntv.player

import android.content.Context
import android.view.Surface
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import tv.own.owntv.core.network.HttpClient

/**
 * ExoPlayer engine for the Home screen hero preview.
 *
 * It is intentionally small: muted only, VOD start-position support, no HUD integration, and a single surface.
 * The Home screen keeps it alive while the hero is focused so the preview starts quickly and can be
 * reused across hero items without rebuilding the player each time.
 */
@UnstableApi
class HeroPreviewEngine(
    private val context: Context,
    private val streamingHttp: tv.own.owntv.core.network.StreamingHttpClient,
    /** True while another engine (mpv) already holds a stream — used for the one-session guard. */
    private val streamInUse: () -> Boolean = { false },
) {
    enum class State { IDLE, LOADING, PLAYING, ERROR }

    private var player: ExoPlayer? = null
    private var surface: Surface? = null
    private var hasStarted = false

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state.asStateFlow()

    var currentUrl: String? = null
        private set

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (currentUrl == null && playbackState != Player.STATE_IDLE) return
            when (playbackState) {
                Player.STATE_BUFFERING -> {
                    if (!hasStarted) _state.value = State.LOADING
                }
                Player.STATE_READY -> {
                    hasStarted = true
                }
                Player.STATE_ENDED -> {
                    // REPEAT_MODE_ONE should keep the current clip looping; this is just a fallback.
                }
                else -> Unit
            }
        }

        override fun onRenderedFirstFrame() {
            if (currentUrl != null) _state.value = State.PLAYING
        }

        override fun onPlayerError(error: PlaybackException) {
            android.util.Log.w(TAG, "Hero preview error: ${error.errorCodeName}", error)
            // HTTP 458 = "account session in use": the provider allows one stream at a time. Remember it
            // so every engine (Live preview included) stops competing for that single session (F19d).
            currentUrl?.let { url -> if (httpStatusOf(error) == 458) LiveStreamQuirks.rememberSessionLimit(url) }
            hasStarted = false
            currentUrl = null
            player?.run {
                stop()
                clearMediaItems()
            }
            _state.value = State.ERROR
        }
    }

    /** The HTTP status behind a load failure, following the cause chain Media3 wraps it in. */
    private fun httpStatusOf(error: Throwable?): Int? {
        var t = error
        var hops = 0
        while (t != null && hops++ < 8) {
            (t as? androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException)?.let { return it.responseCode }
            t = t.cause
        }
        return null
    }

    fun setSurface(s: Surface?) {
        surface = s
        if (s != null) player?.setVideoSurface(s) else player?.clearVideoSurface()
    }

    private var builtForUa: String = HttpClient.DEFAULT_USER_AGENT

    fun play(url: String, seekToMs: Long = 0L, userAgent: String? = null) {
        // One-session provider with its single stream already in use: previewing here would knock the
        // user's own playback off the air (or just fail with a 458). Stay on the poster instead (F19d) —
        // the same rule the Live preview pane follows.
        if (streamInUse() && LiveStreamQuirks.isSingleSession(url)) {
            stop()
            return
        }
        val effectiveUa = userAgent?.takeIf { it.isNotBlank() } ?: HttpClient.DEFAULT_USER_AGENT
        currentUrl = url
        val startPositionMs = seekToMs.coerceAtLeast(0L)
        hasStarted = false
        _state.value = State.LOADING
        runCatching {
            // Rebuild if UA changed (different source with different User-Agent).
            if (effectiveUa != builtForUa) { player?.release(); player = null }
            val p = player ?: build(effectiveUa).also { player = it; builtForUa = effectiveUa }
            surface?.let { p.setVideoSurface(it) }
            p.volume = 0f
            p.repeatMode = Player.REPEAT_MODE_ONE
            p.setMediaItem(MediaItem.fromUri(url), startPositionMs)
            p.prepare()
            p.playWhenReady = true
        }.onFailure {
            android.util.Log.w(TAG, "Hero preview play failed for ${HttpClient.redactUrl(url)}", it)
            hasStarted = false
            currentUrl = null
            player?.run {
                stop()
                clearMediaItems()
            }
            _state.value = State.ERROR
        }
    }

    fun stop() {
        currentUrl = null
        hasStarted = false
        _state.value = State.IDLE
        player?.run {
            stop()
            clearMediaItems()
        }
    }

    fun release() {
        player?.run {
            removeListener(listener)
            release()
        }
        player = null
        surface = null
        hasStarted = false
        currentUrl = null
        _state.value = State.IDLE
    }

    private fun build(ua: String = HttpClient.DEFAULT_USER_AGENT): ExoPlayer {
        val dataSource = OkHttpDataSource.Factory(streamingHttp.client).setUserAgent(ua)
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

    companion object {
        private const val TAG = "HeroPreviewEngine"
    }
}
