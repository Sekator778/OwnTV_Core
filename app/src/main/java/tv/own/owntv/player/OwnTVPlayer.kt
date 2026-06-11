package tv.own.owntv.player

import android.content.Context
import android.view.Surface
import dev.jdtech.mpv.MPVLib
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import tv.own.owntv.core.network.HttpClient
import tv.own.owntv.features.settings.data.SettingsRepository
import java.util.Locale

/** A selectable audio/subtitle track ([mpvId] is the mpv track id used for `aid`/`sid`). */
data class TrackOption(val label: String, val mpvId: Int, val selected: Boolean)

/** Metadata shown in the player HUD (breadcrumb path, year, channel logo). */
data class MediaMeta(
    val title: String? = null,
    val subtitle: String? = null,
    val year: String? = null,
    val logoUrl: String? = null,
)

/** An item in a play queue (e.g. a season's episodes), for prev/next. */
data class PlaylistItem(val url: String, val meta: MediaMeta = MediaMeta())

/** Whether prev/next are available in the current queue. */
data class NavState(val hasPrev: Boolean, val hasNext: Boolean)

/** Video scaling modes exposed in the player's zoom menu. */
enum class ZoomMode(val label: String) {
    FIT("Fit Screen"), FILL("Fill / Crop"), STRETCH("Stretch"),
    ORIGINAL("Original (1:1)"), FORCE_16_9("Force 16:9"), FORCE_4_3("Force 4:3"),
}

/**
 * App-wide single libmpv player. mpv (FFmpeg) decodes virtually any codec/container and exposes every
 * audio/subtitle track — the right engine for IPTV (ExoPlayer only surfaced device-decodable tracks).
 * Also gives caching, playback speed, etc. State is published as StateFlows for the Compose HUD.
 */
class OwnTVPlayer(
    private val context: Context,
    private val settings: SettingsRepository,
) : MPVLib.EventObserver {

    private var mpv: MPVLib? = null
    private var initialized = false
    private var pendingSeekMs = 0L
    private var currentUrl: String? = null
    private var expectingPlayback = false
    private var playlist: List<PlaylistItem> = emptyList()
    private var playlistIndex = 0
    // mpv's android video output needs a surface at loadfile time, or it deselects video (audio-only).
    // So when no surface is attached yet we defer the load until attachSurface().
    private var surfaceAttached = false
    private var pendingUrl: String? = null
    private var hdrHint = true

    // Video Player Settings — cached so ensureInit can apply them as mpv options, and the observers
    // below apply changes live to a running player.
    private var hwDecoding = true
    private var subScale = 1.0
    private var audioDelaySec = 0.0
    private var prefAudioLang = ""
    private var prefSubLang = ""
    private var defaultZoom = ZoomMode.FIT

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    init {
        // Track the HDR setting; apply it live and re-apply on each load via ensureInit.
        settings.hdrEnabled.onEach { enabled ->
            hdrHint = enabled
            if (initialized) mpv?.setPropertyString("target-colorspace-hint", if (enabled) "yes" else "no")
        }.launchIn(scope)
        settings.hwDecoding.onEach { on ->
            hwDecoding = on
            if (initialized) mpv?.setPropertyString("hwdec", if (on) "auto-safe" else "no")
        }.launchIn(scope)
        settings.subtitleScale.onEach { s ->
            subScale = s.toDouble()
            if (initialized) mpv?.setPropertyDouble("sub-scale", subScale)
        }.launchIn(scope)
        settings.audioDelayMs.onEach { ms ->
            audioDelaySec = ms / 1000.0
            if (initialized) mpv?.setPropertyDouble("audio-delay", audioDelaySec)
        }.launchIn(scope)
        settings.preferredAudioLang.onEach { lang ->
            prefAudioLang = lang
            if (initialized && lang.isNotBlank()) mpv?.setPropertyString("alang", lang)
        }.launchIn(scope)
        settings.preferredSubLang.onEach { lang ->
            prefSubLang = lang
            if (initialized && lang.isNotBlank()) mpv?.setPropertyString("slang", lang)
        }.launchIn(scope)
        settings.defaultZoom.onEach { name ->
            defaultZoom = runCatching { ZoomMode.valueOf(name) }.getOrDefault(ZoomMode.FIT)
        }.launchIn(scope)
    }
    // Bumped on every load so a stale end-of-file error check can tell it's been superseded.
    private var loadGeneration = 0
    private var errorCheckJob: Job? = null

    private val _nav = MutableStateFlow(NavState(false, false))
    val nav: StateFlow<NavState> = _nav.asStateFlow()

    var currentTitle: String? = null
        private set
    var currentSubtitle: String? = null
        private set
    var currentYear: String? = null
        private set
    var currentLogoUrl: String? = null
        private set
    var isLiveContent: Boolean = false
        private set

    private var preMuteVolume = 100

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()
    private val _position = MutableStateFlow(0L)
    val position: StateFlow<Long> = _position.asStateFlow()
    private val _duration = MutableStateFlow(0L)
    val duration: StateFlow<Long> = _duration.asStateFlow()
    private val _buffering = MutableStateFlow(false)
    val buffering: StateFlow<Boolean> = _buffering.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    private val _volume = MutableStateFlow(100)
    val volume: StateFlow<Int> = _volume.asStateFlow()
    private val _videoRes = MutableStateFlow<String?>(null)
    val videoRes: StateFlow<String?> = _videoRes.asStateFlow()
    private val _audioCount = MutableStateFlow(0)
    val audioCount: StateFlow<Int> = _audioCount.asStateFlow()
    private val _subCount = MutableStateFlow(0)
    val subCount: StateFlow<Int> = _subCount.asStateFlow()
    private val _zoomMode = MutableStateFlow(ZoomMode.FIT)
    val zoomMode: StateFlow<ZoomMode> = _zoomMode.asStateFlow()
    private val _speed = MutableStateFlow(1.0)
    val speed: StateFlow<Double> = _speed.asStateFlow()

    val currentMediaUrl: String? get() = currentUrl

    private fun ensureInit() {
        if (initialized) return
        mpv = MPVLib.create(context)?.apply {
            setOptionString("vo", "gpu")
            setOptionString("gpu-context", "android")
            setOptionString("hwdec", if (hwDecoding) "auto-safe" else "no")
            setOptionString("ao", "audiotrack")
            setOptionString("force-window", "no")
            setOptionString("idle", "yes")
            setOptionString("ytdl", "no") // IPTV URLs are direct; skip the youtube-dl hook
            setOptionString("msg-level", "all=warn") // quieter logcat: drop mpv's verbose per-frame logs
            // Large demuxer cache so 4K/8K IPTV streams read ahead and don't stutter.
            setOptionString("cache", "yes")
            setOptionString("demuxer-max-bytes", "256MiB")
            setOptionString("demuxer-max-back-bytes", "64MiB")
            setOptionString("demuxer-readahead-secs", "60")
            setOptionString("cache-secs", "120")
            setOptionString("network-timeout", "60")
            setOptionString("user-agent", HttpClient.DEFAULT_USER_AGENT)
            setOptionString("sub-scale-with-window", "yes")
            setOptionString("sub-scale", subScale.toString())
            setOptionString("audio-delay", audioDelaySec.toString())
            if (prefAudioLang.isNotBlank()) setOptionString("alang", prefAudioLang)
            if (prefSubLang.isNotBlank()) setOptionString("slang", prefSubLang)
            // HDR passthrough: signal the source colorspace (incl. HDR10/HLG) to the display surface.
            setOptionString("target-colorspace-hint", if (hdrHint) "yes" else "no")
            init()
            observeProperty("time-pos", MPVLib.MpvFormat.MPV_FORMAT_INT64)
            observeProperty("duration", MPVLib.MpvFormat.MPV_FORMAT_INT64)
            observeProperty("pause", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
            observeProperty("paused-for-cache", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
            observeProperty("height", MPVLib.MpvFormat.MPV_FORMAT_INT64)
            observeProperty("speed", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
            addObserver(this@OwnTVPlayer)
        }
        initialized = mpv != null
    }

    /** Play a single item (movie / live channel) — clears any queue. [muted] is used by the live preview. */
    fun play(
        url: String,
        title: String? = null,
        subtitle: String? = null,
        year: String? = null,
        logoUrl: String? = null,
        isLive: Boolean = false,
        startPositionMs: Long = 0,
        muted: Boolean = false,
    ) {
        playlist = emptyList()
        playlistIndex = 0
        updateNav()
        _zoomMode.value = defaultZoom // start new content at the user's default zoom
        loadUrl(url, MediaMeta(title, subtitle, year, logoUrl), isLive, startPositionMs, muted)
    }

    /** Play a queue (a season's episodes) starting at [startIndex] — enables prev/next. */
    fun playEpisodes(items: List<PlaylistItem>, startIndex: Int, startPositionMs: Long = 0) {
        playlist = items
        playlistIndex = startIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))
        val item = items.getOrNull(playlistIndex) ?: return
        _zoomMode.value = defaultZoom
        loadUrl(item.url, item.meta, isLive = false, startPositionMs)
        updateNav()
    }

    fun next() {
        if (playlistIndex < playlist.size - 1) {
            playlistIndex++
            playCurrent()
        }
    }

    fun previous() {
        if (playlistIndex > 0) {
            playlistIndex--
            playCurrent()
        }
    }

    private fun playCurrent() {
        val item = playlist.getOrNull(playlistIndex) ?: return
        loadUrl(item.url, item.meta, isLive = false, 0)
        updateNav()
    }

    private fun updateNav() {
        _nav.value = NavState(playlistIndex > 0, playlistIndex < playlist.size - 1)
    }

    private fun loadUrl(url: String, meta: MediaMeta, isLive: Boolean, startPositionMs: Long, muted: Boolean = false) {
        ensureInit()
        currentTitle = meta.title
        currentSubtitle = meta.subtitle
        currentYear = meta.year
        currentLogoUrl = meta.logoUrl
        isLiveContent = isLive
        currentUrl = url
        loadGeneration++
        errorCheckJob?.cancel()
        _error.value = null
        _videoRes.value = null
        expectingPlayback = true
        pendingSeekMs = startPositionMs
        // Mute is a global mpv property, so set it now (applies whenever the file actually loads). The
        // live preview mutes; everything else plays with sound.
        mpv?.setPropertyBoolean("mute", muted)
        // Defer the actual loadfile until a surface exists, otherwise mpv inits video output with no
        // surface and falls back to audio-only. attachSurface() flushes the pending load.
        if (surfaceAttached) startLoad(url) else pendingUrl = url
    }

    private fun startLoad(url: String) {
        pendingUrl = null
        mpv?.command(arrayOf("loadfile", url))
        mpv?.setPropertyBoolean("pause", false)
        // mpv only fires the "pause" observer on a *change*; at startup pause is already false, so seed
        // the playing state here, otherwise the HUD shows PLAY while the stream is actually running.
        _isPlaying.value = true
    }

    fun togglePlayPause() {
        if (initialized) mpv?.command(arrayOf("cycle", "pause"))
    }

    fun seekBy(deltaMs: Long) {
        if (initialized) mpv?.command(arrayOf("seek", (deltaMs / 1000).toString(), "relative"))
    }

    fun setSpeed(speed: Double) {
        if (initialized) mpv?.setPropertyDouble("speed", speed)
        _speed.value = speed
    }

    // --- Volume (mpv software volume, independent of the system/hardware volume) ---
    fun setVolume(percent: Int) {
        val v = percent.coerceIn(0, 150)
        if (initialized) mpv?.setPropertyDouble("volume", v.toDouble())
        _volume.value = v
        if (v > 0) preMuteVolume = v
    }

    fun adjustVolume(delta: Int) = setVolume(_volume.value + delta)

    fun toggleMute() {
        if (_volume.value > 0) { preMuteVolume = _volume.value; setVolume(0) } else setVolume(preMuteVolume.coerceAtLeast(10))
    }

    // --- Zoom / aspect ---
    fun setZoomMode(mode: ZoomMode) {
        _zoomMode.value = mode
        val m = mpv ?: return
        // Reset, then apply the chosen mode's overrides.
        m.setPropertyString("video-unscaled", "no")
        m.setPropertyDouble("panscan", 0.0)
        when (mode) {
            ZoomMode.FIT -> { m.setPropertyString("keepaspect", "yes"); m.setPropertyString("video-aspect-override", "no") }
            ZoomMode.FILL -> { m.setPropertyString("keepaspect", "yes"); m.setPropertyString("video-aspect-override", "no"); m.setPropertyDouble("panscan", 1.0) }
            ZoomMode.STRETCH -> { m.setPropertyString("keepaspect", "no"); m.setPropertyString("video-aspect-override", "no") }
            ZoomMode.ORIGINAL -> { m.setPropertyString("keepaspect", "yes"); m.setPropertyString("video-aspect-override", "no"); m.setPropertyString("video-unscaled", "yes") }
            ZoomMode.FORCE_16_9 -> { m.setPropertyString("keepaspect", "yes"); m.setPropertyString("video-aspect-override", "16:9") }
            ZoomMode.FORCE_4_3 -> { m.setPropertyString("keepaspect", "yes"); m.setPropertyString("video-aspect-override", "4:3") }
        }
    }

    fun retry() {
        val url = currentUrl ?: return
        loadUrl(url, MediaMeta(currentTitle, currentSubtitle, currentYear, currentLogoUrl), isLiveContent, 0)
    }

    fun stop() {
        if (initialized) mpv?.command(arrayOf("stop"))
        currentUrl = null
        pendingUrl = null
        _isPlaying.value = false
    }

    fun release() {
        errorCheckJob?.cancel()
        scope.cancel()
        if (initialized) {
            mpv?.removeObserver(this)
            mpv?.destroy()
            mpv = null
            initialized = false
        }
    }

    // --- Surface (driven by the MpvVideoSurface view) ---
    fun attachSurface(surface: Surface) {
        ensureInit()
        mpv?.attachSurface(surface)
        mpv?.setOptionString("force-window", "yes")
        mpv?.setOptionString("vo", "gpu")
        surfaceAttached = true
        // Flush a load that was waiting for the surface (so video output inits correctly the first time).
        pendingUrl?.let { startLoad(it) }
    }

    fun setSurfaceSize(width: Int, height: Int) {
        if (initialized) mpv?.setPropertyString("android-surface-size", "${width}x$height")
    }

    fun detachSurface() {
        surfaceAttached = false
        if (!initialized) return
        mpv?.setPropertyString("vo", "null")
        mpv?.setOptionString("force-window", "no")
        mpv?.detachSurface()
    }

    // --- Tracks ---
    fun audioTracks(): List<TrackOption> = tracks("audio")
    fun textTracks(): List<TrackOption> = tracks("sub")

    private fun tracks(type: String): List<TrackOption> {
        if (!initialized) return emptyList()
        val m = mpv ?: return emptyList()
        val count = m.getPropertyInt("track-list/count") ?: 0
        val out = ArrayList<TrackOption>()
        for (i in 0 until count) {
            if (m.getPropertyString("track-list/$i/type") != type) continue
            val id = m.getPropertyInt("track-list/$i/id") ?: continue
            val title = m.getPropertyString("track-list/$i/title")
            val lang = m.getPropertyString("track-list/$i/lang")
            val selected = m.getPropertyBoolean("track-list/$i/selected") ?: false
            out.add(TrackOption(label(title, lang, id), id, selected))
        }
        return out
    }

    fun selectAudio(mpvId: Int) {
        if (initialized) mpv?.setPropertyInt("aid", mpvId)
    }

    fun selectSubtitle(mpvId: Int) {
        if (initialized) mpv?.setPropertyInt("sid", mpvId)
    }

    fun disableSubtitles() {
        if (initialized) mpv?.setPropertyString("sid", "no")
    }

    private fun label(title: String?, lang: String?, id: Int): String {
        val l = lang?.takeIf { it.isNotBlank() && it != "und" }
            ?.let { runCatching { Locale(it).displayLanguage }.getOrNull()?.ifBlank { it } ?: it }
        return listOfNotNull(title?.takeIf { it.isNotBlank() }, l).joinToString(" · ").ifBlank { "Track $id" }
    }

    // --- mpv event callbacks (called off the main thread) ---
    override fun eventProperty(property: String) {}

    override fun eventProperty(property: String, value: Long) {
        when (property) {
            "time-pos" -> {
                _position.value = value * 1000
                if (value > 0) expectingPlayback = false // playback actually started
            }
            "duration" -> _duration.value = value * 1000
            "height" -> _videoRes.value = resolutionLabel(value.toInt())
        }
    }

    private fun resolutionLabel(height: Int): String? = when {
        height <= 0 -> null
        height >= 2160 -> "4K"
        height >= 1440 -> "1440p"
        height >= 1080 -> "1080p"
        height >= 720 -> "720p"
        height >= 480 -> "480p"
        else -> "${height}p"
    }

    override fun eventProperty(property: String, value: Boolean) {
        when (property) {
            "pause" -> _isPlaying.value = !value
            "paused-for-cache" -> _buffering.value = value
        }
    }

    override fun eventProperty(property: String, value: String) {}
    override fun eventProperty(property: String, value: Double) {
        if (property == "speed") _speed.value = value
    }

    override fun event(eventId: Int) {
        when (eventId) {
            MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED -> {
                // The new file opened successfully — cancel any pending error and clear a stale one.
                expectingPlayback = false
                errorCheckJob?.cancel()
                _error.value = null
                _audioCount.value = audioTracks().size
                _subCount.value = textTracks().size
                mpv?.getPropertyBoolean("pause")?.let { _isPlaying.value = !it }
                mpv?.getPropertyInt("height")?.let { _videoRes.value = resolutionLabel(it) }
                setZoomMode(_zoomMode.value) // re-apply zoom on the new track
                if (pendingSeekMs > 0) {
                    mpv?.command(arrayOf("seek", (pendingSeekMs / 1000).toString(), "absolute"))
                    pendingSeekMs = 0
                }
            }
            // A file ended. This also fires for the *previous* item when we load a new one (next/prev),
            // so don't error immediately — wait briefly; if the new file loads, FILE_LOADED cancels this.
            MPVLib.MpvEvent.MPV_EVENT_END_FILE -> {
                if (expectingPlayback) {
                    val gen = loadGeneration
                    errorCheckJob?.cancel()
                    errorCheckJob = scope.launch {
                        delay(1500)
                        if (expectingPlayback && gen == loadGeneration) {
                            _error.value = "Couldn't play this stream. The source may be offline or use an unsupported format."
                        }
                    }
                }
            }
        }
    }
}
