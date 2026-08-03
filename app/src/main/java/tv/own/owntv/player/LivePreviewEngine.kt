package tv.own.owntv.player

import android.content.Context
import android.view.Surface
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import tv.own.owntv.features.settings.data.LiveBuffer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.hls.DefaultHlsExtractorFactory
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoFrameMetadataListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tv.own.owntv.core.network.HttpClient
import tv.own.owntv.core.network.StreamHeaders

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
    private val streamingHttp: tv.own.owntv.core.network.StreamingHttpClient,
    private val diagnostics: PlayerDiagnostics,
    settings: tv.own.owntv.features.settings.data.SettingsRepository,
    connectivity: tv.own.owntv.core.network.ConnectivityObserver,
) : PlaybackEngine {

    // Escape-hatch toggle (Settings → Video player → Diagnostics). When off, no live fps/bitrate
    // measuring runs on this engine — declared values only. Never affects the playback pipeline.
    @Volatile private var measuredStatsEnabled = true
    private val settingsFlow = settings.measuredStreamStats
    private val settingsScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main.immediate)
    // Live latency (#72): target live-edge offset in seconds; null = engine default (Balanced). Applied
    // as a MediaItem.LiveConfiguration on the next channel open.
    @Volatile private var liveBufferSecs: Int? = null
    // "Pre-buffer" (F07): global choice, plus the per-playlist override the current tune
    // was opened with (null = follow the global one). Both are read at [build] time — the load
    // control's durations are fixed when the player is constructed.
    @Volatile private var livePrerollSecs: Int = LiveBuffer.PREROLL_OFF
    @Volatile private var prerollOverrideSecs: Int? = null

    /** The buffering numbers the live [player] instance was actually constructed with. A LoadControl can't
     *  be changed afterwards, so this is what [play] compares against to decide on a rebuild. */
    @Volatile private var builtLoadControl: LiveBuffer.LoadControlMs? = null
    private fun effectivePrerollSecs(): Int = prerollOverrideSecs ?: livePrerollSecs
    enum class State { IDLE, LOADING, PLAYING, ERROR }

    init { LiveDiagnosticsLog.init(context) }

    private var player: ExoPlayer? = null
    /** Media3 independently sends Surface.setFrameRate hints unless explicitly disabled. Keep that path
     *  tied to OwnTV's AFR toggle too; the default preview/in-pane state must never switch the display. */
    @Volatile private var autoFrameRateEnabled = false
    /** Device memory budget, resolved once and reused across player rebuilds (see [build]). */
    private var playerBudget: PlayerBudget? = null
    private var surface: Surface? = null
    private var muted: Boolean = true
    // Volume-0 is NOT a reliable mute. When the TV/AVR declares AC3/E-AC3/DTS support, MediaCodecAudioRenderer
    // picks the passthrough "decoder" and the compressed 5.1 bitstream is forwarded to HDMI untouched —
    // AudioTrack.setVolume() has no effect on an IEC61937 stream, so those channels kept playing sound in a
    // "muted" preview while stereo AAC/MP3 channels muted correctly. So a muted preview also DESELECTS the
    // audio track type, which stops the renderer (and the passthrough sink) outright.
    // Exception: a stream with no video track at all (radio/audio-only) would then have nothing to render and
    // would stall the freeze watchdog — those keep the volume-0 path, which works for their PCM/stereo audio.
    private var audioTrackDisabled = false
    private var hasVideoTrack = true

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state.asStateFlow()
    private val _videoHeight = MutableStateFlow<Int?>(null)
    val videoHeight: StateFlow<Int?> = _videoHeight.asStateFlow()
    // PAR-corrected display aspect (w/h) + native pixel (w, h), used by ExoPreviewSurface's zoom/letterbox
    // sizing (see Modifier.videoZoom). Mirrors OwnTVPlayer._videoAspect/_videoSize so live-on-ExoPlayer
    // zooms identically to live-on-mpv / VOD.
    private val _videoAspect = MutableStateFlow<Float?>(null)
    val videoAspect: StateFlow<Float?> = _videoAspect.asStateFlow()
    private val _videoSize = MutableStateFlow<Pair<Int, Int>?>(null)
    val videoSize: StateFlow<Pair<Int, Int>?> = _videoSize.asStateFlow()
    // Up-to-4 mini stream chips for the preview pane / player top bar: aspect · resolution · fps · audio.
    private val _streamChips = MutableStateFlow<List<String>>(emptyList())
    override val streamChips: StateFlow<List<String>> = _streamChips.asStateFlow()
    // This engine IS ExoPlayer — static first chip for the fullscreen top bar.
    override val engineChip: StateFlow<String?> = MutableStateFlow("EXO")

    // --- PlaybackEngine: lets the full-screen HUD drive a promoted preview (play/pause, state, volume) ---
    private val _isPlaying = MutableStateFlow(false)
    override val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()
    private val _buffering = MutableStateFlow(false)
    override val buffering: StateFlow<Boolean> = _buffering.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    override val error: StateFlow<String?> = _error.asStateFlow()
    private val _errorInfo = MutableStateFlow<ErrorInfo?>(null)
    override val errorInfo: StateFlow<ErrorInfo?> = _errorInfo.asStateFlow()
    private val _videoRes = MutableStateFlow<String?>(null)
    override val videoRes: StateFlow<String?> = _videoRes.asStateFlow()
    private val _volume = MutableStateFlow(100)
    override val volume: StateFlow<Int> = _volume.asStateFlow()
    private val _zoomMode = MutableStateFlow(ZoomMode.FIT)
    override val zoomMode: StateFlow<ZoomMode> = _zoomMode.asStateFlow()
    private val _audioCount = MutableStateFlow(0)
    override val audioCount: StateFlow<Int> = _audioCount.asStateFlow()
    private val _subCount = MutableStateFlow(0)
    override val subCount: StateFlow<Int> = _subCount.asStateFlow()
    // Audio/text tracks enumerated from the active stream (multi-language live, or a VOD file added via M3U).
    private var audioTrackList: List<TrackOption> = emptyList()
    private var audioSelections: List<AudioSel> = emptyList()
    private var textTrackList: List<TrackOption> = emptyList()
    private var textSelections: List<TextSel> = emptyList()
    private data class AudioSel(val id: Int, val group: androidx.media3.common.TrackGroup, val trackIndex: Int)
    private data class TextSel(val id: Int, val group: androidx.media3.common.TrackGroup, val trackIndex: Int)
    // Subtitle cues + an "on" flag. The Compose surface mounts a SubtitleView ONLY while [subtitleOn] (else
    // any overlaid view knocks the SurfaceView off the hardware-overlay path and stutters 4K — same as VOD).
    private val _cues = MutableStateFlow<List<androidx.media3.common.text.Cue>>(emptyList())
    val cues: StateFlow<List<androidx.media3.common.text.Cue>> = _cues.asStateFlow()
    private val _subtitleOn = MutableStateFlow(false)
    val subtitleOn: StateFlow<Boolean> = _subtitleOn.asStateFlow()
    // True when the stream HAS audio but ExoPlayer can decode NONE of it (e.g. AC3/E-AC3/DTS on a device
    // without that decoder) — the VM hands such a stream to mpv (FFmpeg decodes everything) so it isn't silent.
    private val _audioUnsupported = MutableStateFlow(false)
    val audioUnsupported: StateFlow<Boolean> = _audioUnsupported.asStateFlow()
    // One-shot per load: audio/position is progressing normally and a video track exists, but ExoPlayer has
    // never rendered a single frame of it — the "audio plays, no picture" case the freeze/frame watchdogs
    // below can't see (they only catch a freeze AFTER frames were once seen, or a total position stall).
    // The VM observes this to try the existing mpv fallback once; legitimate audio-only streams never have
    // a video track, so they never set this.
    private val _noVideoDetected = MutableStateFlow(false)
    val noVideoDetected: StateFlow<Boolean> = _noVideoDetected.asStateFlow()
    private var noVideoTriggered = false
    private var readySinceMs = 0L
    // Set true once this tune is observed to be UHD (>1080p). Cheap panels (e.g. some Hisense) leak the 4K
    // hardware decoder if it's merely parked/reused (ExoPlayer's normal stop) instead of fully released —
    // every later channel then waits ~20 s for a decoder slot until the TV reboots. So when we LEAVE a UHD
    // channel via stop() (Back / exit fullscreen / background / leaving the list) we fully release+rebuild
    // the ExoPlayer so its MediaCodec is handed back cleanly. Deliberately NOT triggered from play(): that
    // path is also the preview-pane re-tune on every focus, and rebuilding there churns 4K previews and
    // pushes borderline streams into the mpv fallback. Scoped to UHD only — SD/HD keeps the fast reuse path.
    @Volatile private var sawUhd = false

    // Programmatic codec/audio errors (Reviewer: more reliable than logcat for ExoPlayer, and survives the
    // Android 14+ own-logcat lockdown). MediaCodec.CodecException.diagnosticInfo carries the exact code
    // (e.g. 0x80001000); AudioSink errors name the audio failure. Reset per load, preferred when present.
    @Volatile private var lastCodecError: String? = null
    @Volatile private var lastVideoDecoder: String? = null // e.g. "OMX.realtek.video.decoder", for the spec line
    private val throughputTracker = ThroughputTracker()
    private val fpsSample = FpsSample()
    private var dropsBaseline = 0

    init {
        // Keep the escape-hatch flag current; turning it off stops any in-flight measuring immediately.
        settingsFlow.onEach { measuredStatsEnabled = it; if (!it) throughputTracker.setEnabled(false) }
            .launchIn(settingsScope)
        // Detailed playback logging (F18). A release build used to have the live trace compiled off, so
        // the users who report provider-specific faults could produce nothing at all. The ring buffer
        // always runs; this decides whether it also reaches Logcat and the rolling file. A debug or
        // -PdiagnosticBuild build stays on regardless of the setting.
        settings.detailedDiagnostics.onEach {
            LiveDiagnosticsLog.enabled = it ||
                tv.own.owntv.BuildConfig.DEBUG || tv.own.owntv.BuildConfig.DIAGNOSTIC_BUILD
        }.launchIn(settingsScope)
        // Live latency no longer only sets a LiveConfiguration offset (which Media3 honours for HLS/DASH
        // and ignores for the raw MPEG-TS most Xtream live URLs are) — it now drives the LoadControl,
        // whose durations are fixed at construction. So a change while a channel is playing rebuilds,
        // exactly like the surround/hardware-decoding settings above. (F06)
        settings.liveBufferSeconds.onEach {
            val changed = liveBufferSecs != it
            liveBufferSecs = it
            if (changed) currentUrl?.let { _ -> rebuildForSettingChange() }
        }.launchIn(settingsScope)
        settings.livePrerollSecs.onEach {
            val changed = livePrerollSecs != it
            livePrerollSecs = it
            if (changed) currentUrl?.let { _ -> rebuildForSettingChange() }
        }.launchIn(settingsScope)
        // Surround mode only takes effect on the next player build (the audio sink's capabilities are
        // fixed at construction), so a change while a channel is playing rebuilds it — same as mpv's
        // in-place reload. Changing the setting also clears the session latch, which is handled by
        // whoever wrote the setting; here we only need the new value and a rebuild.
        settings.surroundMode.onEach { mode ->
            val changed = surroundMode != mode
            surroundMode = mode
            if (changed) currentUrl?.let { rebuildForSettingChange() }
        }.launchIn(settingsScope)
        // "Hardware decoding = Off" used to reach mpv only, which left Live TV — whose default engine is
        // this one — on the hardware decoder the user was trying to avoid. Rebuild so the new selector
        // takes effect; the factory is fixed at construction.
        settings.hwDecoding.onEach { on ->
            val changed = hwDecodingEnabled != on
            hwDecodingEnabled = on
            if (changed) currentUrl?.let { rebuildForSettingChange() }
        }.launchIn(settingsScope)
    }

    /** Mirrors Settings → Video player → Hardware decoding. Read at [build] time. */
    @Volatile private var hwDecodingEnabled = true

    /** The user's Auto / Stereo only / Surround choice. Read at [build] time. */
    @Volatile private var surroundMode: SurroundMode = SurroundMode.AUTO

    /**
     * Watches this engine's audio output for "accepted the format then played silence" and for a sink
     * that keeps underrunning. Polled from [progressWatchdog] — the tick that already runs whenever a
     * channel is up — so it adds no timer and cannot outlive the engine.
     */
    private val audioWatchdog = AudioWatchdog()
    private val analytics = object : androidx.media3.exoplayer.analytics.AnalyticsListener {
        override fun onVideoCodecError(eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime, videoCodecError: Exception) {
            lastCodecError = codecDetail("video", videoCodecError)
        }
        override fun onAudioCodecError(eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime, audioCodecError: Exception) {
            lastCodecError = codecDetail("audio", audioCodecError)
        }
        override fun onAudioSinkError(eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime, audioSinkError: Exception) {
            lastCodecError = "audio: ${audioSinkError.message ?: audioSinkError.javaClass.simpleName}"
        }
        override fun onVideoDecoderInitialized(eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
            lastVideoDecoder = decoderName
            dropsBaseline = currentDroppedFrames(player) // a new decoder session may start its own counters
        }

        /** Per-chunk failures, which is where a provider that won't serve its own live edge shows up —
         *  [onPlayerError] only sees the aggregate once Media3 has given up on the chunk. */
        override fun onLoadError(
            eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
            loadEventInfo: androidx.media3.exoplayer.source.LoadEventInfo,
            mediaLoadData: androidx.media3.exoplayer.source.MediaLoadData,
            error: java.io.IOException,
            wasCanceled: Boolean,
        ) {
            val code = httpStatusOf(error)
            LiveDiagnosticsLog.event(
                "load_error status=${code ?: -1} type=${error.javaClass.simpleName} " +
                    "dataType=${mediaLoadData.dataType} canceled=$wasCanceled " +
                    "uri=${HttpClient.redactUrl(loadEventInfo.uri.toString())}",
            )
            if (code == null || wasCanceled) return
            // Checked before the segment filter: the session limit is refused at the *manifest*, because
            // the panel never lets the request through to a stream at all.
            if (LiveStreamQuirks.isSessionLimit(code)) { noteSessionLimit(loadEventInfo.uri.toString()); return }
            if (mediaLoadData.dataType != C.DATA_TYPE_MEDIA) return // a bad manifest is a different failure
            if (!activeIsHls || !LiveStreamQuirks.isEdgeRefusal(code)) return
            noteSegmentRefusal(loadEventInfo.uri.toString(), code)
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

    /** "HEVC 1920x1080 • OMX.realtek.video.decoder" from the active stream, for the error screen's spec line. */
    private fun exoSpec(): String? {
        val f = player?.videoFormat
        val codec = f?.sampleMimeType?.substringAfterLast('/')?.let { mimeName(it) }
        val res = if (f != null && f.width > 0 && f.height > 0) "${f.width}x${f.height}" else null
        val head = listOfNotNull(codec, res).joinToString(" ").ifBlank { null }
        return listOfNotNull(head, lastVideoDecoder).joinToString(" • ").ifBlank { null }
    }
    private fun mimeName(m: String) = when (m.lowercase()) {
        "hevc" -> "HEVC"; "avc" -> "H.264"; "av01" -> "AV1"; "x-vnd.on2.vp9", "vp9" -> "VP9"
        "mp4v-es" -> "MPEG-4"; "mpeg2" -> "MPEG-2"; else -> m.uppercase()
    }
    private fun codecDetail(kind: String, e: Exception): String {
        (e as? android.media.MediaCodec.CodecException)?.let { return "$kind codec: ${it.diagnosticInfo}" }
        return "$kind codec: ${e.message ?: e.javaClass.simpleName}"
    }

    private var activeIsHls = false
    /** Actual media-source route for the current load, including runtime `.ts` -> HLS detection. */
    val isHlsStream: Boolean get() = activeIsHls
    /** The top-level request ended at an HLS manifest even though the submitted URL looked like raw TS. */
    @Volatile private var responseWasHls = false
    private var forceHlsForCurrentLoad = false
    private var redirectedHlsRetryDone = false
    /** Distinct live segments this load has been refused — the evidence behind [segmentsRefused]. */
    private val refusedSegments = mutableSetOf<String>()
    private val _segmentsRefused = MutableStateFlow(false)
    /** The provider refuses its own signed segment URLs; ExoPlayer cannot recover, mpv can. One-shot
     *  per load, collected by the ViewModel exactly like [noVideoDetected]. */
    val segmentsRefused: StateFlow<Boolean> = _segmentsRefused
    /** This load was refused because the account's one session is still held (HTTP 458), and whether the
     *  single wait-and-retry that answers it has already been spent. */
    private var sessionLimitSeen = false
    private var sessionLimitRetryDone = false
    /** The playlist shape is logged once per prepare (and again whenever we back off). */
    private var playlistLogged = false

    /** Technical readout for the stream-info overlay, from the active ExoPlayer formats. */
    override fun streamInfo(): List<Pair<String, String>> {
        val p = player ?: return emptyList()
        val out = ArrayList<Pair<String, String>>()
        out += "Engine" to "ExoPlayer"
        out += "Format" to if (activeIsHls) "HLS" else "MPEG-TS"
        p.videoFormat?.let { f ->
            val line = listOfNotNull(
                f.sampleMimeType?.substringAfterLast('/')?.let { mimeName(it) },
                if (f.width > 0 && f.height > 0) "${f.width}×${f.height}" else null,
                displayFps(f)?.let { "%.2f fps".format(it) },
            ).joinToString(" · ")
            if (line.isNotBlank()) out += "Video" to line
            when (f.colorInfo?.colorTransfer) {
                C.COLOR_TRANSFER_ST2084 -> "HDR10 (PQ)"; C.COLOR_TRANSFER_HLG -> "HLG"; else -> null
            }?.let { out += "HDR" to it }
            out += bitrateRow(f, throughputTracker)
        }
        // The real decoder name, not an assumption: with "Hardware decoding" off this engine now puts
        // software decoders first, so a hard-coded "(hardware)" would have been actively misleading.
        out += "Decoder" to (lastVideoDecoder ?: if (hwDecodingEnabled) "ExoPlayer (hardware)" else "ExoPlayer (software preferred)")
        p.audioFormat?.let { f ->
            val line = listOfNotNull(
                f.sampleMimeType?.substringAfterLast('/')?.uppercase(),
                when (f.channelCount) { 1 -> "mono"; 2 -> "stereo"; 6 -> "5.1"; 8 -> "7.1"; else -> null },
                if (f.sampleRate > 0) "%.0f kHz".format(f.sampleRate / 1000.0) else null,
            ).joinToString(" · ")
            if (line.isNotBlank()) out += "Audio" to line
        }
        // Which side of the HDMI cable is decoding, and whether the safety net has already fired. This is
        // the only way to verify a surround setup without a receiver that shows its own input format:
        // "passthrough" means the TV/receiver is decoding, "decoded" means OwnTV is and the sink gets PCM.
        out += "Audio out" to buildString {
            append(if (audioWatchdog.passthrough) "passthrough (TV/receiver decodes)" else "decoded in app")
            append(" · ")
            append(if (AudioOutputPolicy.allowsMultichannel(surroundMode)) "multichannel allowed" else "stereo only")
            AudioOutputPolicy.latchReason?.let { append(" · fell back: $it") }
        }
        bufferRow(p, dropsBaseline)?.let { out += it }
        // The settings that shaped the buffer above, as this player was ACTUALLY built with them — the only
        // way to tell "Live latency / Pre-buffer didn't apply" from "it applied and the buffer filled that
        // fast" without a working logcat. Worded as an amount of video, never as a wait: "start after 10s"
        // read as a ten-second countdown and made a working setting look broken.
        builtLoadControl?.let { lc ->
            out += "Live buffer" to buildString {
                val preroll = effectivePrerollSecs()
                append(if (preroll > 0) "pre-buffer ${preroll}s of video" else "pre-buffer off")
                append(" · depth ${lc.minBufferMs / 1000}s")
                prerollOverrideSecs?.let { append(" · playlist override") }
            }
        }
        currentUrl?.let { out += "Source" to HttpClient.redactUrl(it) }
        return out
    }
    /** Recompute the preview's mini chips (aspect · resolution · fps · audio · bitrate) from the active
     *  formats. Bitrate is the declared [Format.bitrate] only — measuring live throughput on every
     *  preview stream drags 4K playback, so the chip stays blank for raw MPEG-TS (the debug overlay
     *  still shows a measured value when opened). */
    private fun updateStreamChips() {
        val p = player ?: run { _streamChips.value = emptyList(); _videoFps.value = null; return }
        val chips = ArrayList<String>(5)
        p.videoFormat?.let { f ->
            if (f.width > 0 && f.height > 0) aspectLabel(f.width, f.height)?.let { chips += it }
            qualityLabel(f.width, f.height)?.let { chips += it }
            displayFps(f)?.let { chips += "${Math.round(it)} FPS" }
            f.bitrate.takeIf { it > 0 }?.let { chips += "%.1f Mbps".format(it / 1_000_000.0) }
        }
        p.audioFormat?.let { f ->
            (when (f.channelCount) { 1 -> "MONO"; 2 -> "STEREO"; 6 -> "5.1"; 8 -> "7.1"; else -> null })?.let { chips += it }
        }
        _streamChips.value = chips
        // Publish the frame rate for the auto-frame-rate switcher too (mpv's OwnTVPlayer has its own
        // videoFps flow; this is the ExoPlayer live equivalent). Declared Format.frameRate when the
        // stream carries one, otherwise the measured sample.
        _videoFps.value = p.videoFormat?.let { displayFps(it) }?.takeIf { it > 0f }
    }

    private val _videoFps = MutableStateFlow<Float?>(null)
    /** Video frame rate of the current live stream, or null while unknown. */
    val videoFps: StateFlow<Float?> = _videoFps.asStateFlow()

    private fun displayFps(f: Format) = f.frameRate.takeIf { it > 0 } ?: fpsSample.lastFps

    override fun refreshStreamChips() = ensureFpsMeasurement()
    override fun setBitrateTrackingEnabled(enabled: Boolean) = throughputTracker.setEnabled(enabled && measuredStatsEnabled)

    private fun ensureFpsMeasurement() {
        // F14: "Measured stream stats" is presented as a *performance* escape hatch, but Auto frame rate
        // depends on this measurement — raw MPEG-TS almost never declares Format.frameRate, so with the
        // stats toggle off `videoFps` stayed null and AFR silently did nothing while appearing enabled.
        // The measurement is cheap and bounded (FPS_MAX_ATTEMPTS samples, then it stops), so AFR keeps it
        // alive on its own; only the continuous throughput/bitrate tracking follows the stats toggle.
        if (!measuredStatsEnabled && !autoFrameRateEnabled) return
        if ((player?.videoFormat?.frameRate ?: 0f) <= 0f) restartFpsMeasurement()
    }

    private fun aspectLabel(w: Int, h: Int): String? {
        if (w <= 0 || h <= 0) return null
        val r = w.toFloat() / h
        return when {
            r in 1.72f..1.82f -> "16:9"
            r in 1.28f..1.40f -> "4:3"
            r >= 2.15f -> "21:9"
            r in 1.55f..1.66f -> "16:10"
            else -> "%.2f:1".format(r)
        }
    }
    private fun qualityLabel(w: Int, h: Int): String? = classifyResolution(w, h)

    private val _currentMeta = MutableStateFlow(MediaMeta())
    override val currentMeta: StateFlow<MediaMeta> = _currentMeta.asStateFlow()
    override val isLiveContent: Boolean = true

    /** URL the preview is currently on (null when stopped) — lets the VM skip a redundant reload. */
    var currentUrl: String? = null
        private set

    /**
     * Reconnect URL provider — set ONLY when the current item's URL is short-lived and must be
     * re-minted on reconnect (Stalker live, plan §5.4.1). Before a reconnect/HUD-retry reload, the
     * engine awaits this; a null provider or null result → replay [currentUrl] as-is (M3U/Xtream,
     * whose URLs are stable). Installed/cleared by `LiveViewModel` on each tune.
     */
    @Volatile var reconnectUrlProvider: tv.own.owntv.core.stalker.ReconnectUrlProvider? = null


    // Live auto-reconnect: a channel that DID play and then errors/stalls (provider hiccup / Wi-Fi blip)
    // re-fetches from the live edge instead of dead-ending. A channel that NEVER opened keeps the old
    // ERROR (so the VM falls back to mpv). retryCount resets whenever playback goes healthy again.
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    /** Scope for the reconnect URL-provider (awaiting its suspend freshUrl() off-main, then reloading on main). */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var hasPlayed = false
    private var retryCount = 0
    /** One decoder rebuild+retry per load — see [rebuildDecoderAndRetry]. */
    private var decoderRetryDone = false
    // Set just before our own stop()/release() touches the player, so the STATE_IDLE that follows is
    // recognized as a clean, self-caused cancellation rather than an unexpected mid-live drop.
    private var stoppingIntentionally = false
    // A single failed prepare() fires both onPlayerError AND the STATE_IDLE that follows it — without this
    // guard each one called reconnect() independently and burned two retryCount slots for one real failure.
    // Set true while a reconnect's delayed re-prepare is scheduled/running; cleared right before that
    // prepare() call so the NEXT genuine failure (from that prepare) is free to trigger its own reconnect.
    private var reconnectPending = false
    // Set true once retryCount is exhausted and we've surfaced the terminal error; stops the stallWatchdog
    // from re-arming and stops error/IDLE from calling reconnect() again until a fresh play()/retry().
    private var gaveUp = false
    private val stallWatchdog = Runnable { reconnect("buffering stalled") }
    // A STATE_READY on its own is not recovery — a feed that flaps READY→stall→READY every few seconds
    // used to zero retryCount on each blip, so the ladder never advanced and never gave up. The count is
    // only cleared once playback has held for [HEALTHY_MS]; any reconnect cancels this.
    private val healthyReset = Runnable {
        if (retryCount > 0) LiveDiagnosticsLog.event("playback healthy for ${HEALTHY_MS}ms — reconnect ladder reset")
        retryCount = 0
    }

    // Auto-resume after the ladder is spent. The ladder covers ~2 minutes of blind retrying, which is as
    // far as guessing usefully goes — a longer ladder would only make a genuinely dead provider take
    // longer to report. Past that we stop guessing and wait to be told: when the network comes back,
    // resume the channel we were parked on. An outage of any length then recovers by itself, while a
    // provider outage (network never dropped, so nothing fires here) still surfaces its error.
    init {
        connectivity.isOnline
            .onEach { online -> if (online) onNetworkRestored() }
            .launchIn(settingsScope)
    }

    /**
     * The network came back. Only act when a live channel is sitting on the terminal "Lost connection"
     * state — anything else is either already playing, already recovering, or was stopped on purpose,
     * and must not be restarted behind the user's back.
     */
    private fun onNetworkRestored() {
        if (!gaveUp || currentUrl == null || !hasPlayed || stoppingIntentionally) return
        LiveDiagnosticsLog.event("network restored — resuming the channel the ladder gave up on")
        gaveUp = false
        retryCount = 0
        _error.value = null; _errorInfo.value = null
        _state.value = State.LOADING; _buffering.value = true
        reconnect("network restored")
    }

    // Silent-freeze watchdog. A live HLS feed can keep ExoPlayer in STATE_READY with the playback CLOCK
    // still advancing — no buffering event, no onPlayerError — while the video renderer has stopped
    // producing frames (a provider encoder/codec hiccup, a stale/empty segment, a mid-stream codec change).
    // That looks exactly like a frozen channel with "nothing happening", and a position-only watchdog misses
    // it because currentPosition keeps marching with the timeline. So we also tick a counter on every frame
    // actually rendered to the surface (VideoFrameMetadataListener, wired in build()); if frames stop while
    // ExoPlayer insists it's playing, the feed is dead → reconnect. Position-stall (a fully dead feed where
    // even the clock stopped) is kept as a second trigger; audio-only channels have no video frames, so they
    // rely on that position trigger alone.
    private val frameCounter = java.util.concurrent.atomic.AtomicInteger(0)
    private var lastFrameCount = 0
    // Latched true once we've seen ANY rendered frame this load. Frame-based freeze detection only fires
    // AFTER this — so if the per-frame hook silently failed to register (or a stream renders no video at
    // all), we never false-trigger a reconnect on healthy playback; we fall back to the position check.
    private var everRendered = false
    private var videoRenderer: Renderer? = null
    private val frameListener = VideoFrameMetadataListener { _, _, _, _ -> frameCounter.incrementAndGet() }
    private var fpsAttempts = 0
    private val fpsFastRefresh: Runnable = Runnable {
        val fresh = player?.let { fpsSample.peek(it) }
        fpsAttempts++
        // Retry until a reading matches a standard rate, capped so a genuinely unusual one doesn't retry forever.
        val done = fpsAttempts >= FPS_MAX_ATTEMPTS || (fpsAttempts >= 2 && fpsSample.confident)
        if (done) {
            fresh?.let { fpsSample.publish(it) }
            updateStreamChips()
        } else {
            mainHandler.postDelayed(fpsFastRefresh, FPS_TICK_MS)
        }
    }
    private fun restartFpsMeasurement() {
        mainHandler.removeCallbacks(fpsFastRefresh)
        fpsSample.resetWindow() // keeps the old reading visible until replaced
        fpsAttempts = 0
        mainHandler.postDelayed(fpsFastRefresh, FPS_BASELINE_MS)
    }
    private var lastProgressPos = -1L
    private var lastProgressWallMs = 0L // SystemClock.elapsedRealtime() of the last forward position move
    private var frozenChecks = 0
    private val progressWatchdog = object : Runnable {
        override fun run() {
            val p = player
            // Gate on INTENT to play (playWhenReady && STATE_READY), NOT isPlaying. isPlaying drops to false
            // during transient playback suppression and brief internal stalls WITHOUT entering STATE_BUFFERING
            // or STATE_ERROR — and the old gate then reset the freeze counter every poll, so a real frozen-
            // but-"ready" channel was never caught (no spinner / reconnect / error). playWhenReady stays true
            // through those flickers, which is exactly the "should be advancing but isn't" condition we want.
            if (p != null && hasPlayed && p.playWhenReady && p.playbackState == Player.STATE_READY) {
                val now = android.os.SystemClock.elapsedRealtime()
                val frames = frameCounter.get()
                val hasVideo = p.videoFormat != null
                if (frames > 0) everRendered = true
                val pos = p.currentPosition
                val posAdvanced = pos > 0 && pos != lastProgressPos
                if (posAdvanced) { lastProgressPos = pos; lastProgressWallMs = now }
                else if (lastProgressWallMs == 0L) lastProgressWallMs = now // seed on the first ready poll
                if (LiveDiagnosticsLog.enabled) {
                    val liveOffset = p.currentLiveOffset.takeUnless { it == C.TIME_UNSET }
                    val dropped = (currentDroppedFrames(p) - dropsBaseline).coerceAtLeast(0)
                    LiveDiagnosticsLog.event(
                        "health posMs=$pos bufferMs=${p.totalBufferedDuration} liveOffsetMs=${liveOffset ?: -1} " +
                            "frames=$frames dropped=$dropped isPlaying=${p.isPlaying}",
                    )
                }
                // Audio output health. Runs in EVERY surround mode including "Surround" — a user who asked
                // for 5.1 did not ask for silence — and cannot be turned off. On a hit the session latches
                // to stereo (which every engine reads) and this channel is rebuilt on a stereo-only sink.
                audioWatchdog.poll(p.isPlaying)?.let { reason ->
                    LiveDiagnosticsLog.event("audioWatchdog: $reason — forcing stereo for this session")
                    AudioOutputPolicy.latchStereo("exo/live: $reason")
                    PlaybackErrorLog.event(context, "ExoPlayer", live = true, what = "Switched to stereo audio", detail = reason)
                    onAudioFallback?.invoke("Your TV or soundbar couldn't play this audio — switched to stereo.")
                    rebuildForSettingChange()
                    return
                }
                // Audio-plays-no-video: a video track exists but has never rendered a single frame, even
                // though we're not in the total-freeze case above (position/audio clock IS advancing). Only
                // fires once per load so the VM's one-shot mpv fallback isn't retriggered after it acts.
                if (!_audioOnly.value && !noVideoTriggered && hasVideo && !everRendered && now - readySinceMs >= NO_VIDEO_TIMEOUT_MS) {
                    noVideoTriggered = true
                    LiveDiagnosticsLog.event("progressWatchdog: no video frame after ${now - readySinceMs}ms (pos=$pos advancing, video track present)")
                    _noVideoDetected.value = true
                }
                // Backstop: zero forward progress for the whole window while we intend to play == a dead feed.
                // Wall-clock based, so it CAN'T be missed by isPlaying flicker or a non-functional frame hook.
                val noProgressMs = now - lastProgressWallMs
                if (noProgressMs >= FREEZE_TIMEOUT_MS) {
                    LiveDiagnosticsLog.event("progressWatchdog: no-progress detected for ${noProgressMs}ms (pos=$pos, state=READY, frameHook=$everRendered)")
                    frozenChecks = 0
                    reconnect("stream frozen — no progress ${noProgressMs}ms"); return
                }
                // Picture frozen but the live clock still advances (position moving) — only the rendered-frame
                // count can see this. Guarded by everRendered so a non-functional frame hook can't false-fire.
                // In Audio Mode the surface is intentionally detached, so no frames render and the count
                // sits still — that's expected, not a frozen picture. Skip the frame-based freeze check;
                // the position/no-progress backstop above still catches a genuinely dead feed.
                val framesStuck = !_audioOnly.value && everRendered && hasVideo && frames == lastFrameCount
                lastFrameCount = frames
                if (framesStuck) {
                    if (++frozenChecks >= FROZEN_LIMIT) {
                        LiveDiagnosticsLog.event("progressWatchdog: picture frozen, frames stuck at $frames for $frozenChecks polls (pos still advancing)")
                        frozenChecks = 0
                        reconnect("picture frozen"); return
                    }
                } else {
                    frozenChecks = 0
                }
            } else {
                frozenChecks = 0; lastProgressPos = -1L; lastProgressWallMs = 0L
            }
            mainHandler.postDelayed(this, PROGRESS_CHECK_MS)
        }
    }

    /** One diagnostic line per ExoPlayer state transition — never includes the stream URL. */
    private fun logStateChange(playbackState: Int) {
        val name = when (playbackState) {
            Player.STATE_IDLE -> "IDLE"; Player.STATE_BUFFERING -> "BUFFERING"
            Player.STATE_READY -> "READY"; Player.STATE_ENDED -> "ENDED"; else -> "UNKNOWN($playbackState)"
        }
        val p = player
        LiveDiagnosticsLog.event(
            "state_changed state=$name playWhenReady=${p?.playWhenReady} isPlaying=${p?.isPlaying} " +
                "pos=${p?.currentPosition} buffered=${p?.bufferedPosition} hasPlayed=$hasPlayed " +
                "isLiveContent=$isLiveContent buffering=${_buffering.value} reconnect=$retryCount/$MAX_RECONNECTS"
        )
    }

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            logStateChange(playbackState)
            when (playbackState) {
                Player.STATE_BUFFERING -> {
                    _state.value = State.LOADING; _buffering.value = true
                    // After it has played, a long buffer == a dropped feed → reconnect (live streams don't
                    // resume on their own here). Before first play, leave initial load alone.
                    if (hasPlayed && !gaveUp) {
                        LiveDiagnosticsLog.event("stallWatchdog armed (${STALL_MS}ms)")
                        mainHandler.removeCallbacks(stallWatchdog); mainHandler.postDelayed(stallWatchdog, STALL_MS)
                    }
                }
                Player.STATE_READY -> {
                    val resumed = hasPlayed // a READY after first play == recovered from a buffer/stall
                    _state.value = State.PLAYING; _buffering.value = false
                    hasPlayed = true; mainHandler.removeCallbacks(stallWatchdog)
                    if (activeIsHls && !playlistLogged) { playlistLogged = true; logHlsPlaylist("ready") }
                    // Recovery is measured, not assumed: arm the ladder reset and let it fire only if this
                    // READY actually holds (see [healthyReset]).
                    mainHandler.removeCallbacks(healthyReset); mainHandler.postDelayed(healthyReset, HEALTHY_MS)
                    if (resumed) LiveDiagnosticsLog.event("playing — READY, spinner cleared, stallWatchdog cancelled")
                    // (re)start the silent-freeze poll now that we're actually playing. Reset the frame
                    // baseline so the freeze window is measured from this READY (a healthy stream renders its
                    // first frame well within the grace window; one that never does trips the watchdog).
                    frameCounter.set(0); lastFrameCount = 0; everRendered = false; lastProgressPos = -1L; lastProgressWallMs = 0L; frozenChecks = 0
                    readySinceMs = android.os.SystemClock.elapsedRealtime(); noVideoTriggered = false
                    mainHandler.removeCallbacks(progressWatchdog); mainHandler.postDelayed(progressWatchdog, PROGRESS_CHECK_MS)
                    ensureFpsMeasurement()
                }
                Player.STATE_ENDED -> {
                    // A live HLS feed shouldn't legitimately "end" — this is a stall/hiccup (e.g. a stray
                    // EXT-X-ENDLIST from a provider glitch, or a momentarily empty playlist), not a real
                    // terminal state. Only react once we've actually been playing; before that, leave it
                    // alone (mirrors the pre-fix behavior so a channel that never opens still falls through
                    // to onPlayerError / the VM's mpv fallback instead of looping reconnects forever).
                    mainHandler.removeCallbacks(stallWatchdog)
                    when {
                        !hasPlayed -> {
                            LiveDiagnosticsLog.event("STATE_ENDED before first play — no action")
                            _buffering.value = false
                        }
                        reconnectPending || gaveUp -> _buffering.value = true
                        else -> {
                            LiveDiagnosticsLog.event("STATE_ENDED mid-live — treating as stall, reconnecting")
                            _buffering.value = true
                            reconnect("ended mid-live")
                        }
                    }
                }
                Player.STATE_IDLE -> {
                    mainHandler.removeCallbacks(stallWatchdog)
                    when {
                        stoppingIntentionally -> {
                            LiveDiagnosticsLog.event("STATE_IDLE — clean cancellation (stop/release/back)")
                            stoppingIntentionally = false
                            _buffering.value = false
                        }
                        reconnectPending || gaveUp -> _buffering.value = true
                        hasPlayed -> {
                            // Unexpected IDLE while we still intend to be on a live channel — same
                            // dead-end this fix targets, just via STATE_IDLE instead of STATE_ENDED.
                            LiveDiagnosticsLog.event("STATE_IDLE unexpected mid-live — treating as stall, reconnecting")
                            _buffering.value = true
                            reconnect("idle mid-live")
                        }
                        else -> {
                            LiveDiagnosticsLog.event("STATE_IDLE before first play — no action")
                            _buffering.value = false
                        }
                    }
                }
                else -> { _buffering.value = false; mainHandler.removeCallbacks(stallWatchdog) }
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) { _isPlaying.value = isPlaying }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (videoSize.height > 0) {
                _videoHeight.value = videoSize.height
                _videoRes.value = "${videoSize.height}p"
                if (videoSize.height > 1080) sawUhd = true // mark UHD → full decoder release on leave
            }
            if (videoSize.width > 0 && videoSize.height > 0) {
                // Aspect for zoom/letterbox sizing (PAR-corrected), + native pixel size for Original (1:1).
                _videoAspect.value =
                    (videoSize.width.toFloat() * videoSize.pixelWidthHeightRatio) / videoSize.height.toFloat()
                _videoSize.value = videoSize.width to videoSize.height
            }
            updateStreamChips()
            ensureFpsMeasurement()
        }

        override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
            rebuildTracks(tracks); updateStreamChips(); ensureFpsMeasurement()
        }
        override fun onCues(cueGroup: androidx.media3.common.text.CueGroup) { _cues.value = cueGroup.cues }

        override fun onPlayerError(error: PlaybackException) {
            android.util.Log.w(LiveDiagnosticsLog.TAG, "ExoPlayer error: ${error.errorCodeName}", error)
            LiveDiagnosticsLog.event("player_error code=${error.errorCodeName} hasPlayed=$hasPlayed")
            // mid-stream drop → reconnect, unless a reconnect from the SAME failed prepare is already
            // in flight (ExoPlayer often fires this alongside a STATE_IDLE for one physical failure) or
            // we've already exhausted retries and are waiting on the user/a fresh play().
            if (hasPlayed && !reconnectPending && !gaveUp) {
                val hlsHttpFailure = activeIsHls && error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
                reconnect("error ${error.errorCodeName}", fastHlsHttpRecovery = hlsHttpFailure)
                return
            }
            if (hasPlayed) return
            // Some Xtream panels advertise a `.ts` endpoint but HTTP-redirect it to an `.m3u8` manifest.
            // Content type selection happened before that redirect, so the progressive extractor sees
            // `#EXTM3U` and reports an unsupported container. Retry the SAME URL through HlsMediaSource;
            // OkHttp follows the redirect again, now with the correct manifest/segment parser.
            if (!redirectedHlsRetryDone && !activeIsHls && responseWasHls &&
                error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED
            ) {
                retryRedirectedStreamAsHls()
                return
            }
            // Refused only because the previous engine's session hasn't been released yet — wait it out
            // once instead of failing the channel or handing it back to mpv (see [noteSessionLimit]).
            if (sessionLimitSeen && !sessionLimitRetryDone) { retryAfterSessionRelease(); return }
            // A hardware decoder that died before the first frame is usually recoverable on a FRESH
            // MediaCodec, so rebuild and try once more before conceding the channel to mpv (see
            // [rebuildDecoderAndRetry]).
            if (!decoderRetryDone && isDecoderFailure(error)) { rebuildDecoderAndRetry(error); return }
            // Never opened → a stream ExoPlayer can't handle; the VM falls back to mpv on this ERROR.
            _state.value = State.ERROR
            _isPlaying.value = false
            _buffering.value = false
            _error.value = "Couldn't play this channel."
            val raw = lastCodecError ?: diagnostics.recentError()
                ?: error.errorCodeName + ((error.cause?.message ?: error.message)?.let { ": $it" } ?: "")
            _errorInfo.value = ErrorInfo(PlayerErrors.reasonFor(raw), exoSpec(), raw)
        }
    }

    /** Attach the preview SurfaceView's surface, or null when it's destroyed. */
    fun setSurface(s: Surface?) {
        surface = s
        if (s != null) player?.setVideoSurface(s) else player?.clearVideoSurface()
    }

    /** Enable/disable Media3's own Surface.setFrameRate mechanism. This is separate from the window-level
     *  [FrameRateController], so both must follow the same user setting. */
    fun setAutoFrameRateEnabled(enabled: Boolean) {
        val turnedOn = enabled && !autoFrameRateEnabled
        autoFrameRateEnabled = enabled
        player?.setVideoChangeFrameRateStrategy(
            if (enabled) C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS
            else C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF,
        )
        // Switching AFR on mid-channel has to start the fps measurement it needs (F14) — otherwise it only
        // takes effect on the next tune, and only if "Measured stream stats" happens to be on.
        if (turnedOn) ensureFpsMeasurement()
    }

    /** Detach [s] only if it's still the surface in use. A surface-generation bump swaps one SurfaceView
     *  for another, and the outgoing view's `surfaceDestroyed` can land after the incoming view's
     *  `surfaceCreated` — a plain `setSurface(null)` would then throw away the good new surface. */
    fun detachSurface(s: Surface) {
        if (surface !== s) return
        setSurface(null)
    }

    /** Start (or switch to) [url] as a muted/unmuted preview. Never throws — a stream ExoPlayer can't set
     *  up just falls back to the channel logo (the full mpv player can still play it). [meta] populates the
     *  full-screen HUD title when this preview is promoted. [userAgent] is the per-source custom UA. */
    /** Bumped whenever the video output surface must be thrown away and rebuilt; [ExoPreviewSurface]
     *  keys its SurfaceView on this, so a new value means a brand-new [Surface]. */
    private val _surfaceGeneration = MutableStateFlow(0)
    val surfaceGeneration: StateFlow<Int> = _surfaceGeneration

    /** Force the preview SurfaceView to be destroyed and recreated, so the next codec is configured
     *  against a pristine native window.
     *
     *  Some hardware decoders — measured on Realtek (`OMX.realtek.video.decoder`) — can only ever run
     *  ONE 4K instance per Surface. Releasing a 4K codec leaves the native window unusable
     *  (`freeAllBuffers: N buffers were freed while being dequeued!`), and every later codec configured
     *  against it dies ~1s after start with `ERROR(0x80001000)` → `IllegalStateException` out of
     *  `native_dequeueOutputBuffer`, which the live engine reports as a decode failure and falls back to
     *  mpv. Waiting longer does not help (a failing tune had a 885ms gap, a succeeding one 857ms) and
     *  neither does a fresh ExoPlayer/codec — only a fresh Surface does. That is exactly why toggling to
     *  mpv and back "fixed" such a channel: the engine swap recreates the SurfaceView. */
    private fun recreateSurface() {
        _surfaceGeneration.value++
    }

    /** Fully release the ExoPlayer instance (and its MediaCodec) — used when leaving a UHD channel so the
     *  4K hardware decoder is handed back cleanly instead of parked/reused, and recreate the surface with
     *  it (see [recreateSurface]). The next [play] lazily rebuilds via `player ?: build()`; it may run
     *  before the replacement surface arrives, which is fine — [setSurface] attaches it a frame later. */
    fun releaseDecoderForUhd() {
        if (!sawUhd) return // only pay the rebuild when leaving a genuine UHD stream
        sawUhd = false
        if (player == null) return
        android.util.Log.i(LiveDiagnosticsLog.TAG, "releaseDecoderForUhd(): releasing the 4K decoder + surface")
        LiveDiagnosticsLog.event("UHD channel left — full decoder release+rebuild")
        player?.run { removeListener(listener); release() }
        player = null
        videoRenderer = null
        recreateSurface()
    }

    /** [prerollSecsOverride] = the tuned channel's playlist "Pre-buffer" override in
     *  seconds; null follows the global setting. */
    fun play(
        url: String,
        muted: Boolean,
        meta: MediaMeta = MediaMeta(),
        userAgent: String? = null,
        prerollSecsOverride: Int? = null,
        /** Per-channel HTTP headers serialized as `Key: Value` per line (M3U, F16); null for none. */
        httpHeaders: String? = null,
    ) {
        LiveDiagnosticsLog.event("play() url=${HttpClient.redactUrl(url)} muted=$muted")
        // Read BEFORE the player is (re)built below — the load control is fixed at construction.
        prerollOverrideSecs = prerollSecsOverride
        stoppingIntentionally = false
        currentHeaders = StreamHeaders.decode(httpHeaders)
        // A channel's own User-Agent is more specific than the playlist-wide one, so it wins (F16).
        currentUa = StreamHeaders.userAgentOf(currentHeaders)
            ?: userAgent?.takeIf { it.isNotBlank() }
            ?: HttpClient.DEFAULT_USER_AGENT
        diagnostics.start(); diagnostics.markLoad()
        lastCodecError = null; lastVideoDecoder = null
        this.muted = muted
        currentUrl = url
        hasPlayed = false; retryCount = 0; reconnectPending = false; gaveUp = false; decoderRetryDone = false
        responseWasHls = false; forceHlsForCurrentLoad = false; redirectedHlsRetryDone = false
        refusedSegments.clear(); _segmentsRefused.value = false; playlistLogged = false
        sessionLimitSeen = false; sessionLimitRetryDone = false
        mainHandler.removeCallbacks(stallWatchdog); mainHandler.removeCallbacks(progressWatchdog); mainHandler.removeCallbacks(fpsFastRefresh)
        mainHandler.removeCallbacks(healthyReset)
        audioTrackList = emptyList(); audioSelections = emptyList(); _audioCount.value = 0
        textTrackList = emptyList(); textSelections = emptyList(); _subCount.value = 0
        _subtitleOn.value = false; _cues.value = emptyList(); _audioUnsupported.value = false
        _noVideoDetected.value = false; noVideoTriggered = false; readySinceMs = 0L
        _videoHeight.value = null; _videoAspect.value = null; _videoSize.value = null; _streamChips.value = emptyList(); _videoFps.value = null
        _videoRes.value = null
        _error.value = null
        _errorInfo.value = null
        frameCounter.set(0); lastFrameCount = 0; everRendered = false; lastProgressPos = -1L; lastProgressWallMs = 0L; frozenChecks = 0
        throughputTracker.reset(); fpsSample.resetAll(); dropsBaseline = currentDroppedFrames(player)
        audioWatchdog.reset()
        _currentMeta.value = meta
        _volume.value = if (muted) 0 else 100
        _state.value = State.LOADING
        _buffering.value = true
        runCatching {
            // A LoadControl is fixed when the player is constructed, and this engine keeps ONE player alive
            // across tunes — so a setting changed while nothing was playing, or a per-playlist override that
            // differs from the last channel's, would otherwise never take effect (the "Pre-buffer
            // does nothing" report). Drop the player whenever the numbers it was built with no longer match.
            val wanted = LiveBuffer.loadControlFor(liveBufferSecs, effectivePrerollSecs())
            if (player != null && builtLoadControl != wanted) {
                LiveDiagnosticsLog.event("load_control stale (was=$builtLoadControl want=$wanted) — rebuilding player")
                player?.run { removeListener(listener); release() }
                player = null
                videoRenderer = null
            }
            val p = player ?: build().also { player = it }
            // May be null right after a surface-generation bump — setSurface attaches it a frame later.
            surface?.let { p.setVideoSurface(it) }
            // Assume video until the tracks arrive, so a muted preview never leaks a frame of audio while
            // the stream is still being sniffed; rebuildTracks() relaxes this for audio-only streams.
            hasVideoTrack = true
            applyMute(force = true)
            setVideoTrackDisabled(_audioOnly.value) // survives a player rebuild while Audio Mode is on (F19c)
            p.setMediaSource(mediaSourceFor(url))
            p.prepare()
            p.playWhenReady = true
        }.onFailure {
            android.util.Log.w(LiveDiagnosticsLog.TAG, "preview play() failed for ${HttpClient.redactUrl(url)}", it)
            LiveDiagnosticsLog.event("play() failed: ${it.message}")
            _state.value = State.ERROR
            _error.value = "Couldn't play this channel."
            val raw = lastCodecError ?: diagnostics.recentError() ?: it.message
            _errorInfo.value = raw?.let { r -> ErrorInfo(PlayerErrors.reasonFor(r), exoSpec(), r) }
        }
    }

    /**
     * Notified when the audio watchdog has forced this session to stereo, with a message to show the
     * user. Set by whoever owns the UI; a null callback means the fallback still happens silently
     * (getting sound back matters more than announcing it).
     */
    var onAudioFallback: ((String) -> Unit)? = null

    /**
     * Rebuild the player so a changed audio configuration takes effect on the channel that is playing.
     *
     * A rebuild, not a reload: an ExoPlayer's audio sink capabilities are fixed when the renderers are
     * constructed, so re-preparing the same player would keep the sink that just failed. Live has no
     * position to preserve, so this is just "open the same channel again".
     */
    private fun rebuildForSettingChange() {
        val url = currentUrl ?: return
        val meta = _currentMeta.value
        val ua = currentUa
        val wasMuted = muted
        val preroll = prerollOverrideSecs
        // Re-encoded rather than kept as a map so the reopened channel goes down exactly the same path
        // as a fresh tune (including the per-channel UA precedence).
        val headers = StreamHeaders.encode(currentHeaders)
        audioWatchdog.reset()
        mainHandler.removeCallbacks(stallWatchdog)
        mainHandler.removeCallbacks(progressWatchdog)
        mainHandler.removeCallbacks(healthyReset)
        player?.run { removeListener(listener); release() }
        player = null
        videoRenderer = null
        play(url, wasMuted, meta, ua, preroll, headers)
    }

    fun setMuted(m: Boolean) {
        muted = m
        _volume.value = if (m) 0 else 100
        applyMute()
    }

    /** Push [muted] onto the player: volume, plus the audio-track deselect that also silences a
     *  passthrough (AC3/E-AC3/DTS 5.1) bitstream. [force] re-sends the track parameters even when the
     *  desired state is unchanged — needed right after a (re)built player, whose parameters are fresh. */
    private fun applyMute(force: Boolean = false) {
        val p = player ?: return
        p.volume = if (muted) 0f else 1f
        val disable = muted && hasVideoTrack
        if (!force && disable == audioTrackDisabled) return
        audioTrackDisabled = disable
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, disable)
            .build()
        applyVolumeBoost(_volume.value) // re-binds the >100% effect after a player rebuild / mute change
    }

    // Snapshot of the live channel taken when the app backgrounds (screensaver / Home), so it can be restored
    // on return — otherwise onStop frees the stream and a paused live channel never resumes (even on Play).
    private data class LiveRestore(val url: String, val muted: Boolean, val meta: MediaMeta)
    @Volatile private var backgroundRestore: LiveRestore? = null

    /** Backgrounded (screensaver / Home): remember what's playing, then free the stream. Paired with
     *  [onAppForegrounded]. */
    fun onAppBackgrounded() {
        currentUrl?.let { backgroundRestore = LiveRestore(it, muted, _currentMeta.value) }
        stop()
    }

    /** Foregrounded: re-tune the live channel that was freed while backgrounded (at the live edge), so it
     *  resumes instead of sitting on a dead/empty stream. No-op if something is already playing. */
    fun onAppForegrounded() {
        val r = backgroundRestore ?: return
        backgroundRestore = null
        if (currentUrl != null) return
        play(r.url, muted = r.muted, meta = r.meta)
    }

    /** Drop any pending restore (e.g. on profile switch — don't bring back the previous user's channel). */
    fun discardBackgroundRestore() { backgroundRestore = null }

    /** Stop playback and free the decoder/connection (e.g. before mpv takes over for fullscreen). Keeps the
     *  ExoPlayer instance alive for the next preview. */
    fun stop() {
        LiveDiagnosticsLog.event("stop() — intentional")
        stoppingIntentionally = true
        currentUrl = null
        hasPlayed = false; retryCount = 0; reconnectPending = false; gaveUp = false; decoderRetryDone = false
        mainHandler.removeCallbacks(stallWatchdog); mainHandler.removeCallbacks(progressWatchdog); mainHandler.removeCallbacks(fpsFastRefresh)
        mainHandler.removeCallbacks(healthyReset)
        frameCounter.set(0); lastFrameCount = 0; everRendered = false; lastProgressPos = -1L; frozenChecks = 0
        audioTrackList = emptyList(); audioSelections = emptyList(); _audioCount.value = 0
        textTrackList = emptyList(); textSelections = emptyList(); _subCount.value = 0
        _subtitleOn.value = false; _cues.value = emptyList(); _audioUnsupported.value = false
        _noVideoDetected.value = false; noVideoTriggered = false; readySinceMs = 0L
        _videoHeight.value = null; _videoAspect.value = null; _videoSize.value = null; _streamChips.value = emptyList(); _videoFps.value = null
        _state.value = State.IDLE
        player?.run { stop(); clearMediaItems() }
        releaseHttpConnections()
        // Leaving a UHD channel (back / exit fullscreen / background): fully release the 4K decoder.
        releaseDecoderForUhd()
    }

    /**
     * Drop the playback pool's now-idle sockets instead of letting OkHttp hold them for its default
     * 5 idle minutes.
     *
     * A panel that allows one session per account (see [LiveStreamQuirks.isSessionLimit]) refuses the
     * *second* client, so after a handoff a pooled ExoPlayer connection locks mpv out of the channel the
     * user just asked for — mpv/FFmpeg has its own HTTP stack and cannot reuse it. Eviction only closes
     * connections no call is using; anything in flight is untouched.
     *
     * This clears [StreamingHttpClient]'s own pool, not the app-wide one, so EPG/metadata/image
     * connections keep their keep-alive across a zap (F28).
     */
    private fun releaseHttpConnections() {
        // Off the main thread: closing sockets is quick but still I/O, and stop() runs on a UI transition.
        Thread {
            runCatching { streamingHttp.evictAll() }
                .onFailure { LiveDiagnosticsLog.event("connection pool evict failed: ${it.javaClass.simpleName}") }
        }.start()
    }

    fun release() {
        LiveDiagnosticsLog.event("release() — intentional")
        releaseLoudness()
        stoppingIntentionally = true
        mainHandler.removeCallbacks(stallWatchdog)
        mainHandler.removeCallbacks(progressWatchdog)
        mainHandler.removeCallbacks(healthyReset)
        player?.run { removeListener(listener); release() }
        player = null
        videoRenderer = null
        surface = null
        currentUrl = null
        sawUhd = false
        _state.value = State.IDLE
    }

    /** Live auto-reconnect: re-fetch [currentUrl] from the live edge after a mid-stream error/stall. Backs
     *  off and gives up after [MAX_RECONNECTS] consecutive failures (then the HUD's Retry button takes over).
     *  retryCount is reset to 0 as soon as playback goes healthy again (STATE_READY).
     *
     *  For an expiring-URL source (Stalker, plan §5.4.1) the reconnect must NOT replay the now-dead
     *  resolved URL — a [reconnectUrlProvider] mints a fresh one first (null/absent → replay as-is,
     *  which is correct for M3U/Xtream and direct-URL Stalker portals). */
    private fun reconnect(reason: String, fastHlsHttpRecovery: Boolean = false) {
        mainHandler.removeCallbacks(stallWatchdog); mainHandler.removeCallbacks(progressWatchdog); mainHandler.removeCallbacks(fpsFastRefresh)
        mainHandler.removeCallbacks(healthyReset) // this attempt is a failure, not a recovery
        val p = player
        val url = currentUrl
        if (p == null || url == null || retryCount >= MAX_RECONNECTS) {
            LiveDiagnosticsLog.event("reconnect exhausted ($reason) at $retryCount/$MAX_RECONNECTS — giving up")
            gaveUp = true
            _state.value = State.ERROR; _isPlaying.value = false; _buffering.value = false
            _error.value = "Lost connection to this channel."
            val raw = lastCodecError ?: diagnostics.recentError() ?: reason
            _errorInfo.value = ErrorInfo(PlayerErrors.reasonFor(raw), exoSpec(), raw)
            return
        }
        retryCount++
        reconnectPending = true
        _error.value = null; _errorInfo.value = null; _state.value = State.LOADING; _buffering.value = true
        LiveDiagnosticsLog.event("reconnect attempt $retryCount/$MAX_RECONNECTS reason=$reason")
        // A brief HTTP failure on HLS reconnects fast (segments are small and the next one is seconds
        // away), but it does NOT get its retry count forgiven here: only [healthyReset] — sustained
        // playback — clears the ladder. Forgiving on a bare READY let a feed that died 10 s later loop
        // forever without ever reaching the honest "Lost connection" end state.
        val delayMs = if (fastHlsHttpRecovery) hlsHttpReconnectDelayMs(retryCount) else reconnectDelayMs(retryCount)
        // Resolve a fresh URL off-main (Stalker create_link is a network call) before the delayed reload.
        val provider = reconnectUrlProvider
        scope.launch {
            val fresh = if (provider != null) {
                withContext(Dispatchers.IO) {
                    runCatching { provider.freshUrl() }
                        .onFailure { LiveDiagnosticsLog.event("reconnect fresh-url failed: ${it.message}") }
                        .getOrNull()
                }
            } else null
            // Coalesce the backoff delay with the resolve: whichever is later wins, but the resolve must
            // complete before we reload. Post the reload so it lands on the main thread's Looper after delay.
            mainHandler.postDelayed({
                if (currentUrl != url) { reconnectPending = false; return@postDelayed } // superseded (zapped / stopped)
                reconnectPending = false
                val loadUrl = fresh ?: url // null provider/result → replay the (still-valid) stored URL
                if (fresh != null && fresh != url) {
                    currentUrl = fresh // adopt the refreshed URL so a later reconnect compares against it
                    LiveDiagnosticsLog.event("reconnect re-resolved expiring URL (${HttpClient.redactUrl(fresh)})")
                }
                runCatching {
                    // Via mediaSourceFor(), not setMediaItem(): a bare MediaItem would drop the TS
                    // caption-descriptor override (#57 CC1) and the live target offset, so a channel
                    // silently lost its captions after the first reconnect.
                    p.setMediaSource(mediaSourceFor(loadUrl)) // fresh fetch (live edge)
                    p.prepare()
                    p.playWhenReady = true
                }.onFailure { _state.value = State.ERROR; _error.value = "Lost connection to this channel." }
            }, delayMs)
        }
    }

    /**
     * Whether [error] is the video hardware decoder giving up rather than a stream/network problem.
     * Capability mismatches (`…EXCEEDS_CAPABILITIES`) are deliberately NOT included — a decoder that
     * genuinely can't handle the format will fail identically on a rebuild, so retrying only delays mpv.
     */
    private fun isDecoderFailure(error: PlaybackException): Boolean =
        error.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED ||
            error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED

    /**
     * A live HLS segment came back 403/404/410.
     *
     * The traced panel signs every segment URL with a short-lived token and then answers **all** of them
     * `403 "Invalid token 2"` while the playlist itself keeps returning 200 — not one segment of the
     * eight in the window ever succeeded. Media3 can only re-issue the exact URL it resolved from the
     * playlist snapshot, so there is no offset, buffer size or retry count that rescues this: an earlier
     * attempt at moving the playhead 40/60/90 s back into the window failed at every rung, because
     * sitting further back makes the token *more* stale, not less.
     *
     * So the moment [LiveStreamQuirks.REFUSALS_BEFORE_HANDOFF] *distinct* segments have been refused,
     * stop: flag it for the ViewModel, which hands the channel to mpv — FFmpeg re-reads the playlist and
     * fetches with a fresh token, which is exactly why the same channel plays there. The panel is
     * remembered so its next channel opens on mpv without the dead spinner first.
     */
    private fun noteSegmentRefusal(segmentUri: String, status: Int) {
        val url = currentUrl ?: return
        if (!refusedSegments.add(segmentUri)) return // same segment retried — not new evidence
        if (refusedSegments.size == 1) logHlsPlaylist("segment HTTP $status")
        LiveDiagnosticsLog.event(
            "segment refused (HTTP $status) ${refusedSegments.size}/${LiveStreamQuirks.REFUSALS_BEFORE_HANDOFF} " +
                "distinct segments on this load",
        )
        if (refusedSegments.size < LiveStreamQuirks.REFUSALS_BEFORE_HANDOFF) return
        if (_segmentsRefused.value) return // already handed over
        LiveStreamQuirks.rememberSegmentRefusal(url)
        LiveDiagnosticsLog.event(
            "provider refuses its own signed segment URLs — ExoPlayer cannot re-sign them; handing this " +
                "panel to mpv",
        )
        _segmentsRefused.value = true
        // The user sees a channel that "just works after a pause" — this is the line that explains why,
        // and it is the one piece of evidence a provider-specific report needs (F26).
        PlaybackErrorLog.event(
            context, "ExoPlayer", live = true,
            what = "Handed this channel to mpv",
            detail = "provider refused $status on ${refusedSegments.size} signed segment URLs",
        )
    }

    /**
     * The panel refused us because its one allowed session is still held — almost always by mpv, which
     * this engine just took over from.
     *
     * The stream is fine and the URL is right; we are simply the second client. The socket the other
     * engine held has to finish closing and the panel has to notice, which takes longer than the handoff
     * delay, so the answer is to wait and ask again once rather than to fail the channel or bounce it
     * back to mpv (which would hand the session straight back and make the toggle useless).
     */
    private fun noteSessionLimit(uri: String) {
        val url = currentUrl ?: return
        LiveStreamQuirks.rememberSessionLimit(url)
        if (sessionLimitSeen) return // one log line per load is enough; the retry is already armed
        sessionLimitSeen = true
        LiveDiagnosticsLog.event(
            "provider refused with HTTP 458 (account session still in use) uri=${HttpClient.redactUrl(uri)}",
        )
        PlaybackErrorLog.event(
            context, "ExoPlayer", live = true,
            what = "Provider allows one session at a time",
            detail = "HTTP 458 while the previous engine's session was still open — waiting and retrying once",
        )
    }

    /**
     * Give the previous engine's session time to actually die, then try the same channel once more.
     * Only for [noteSessionLimit] — a retry helps nothing when the URL or the stream is the problem.
     */
    private fun retryAfterSessionRelease() {
        val p = player ?: return
        val url = currentUrl ?: return
        sessionLimitRetryDone = true
        _state.value = State.LOADING; _buffering.value = true
        _error.value = null; _errorInfo.value = null
        LiveDiagnosticsLog.event("waiting ${SESSION_RELEASE_MS}ms for the provider to free the account session, then retrying")
        mainHandler.postDelayed({
            if (currentUrl != url) return@postDelayed
            runCatching {
                p.setMediaSource(mediaSourceFor(url))
                p.prepare()
                p.playWhenReady = true
            }.onFailure {
                _state.value = State.ERROR
                _buffering.value = false
                _error.value = "Couldn't play this channel."
                _errorInfo.value = ErrorInfo(PlayerErrors.reasonFor(it.message.orEmpty()), exoSpec(), it.message)
            }
        }, SESSION_RELEASE_MS)
    }

    /**
     * Dump the shape of the live playlist Media3 is actually working from — the one piece of evidence
     * that separates "the provider won't serve its newest segments" from an app-side URL/identity bug.
     * Read from the in-memory snapshot ([Player.getCurrentManifest]); costs no extra request. Segment
     * hosts are kept (they are the point), paths are truncated and credential-redacted.
     */
    private fun logHlsPlaylist(reason: String) {
        if (!LiveDiagnosticsLog.enabled) return
        val p = player ?: return
        val manifest = p.currentManifest as? androidx.media3.exoplayer.hls.HlsManifest ?: return
        val playlist = manifest.mediaPlaylist
        val base = playlist.baseUri
        val hosts = playlist.segments
            .map { LiveStreamQuirks.hostKey(androidx.media3.common.util.UriUtil.resolveToUri(base, it.url).toString()) }
        val tail = playlist.segments.takeLast(3).mapIndexed { i, seg ->
            val abs = androidx.media3.common.util.UriUtil.resolveToUri(base, seg.url).toString()
            val idx = playlist.mediaSequence + playlist.segments.size - minOf(3, playlist.segments.size) + i
            "$idx@${LiveStreamQuirks.hostKey(abs)}${HttpClient.redactUrl(abs.substringAfter("://").substringAfter('/')).takeLast(28)}"
        }
        LiveDiagnosticsLog.event(
            "hls_playlist ($reason) mediaSeq=${playlist.mediaSequence} segs=${playlist.segments.size} " +
                "targetDurSec=${playlist.targetDurationUs / 1_000_000.0} windowSec=${playlist.durationUs / 1_000_000.0} " +
                "pdt=${playlist.hasProgramDateTime} startOffsetUs=${playlist.startOffsetUs} " +
                "liveOffsetMs=${p.currentLiveOffset.takeUnless { it == C.TIME_UNSET } ?: -1} " +
                "hosts=${hosts.groupingBy { it }.eachCount()} newest=$tail",
        )
    }

    private fun retryRedirectedStreamAsHls() {
        val p = player ?: return
        val url = currentUrl ?: return
        redirectedHlsRetryDone = true
        forceHlsForCurrentLoad = true
        // Panel-wide lesson, not a per-channel one: every other channel here — and mpv, if we hand over —
        // now starts as HLS instead of repeating this failure.
        LiveStreamQuirks.rememberHlsRedirect(url)
        _state.value = State.LOADING; _buffering.value = true
        _error.value = null; _errorInfo.value = null
        LiveDiagnosticsLog.event("redirected .ts response is HLS — retrying with HlsMediaSource")
        mainHandler.post {
            if (currentUrl != url) return@post
            runCatching {
                p.setMediaSource(mediaSourceFor(url))
                p.prepare()
                p.playWhenReady = true
            }.onFailure {
                _state.value = State.ERROR
                _buffering.value = false
                _error.value = "Couldn't play this channel."
                _errorInfo.value = ErrorInfo(PlayerErrors.reasonFor(it.message.orEmpty()), exoSpec(), it.message)
            }
        }
    }

    /**
     * One-shot recovery from a decoder that died **before the first frame**.
     *
     * Observed on Realtek TVs with 4K HEVC raw-TS: the codec is created, `format_supported=YES`, and
     * ~1.5s later `MediaCodec.dequeueOutputBuffer` throws IllegalStateException — `Decoder failed:
     * OMX.realtek.video.decoder`. The MediaCodec is then permanently wedged, but a NEW one plays the
     * very same stream: that is exactly what the HUD's compatibility-mode toggle used to achieve by
     * hand (mpv, then back to ExoPlayer on a freshly built player). ExoPlayer's own retry can't fix it
     * because `prepare()` reuses the wedged codec, so the player instance itself has to go.
     *
     * Once per load ([decoderRetryDone]): if the rebuild fails too, the normal ERROR path runs and the
     * VM hands the channel to mpv as before — this only costs a genuinely undecodable channel one extra
     * attempt before the fallback.
     */
    private fun rebuildDecoderAndRetry(error: PlaybackException) {
        val url = currentUrl ?: return
        decoderRetryDone = true
        LiveDiagnosticsLog.event("decoder failed before first frame (${error.errorCodeName}) — rebuilding the decoder and retrying once")
        android.util.Log.w(LiveDiagnosticsLog.TAG, "decoder failure before first frame — rebuild + retry once")
        _state.value = State.LOADING; _buffering.value = true
        _error.value = null; _errorInfo.value = null
        // Drop the whole player: removeListener first so this release doesn't come back as STATE_IDLE.
        player?.run { removeListener(listener); release() }
        player = null
        videoRenderer = null
        sawUhd = false
        // A fresh codec alone does NOT rescue this — the dead native window has to go too, or the retry
        // reproduces the identical failure. See [recreateSurface].
        recreateSurface()
        // Let the OMX component actually tear down before the replacement asks for it — the same
        // reason the mpv→ExoPlayer swap in LiveViewModel waits before re-tuning.
        mainHandler.postDelayed({
            if (currentUrl != url) return@postDelayed // zapped away / stopped while we waited
            runCatching {
                val p = build().also { player = it }
                surface?.let { p.setVideoSurface(it) }
                applyMute(force = true)
                p.setMediaSource(mediaSourceFor(url))
                p.prepare()
                p.playWhenReady = true
            }.onFailure {
                LiveDiagnosticsLog.event("decoder rebuild failed: ${it.message}")
                _state.value = State.ERROR
                _error.value = "Couldn't play this channel."
                _errorInfo.value = ErrorInfo(PlayerErrors.reasonFor(it.message ?: ""), exoSpec(), it.message ?: "")
            }
        }, DECODER_REBUILD_DELAY_MS)
    }

    // --- Audio Mode (Audio Mode plan §5): keep audio playing, release the video surface ---
    private val _audioOnly = MutableStateFlow(false)
    override val audioOnly: StateFlow<Boolean> = _audioOnly.asStateFlow()
    override fun enterAudioOnly() {
        if (_audioOnly.value) return
        _audioOnly.value = true
        player?.clearVideoSurface() // audio keeps playing without a surface; [surface] kept for return
        setVideoTrackDisabled(true)
    }
    override fun exitAudioOnly() {
        if (!_audioOnly.value) return
        _audioOnly.value = false
        setVideoTrackDisabled(false)
        surface?.let { player?.setVideoSurface(it) }
    }

    /**
     * Dropping the surface alone only stops the *drawing*: ExoPlayer keeps decoding every video frame into
     * a dummy buffer, so Audio Mode still burns the decoder, the CPU and the bandwidth it was meant to save.
     * Disabling the track releases the decoder outright and skips the video samples (F19c).
     */
    private fun setVideoTrackDisabled(disabled: Boolean) {
        val p = player ?: return
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, disabled)
            .build()
    }

    // --- PlaybackEngine controls (full-screen HUD) ---
    override fun togglePlayPause() {
        val p = player ?: return
        if (p.isPlaying) p.pause() else p.play()
    }

    override fun setZoomMode(mode: ZoomMode) { _zoomMode.value = mode } // ExoPreviewSurface observes this + videoAspect/Size and sizes the surface (see Modifier.videoZoom)

    override fun adjustVolume(delta: Int) {
        // ExoPlayer itself can't amplify past unity (and a gain audio-processor broke the audio sink), so
        // 100–150% rides on the platform LoudnessEnhancer instead — same range as mpv, so a quiet channel
        // can be lifted without leaving Live TV (F19a).
        val v = (_volume.value + delta).coerceIn(0, MAX_VOLUME)
        _volume.value = v
        muted = v == 0
        applyMute() // re-enables/deselects the audio track when crossing 0 (passthrough-safe mute)
        player?.volume = v.coerceAtMost(100) / 100f
        applyVolumeBoost(v)
    }

    // --- Volume boost above 100% (F19a) ---
    private var loudness: android.media.audiofx.LoudnessEnhancer? = null
    private var loudnessSession = C.AUDIO_SESSION_ID_UNSET

    /**
     * Attach/AIM/drop the LoudnessEnhancer for [percent]. At or below 100 the effect is released outright,
     * so a normal stream never carries an audio effect it doesn't need. Devices whose audio HAL refuses the
     * effect (it is optional) just stay at unity — the volume readout still moves, nothing crashes.
     *
     * Also re-attaches after a player rebuild, since the effect is bound to one audio session id.
     */
    private fun applyVolumeBoost(percent: Int) {
        val session = player?.audioSessionId ?: C.AUDIO_SESSION_ID_UNSET
        if (session != loudnessSession) releaseLoudness()
        val gainMb = (percent - 100).coerceAtLeast(0) * BOOST_MB_PER_PERCENT
        if (gainMb <= 0 || session == C.AUDIO_SESSION_ID_UNSET) { releaseLoudness(); return }
        val fx = loudness ?: runCatching { android.media.audiofx.LoudnessEnhancer(session) }
            .onFailure { LiveDiagnosticsLog.event("volume boost unavailable: ${it.javaClass.simpleName}") }
            .getOrNull()?.also { loudness = it; loudnessSession = session } ?: return
        runCatching { fx.setTargetGain(gainMb); fx.enabled = true }
            .onFailure { LiveDiagnosticsLog.event("volume boost failed: ${it.javaClass.simpleName}") }
    }

    private fun releaseLoudness() {
        runCatching { loudness?.release() }
        loudness = null
        loudnessSession = C.AUDIO_SESSION_ID_UNSET
    }

    override fun toggleMute() = setMuted(!muted)
    override fun retry() {
        val url = currentUrl ?: return
        val provider = reconnectUrlProvider
        if (provider == null) { play(url, muted, _currentMeta.value); return }
        // Expiring-URL source (Stalker): re-resolve before retrying, then reload on the main thread.
        scope.launch {
            val fresh = withContext(Dispatchers.IO) {
                runCatching { provider.freshUrl() }
                    .onFailure { LiveDiagnosticsLog.event("retry fresh-url failed: ${it.message}") }
                    .getOrNull()
            }
            play(fresh ?: url, muted, _currentMeta.value)
        }
    }
    override fun selectAudio(id: Int) {
        val p = player ?: return
        val sel = audioSelections.firstOrNull { it.id == id } ?: return
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setOverrideForType(androidx.media3.common.TrackSelectionOverride(sel.group, listOf(sel.trackIndex)))
            .build()
        audioTrackList = audioTrackList.map { it.copy(selected = it.mpvId == id) }
    }

    override fun selectSubtitle(id: Int) {
        val p = player ?: return
        val sel = textSelections.firstOrNull { it.id == id } ?: return
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setOverrideForType(androidx.media3.common.TrackSelectionOverride(sel.group, listOf(sel.trackIndex)))
            .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, false)
            .build()
        _subtitleOn.value = true // mount the SubtitleView overlay
        textTrackList = textTrackList.map { it.copy(selected = it.mpvId == id) }
    }

    override fun disableSubtitles() {
        player?.let {
            it.trackSelectionParameters = it.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, true).build()
        }
        _subtitleOn.value = false
        _cues.value = emptyList()
        textTrackList = textTrackList.map { it.copy(selected = false) }
    }

    override fun audioTracks(): List<TrackOption> = audioTrackList
    override fun textTracks(): List<TrackOption> = textTrackList

    /** Build the audio + subtitle track lists from the active stream so the HUD menus can switch language /
     *  subtitles (multi-track live channels, or a VOD file imported via M3U). Mirrors [ExoSubtitleEngine]. */
    private fun rebuildTracks(tracks: androidx.media3.common.Tracks) {
        val audio = ArrayList<TrackOption>(); val aSel = ArrayList<AudioSel>(); var aId = 0
        val text = ArrayList<TrackOption>(); val tSel = ArrayList<TextSel>(); var tId = 0
        for (group in tracks.groups) {
            when (group.type) {
                androidx.media3.common.C.TRACK_TYPE_AUDIO -> for (i in 0 until group.length) {
                    val f = group.getTrackFormat(i)
                    val lang = f.language?.takeIf { it.isNotBlank() && it != "und" }
                    audio.add(TrackOption(f.label ?: lang?.uppercase() ?: "Audio ${aId + 1}", aId, group.isTrackSelected(i), lang = lang))
                    aSel.add(AudioSel(aId, group.mediaTrackGroup, i)); aId++
                }
                androidx.media3.common.C.TRACK_TYPE_TEXT -> for (i in 0 until group.length) {
                    val f = group.getTrackFormat(i)
                    val lang = f.language?.takeIf { it.isNotBlank() && it != "und" }
                    text.add(TrackOption(f.label ?: lang?.uppercase() ?: "Subtitle ${tId + 1}", tId, _subtitleOn.value && group.isTrackSelected(i), lang = lang))
                    tSel.add(TextSel(tId, group.mediaTrackGroup, i)); tId++
                }
            }
        }
        // Audio-only (radio) streams must keep their audio renderer even when muted — deselecting it would
        // leave nothing to render and the progress watchdog would read that as a dead feed.
        hasVideoTrack = tracks.groups.any { it.type == androidx.media3.common.C.TRACK_TYPE_VIDEO }
        applyMute()
        audioTrackList = audio; audioSelections = aSel; _audioCount.value = audio.size
        textTrackList = text; textSelections = tSel; _subCount.value = text.size
        if (tv.own.owntv.BuildConfig.DEBUG) {
            LiveDiagnosticsLog.event(
                "tracks: audio=${audio.size} text=${text.size}" +
                    text.joinToString(prefix = " [", postfix = "]") { it.label },
            )
        }
        // Audio exists but ExoPlayer can decode none of it → the VM will route this stream to mpv.
        val anySupportedAudio = tracks.groups.any { g ->
            g.type == androidx.media3.common.C.TRACK_TYPE_AUDIO && (0 until g.length).any { g.isTrackSupported(it) }
        }
        _audioUnsupported.value = audio.isNotEmpty() && !anySupportedAudio
    }

    // Effective User-Agent for the current stream; updated per play() call.
    // null = no source UA configured, use DEFAULT_USER_AGENT.
    private var currentUa: String = HttpClient.DEFAULT_USER_AGENT
    /** Per-channel HTTP headers for the tuned channel (M3U `#EXTVLCOPT`/`#EXTHTTP`/`#KODIPROP`, F16).
     *  Empty for providers that carry none, which is the usual case. */
    private var currentHeaders: Map<String, String> = emptyMap()
    private var dataSourceForUa: String = ""
    private var dataSourceForHeaders: Map<String, String> = emptyMap()
    private var cachedHttpDataSource: OkHttpDataSource.Factory? = null
    private var cachedDefaultFactory: DefaultMediaSourceFactory? = null
    private var cachedHlsCcFactory: HlsMediaSource.Factory? = null
    /** Debug-build HTTP probe for provider-side failures. It persists redacted metadata, a
     *  media-signature classification, and — for FAILED responses only — a short scrubbed text prefix of
     *  the error body, which is usually the panel telling us exactly why it refused ("token expired",
     *  "max connections"). Successful bodies are never read. Credentials never appear: URLs go through
     *  [HttpClient.redactUrl], body text additionally has this request's own user/pass/token strings
     *  masked ([textPrefix]), and Authorization/Cookie are logged as presence flags, never values. */
    private val diagnosticHttpClient by lazy {
        streamingHttp.client.newBuilder()
            .addInterceptor { chain ->
                val startedAt = android.os.SystemClock.elapsedRealtime()
                val request = chain.request()
                try {
                    val response = chain.proceed(request)
                    val finalUrl = response.request.url.toString()
                    if (isHlsResponse(finalUrl, response.header("Content-Type"))) {
                        responseWasHls = true
                        // The panel — not just this URL — serves HLS behind a `.ts` endpoint. mpv needs to
                        // know that too, or FFmpeg reconnects to the manifest's EOF forever.
                        if (!request.url.toString().substringBefore('?').endsWith(".m3u8", ignoreCase = true)) {
                            LiveStreamQuirks.rememberHlsRedirect(request.url.toString())
                        }
                    }
                    if (LiveDiagnosticsLog.enabled) {
                        val prefix = runCatching { response.peekBody(564).bytes() }.getOrDefault(byteArrayOf())
                        val requested = request.url.toString()
                        // A redirect is invisible in the final URL alone, and it is exactly what decides
                        // whether a segment came from the panel's origin or its CDN.
                        val via = if (requested != finalUrl) {
                            " requested=${HttpClient.redactUrl(requested)}"
                        } else {
                            ""
                        }
                        // For an error the body IS the diagnosis ("token expired", "max connections", a
                        // hotlink-protection page…). Metadata-only for success responses, as before.
                        val body = if (!response.isSuccessful) {
                            " body=\"${textPrefix(prefix, requested)}\""
                        } else {
                            ""
                        }
                        LiveDiagnosticsLog.event(
                            "http_response role=${requestRole(requested)} code=${response.code} " +
                                "type=${response.header("Content-Type").orEmpty()} " +
                                "length=${response.body?.contentLength() ?: -1} signature=${mediaSignature(prefix)} " +
                                "server=${response.header("Server").orEmpty()} xcache=${response.header("X-Cache").orEmpty()} " +
                                "age=${response.header("Age").orEmpty()} retryAfter=${response.header("Retry-After").orEmpty()} " +
                                "setCookie=${response.headers("Set-Cookie").size} " +
                                "reqCookie=${request.header("Cookie") != null} reqAuth=${request.header("Authorization") != null} " +
                                "reqRange=${request.header("Range") != null} ua=${request.header("User-Agent").orEmpty()} " +
                                "elapsedMs=${android.os.SystemClock.elapsedRealtime() - startedAt} " +
                                "url=${HttpClient.redactUrl(finalUrl)}$via$body",
                        )
                    }
                    response
                } catch (t: Throwable) {
                    if (LiveDiagnosticsLog.enabled) {
                        LiveDiagnosticsLog.event(
                            "http_failure type=${t.javaClass.simpleName} message=${HttpClient.redactUrl(t.message.orEmpty())} " +
                                "elapsedMs=${android.os.SystemClock.elapsedRealtime() - startedAt} " +
                                "url=${HttpClient.redactUrl(request.url.toString())}",
                        )
                    }
                    throw t
                }
            }
            .build()
    }

    /**
     * A failed response's body, reduced to one short printable line for the log: markup stripped,
     * whitespace collapsed, capped, then scrubbed of anything that could identify the account. Scrubbing
     * is done against [requestUrl]'s **own** credentials — the Xtream `/live/<user>/<pass>/` segments and
     * signed query values — so even a panel that echoes the username back in an error page cannot leak it.
     */
    private fun textPrefix(bytes: ByteArray, requestUrl: String): String {
        val text = bytes.decodeToString()
            .replace(Regex("<[^>]*>"), " ")
            .replace(Regex("\\s+"), " ")
            .filter { it.code in 32..126 }
            .trim()
            .take(160)
        return HttpClient.redactUrl(secretsIn(requestUrl).fold(text) { acc, secret -> acc.replace(secret, "***") })
            .replace('"', '\'')
    }

    /** Credential-bearing substrings of [url] — path segments and signed query values — never logged. */
    private fun secretsIn(url: String): List<String> {
        val path = url.substringBefore('?')
        val segments = Regex("(?i)/(?:live|movie|series|vod|timeshift)/([^/]+)/([^/]+)/")
            .find(path)?.groupValues?.drop(1).orEmpty()
        val queryValues = url.substringAfter('?', "").split('&')
            .mapNotNull { it.substringAfter('=', "").takeIf { v -> v.length >= 6 } }
        return (segments + queryValues).filter { it.length >= 3 }
    }

    /** What this request was for — the one thing a bare URL in the log doesn't say. */
    private fun requestRole(url: String): String {
        val path = url.substringBefore('?').lowercase()
        return when {
            path.endsWith(".m3u8") -> "playlist"
            path.endsWith(".ts") || path.endsWith(".m4s") || path.endsWith(".mp4") -> "segment"
            path.endsWith(".key") -> "key"
            else -> "stream"
        }
    }

    private fun mediaSignature(bytes: ByteArray): String {
        if (bytes.isEmpty()) return "empty"
        fun has(vararg expected: Int): Boolean =
            bytes.size >= expected.size && expected.indices.all { (bytes[it].toInt() and 0xff) == expected[it] }
        val textStart = bytes.take(32).map { it.toInt().toChar() }.joinToString("").trimStart().lowercase()
        return when {
            // Three sync bytes distinguish a real transport stream from an error page beginning with 'G'.
            bytes.size > 376 && bytes[0] == 0x47.toByte() && bytes[188] == 0x47.toByte() && bytes[376] == 0x47.toByte() -> "mpeg-ts"
            has(0x1a, 0x45, 0xdf, 0xa3) -> "matroska"
            bytes.size >= 8 && bytes.copyOfRange(4, 8).decodeToString() == "ftyp" -> "mp4"
            has(0x00, 0x00, 0x01, 0xba) -> "mpeg-ps"
            has(0x49, 0x44, 0x33) -> "id3/audio"
            textStart.startsWith("#extm3u") -> "hls-manifest"
            textStart.startsWith("<!doctype") || textStart.startsWith("<html") -> "html"
            textStart.startsWith("{") || textStart.startsWith("[") -> "json"
            else -> "unknown"
        }
    }

    /**
     * Stock policy everywhere except a live media segment the provider outright refuses (403/404/410).
     * Media3 can only re-issue the identical segment URL, and the traced panel answers 403 to it for as
     * long as the playlist snapshot lives — the default ladder therefore spends ~8 s hammering a URL
     * that will never succeed, drains the buffer and turns a recoverable hiccup into a dead channel.
     * One short retry (a genuine blip), then fatal so [maybeBackOffFromLiveEdge]/the reconnect ladder
     * can act. Manifests and every other data type keep the stock behaviour.
     */
    private val edgeRefusalPolicy =
        object : androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy() {
            override fun getRetryDelayMsFor(
                loadErrorInfo: androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.LoadErrorInfo,
            ): Long {
                val status = httpStatusOf(loadErrorInfo.exception)
                val isSegment = loadErrorInfo.mediaLoadData.dataType == C.DATA_TYPE_MEDIA
                if (isSegment && status != null && LiveStreamQuirks.isEdgeRefusal(status)) {
                    return edgeRefusalRetryDelayMs(loadErrorInfo.errorCount)
                }
                return super.getRetryDelayMsFor(loadErrorInfo)
            }
        }

    private fun httpDataSourceFor(ua: String): OkHttpDataSource.Factory {
        // Keyed on the headers as well as the UA: the three cached factories bake the data source in,
        // so a channel with its own Referer must not reuse the previous channel's factory (F16).
        if (ua != dataSourceForUa || currentHeaders != dataSourceForHeaders || cachedHttpDataSource == null) {
            cachedHttpDataSource = OkHttpDataSource.Factory(diagnosticHttpClient).setUserAgent(ua)
                .setTransferListener(throughputTracker)
                .setDefaultRequestProperties(
                    currentHeaders.filterKeys { !it.equals("User-Agent", ignoreCase = true) },
                )
            // Raw MPEG-TS (typical Xtream live ".ts"): providers rarely declare caption descriptors in
            // the PMT, so the stock TS extractor never exposes the embedded CEA-608 track (#57).
            // FLAG_OVERRIDE_CAPTION_DESCRIPTORS makes it expose the standard CC1 track regardless; the
            // flag only affects TS — every other format sniffs exactly as before. Passed into the same
            // DefaultMediaSourceFactory that has always handled non-HLS live, so routing is unchanged.
            // The flag alone is NOT enough: DefaultExtractorsFactory passes an empty subtitle-format
            // list to DefaultTsPayloadReaderFactory, and with the override flag that empty list is
            // returned verbatim (= zero CC tracks, even declared ones). The CEA-608 CC1 format must be
            // supplied explicitly via setTsSubtitleFormats.
            val cc1 = androidx.media3.common.Format.Builder()
                .setSampleMimeType(androidx.media3.common.MimeTypes.APPLICATION_CEA608)
                .setAccessibilityChannel(1) // CC1 — the standard primary caption channel
                .build()
            cachedDefaultFactory = DefaultMediaSourceFactory(
                cachedHttpDataSource!!,
                androidx.media3.extractor.DefaultExtractorsFactory()
                    .setTsExtractorFlags(
                        androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory.FLAG_OVERRIDE_CAPTION_DESCRIPTORS,
                    )
                    .setTsSubtitleFormats(listOf(cc1)),
            )
            cachedHlsCcFactory = HlsMediaSource.Factory(cachedHttpDataSource!!)
                .setExtractorFactory(DefaultHlsExtractorFactory(0, true))
                .setLoadErrorHandlingPolicy(edgeRefusalPolicy)
            dataSourceForUa = ua
            dataSourceForHeaders = currentHeaders
        }
        return cachedHttpDataSource!!
    }

    /** HLS → caption-aware factory; everything else (raw MPEG-TS, etc.) → default. */
    private fun mediaSourceFor(url: String): MediaSource {
        httpDataSourceFor(currentUa) // ensure factories match current UA
        // Live latency (#72): a target live-edge offset for live streams (HLS/DASH). Ignored by
        // progressive/raw-TS sources, so it can only help where it applies. Unset (Balanced) keeps
        // Media3's own default: nothing in the app overrides the user's live latency any more. An earlier
        // attempt to auto-widen it away from a 403-ing live edge was tested and did nothing — the traced
        // panel refuses EVERY segment in its window, not just the newest (see [noteSegmentRefusal]).
        val targetOffsetSecs = liveBufferSecs
        val item = MediaItem.Builder().setUri(url).apply {
            targetOffsetSecs?.let {
                setLiveConfiguration(MediaItem.LiveConfiguration.Builder().setTargetOffsetMs(it * 1000L).build())
            }
        }.build()
        val uri = item.localConfiguration?.uri ?: run {
            activeIsHls = false
            return cachedDefaultFactory!!.createMediaSource(item)
        }
        // A panel already caught redirecting `.ts` → manifest goes straight to the HLS factory: without
        // this every channel on it repeats the container-unsupported failure + retry before recovering.
        val knownHlsHost = LiveStreamQuirks.isKnownHlsHost(url)
        val isHls = forceHlsForCurrentLoad || knownHlsHost || Util.inferContentType(uri) == C.CONTENT_TYPE_HLS
        activeIsHls = isHls
        LiveDiagnosticsLog.event(
            "media_source inferred=${if (isHls) "hls" else "progressive"} knownHlsHost=$knownHlsHost " +
                "targetOffsetSec=${targetOffsetSecs ?: -1} " +
                "url=${HttpClient.redactUrl(url)}",
        )
        return if (isHls) cachedHlsCcFactory!!.createMediaSource(item)
        else cachedDefaultFactory!!.createMediaSource(item)
    }

    private fun build(): ExoPlayer {
        // Tuned for raw MPEG-TS live (Xtream `.ts`, no HLS manifest): ONE long-lived HTTP response, where
        // ExoPlayer stops reading the socket once the buffer is full and resumes only after it drains back
        // to minBufferMs. Provider restreamers/proxies cull a connection that sits idle that long, and EOF
        // on a duration-less source surfaces as STATE_ENDED/IO error — a reconnect (visible glitch) every
        // few seconds on a channel that is otherwise healthy. HLS never hit this: each segment is its own
        // short request, so a pause between segments costs nothing.
        //
        // DefaultLoadControl (prioritizeTimeOverSizeThresholds = false, the default) resolves to:
        //     isLoading = !targetBytesReached && (buffered < min || (buffered < max && isLoading))
        // so the socket's idle window is (max − min) in wall-clock time, NOT the buffer depth. Hence a
        // NARROW window — buffering deeper would only park the socket longer, and a deep buffer cannot
        // hide the cull anyway (the EOF still forces a re-prepare).
        //
        // The byte cap does the same job for high-bitrate streams: on a ~25 Mbps UHD TS, TARGET_BUFFER_BYTES
        // is reached at under MIN_BUFFER_MS of media, so loading resumes on every drain — effectively a
        // continuous read. It also bounds what a 4K channel pins on the app heap.
        //
        // The start thresholds stay tiny (1s to first play, 2s after a rebuffer): they, not the buffer
        // depth, are what tuning and preview scrolling cost.
        val budget = playerBudget ?: PlayerBudget.of(context).also { playerBudget = it }
        //
        // F06/F07: those numbers used to be hardcoded, which is why the **Live latency** setting did
        // nothing at all for raw-TS live — it only ever set a `MediaItem.LiveConfiguration` offset, and
        // Media3 honours that for HLS/DASH only. The preset now drives the depth (keeping the 2 s idle
        // window at every setting), and the new "Pre-buffer" drives the start thresholds.
        // Balanced + Off reproduces the previous constants exactly.
        val lc = LiveBuffer.loadControlFor(liveBufferSecs, effectivePrerollSecs()).also { builtLoadControl = it }
        val defaultBytes = if (budget.lowSpec) LOW_RAM_TARGET_BYTES else TARGET_BUFFER_BYTES
        LiveDiagnosticsLog.event(
            "load_control min=${lc.minBufferMs} max=${lc.maxBufferMs} start=${lc.bufferForPlaybackMs} " +
                "restart=${lc.bufferForPlaybackAfterRebufferMs} preroll=${effectivePrerollSecs()}s " +
                "latencySec=${liveBufferSecs ?: -1}",
        )
        // Also to Logcat unconditionally: the diagnostics log is off in a release build, and these numbers
        // are the only way to tell "the setting didn't apply" from "the buffer filled that fast".
        android.util.Log.i(
            LiveDiagnosticsLog.TAG,
            "live_buffer preroll=${effectivePrerollSecs()}s start=${lc.bufferForPlaybackMs}ms " +
                "min=${lc.minBufferMs}ms max=${lc.maxBufferMs}ms latency=${liveBufferSecs ?: -1}",
        )
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                lc.minBufferMs,
                lc.maxBufferMs,
                lc.bufferForPlaybackMs,
                lc.bufferForPlaybackAfterRebufferMs,
            )
            .setTargetBufferBytes(LiveBuffer.targetBufferBytes(liveBufferSecs, defaultBytes))
            .build()
        // forceDisableMediaCodecAsynchronousQueueing(): Media3 runs MediaCodec asynchronously by default on
        // API 31+, which corrupts (macroblocks) some UHD-HEVC streams on Realtek/Amlogic VPUs — the
        // synchronous path is what players like TiviMate use to avoid it. Channels it still can't decode
        // cleanly are handed to mpv via the per-channel "force mpv" routing.
        //
        // The audio sink is pinned to stereo PCM when the user asked for "Stereo only" or when the
        // session latch has tripped. This is the half of the surround setting that never existed:
        // the old boolean only reached mpv, while Live TV's default engine is this one, so a TV that
        // mis-plays Dolby got exactly the same treatment however the switch was set.
        val renderers = OwnTVRenderersFactory(context, forceStereo = !AudioOutputPolicy.allowsMultichannel(surroundMode))
            .forceDisableMediaCodecAsynchronousQueueing()
            .apply {
                // Software first, hardware still in the list as a backstop — Media3 walks the decoder
                // list in order and falls through on failure, so this can only add a route.
                if (!hwDecodingEnabled) {
                    setMediaCodecSelector { mime, secure, tunneling ->
                        androidx.media3.exoplayer.mediacodec.MediaCodecSelector.DEFAULT
                            .getDecoderInfos(mime, secure, tunneling)
                            .sortedBy { it.hardwareAccelerated }
                    }
                }
            }
        return ExoPlayer.Builder(context)
            .setRenderersFactory(renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(httpDataSourceFor(currentUa)))
            .setLoadControl(loadControl)
            .build()
            .apply {
                // Media3's default ONLY_IF_SEAMLESS still issues Surface.setFrameRate() requests. Some
                // vendor stacks advertise a seamless switch but visibly re-handshake HDMI, so Off must
                // explicitly disable this second AFR mechanism as well as FrameRateController.
                setVideoChangeFrameRateStrategy(
                    if (autoFrameRateEnabled) C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS
                    else C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF,
                )
                addListener(listener); addAnalyticsListener(analytics); addAnalyticsListener(audioWatchdog)
                // Wire the per-frame tick to the video renderer so the health watchdog can tell a frozen
                // PICTURE (clock still running — invisible to a position-only check) from real playback.
                // Best-effort: if the renderer isn't found / doesn't accept the message, the position and
                // error/stall watchdogs still cover total freezes, so this can't make things worse.
                videoRenderer = (0 until rendererCount).map { getRenderer(it) }
                    .firstOrNull { it.trackType == C.TRACK_TYPE_VIDEO }
                if (videoRenderer == null) {
                    android.util.Log.w(LiveDiagnosticsLog.TAG, "frame hook NOT wired — no video renderer found; picture-freeze detection falls back to the no-progress backstop")
                }
                videoRenderer?.let { r ->
                    runCatching {
                        createMessage(r)
                            .setType(MediaCodecVideoRenderer.MSG_SET_VIDEO_FRAME_METADATA_LISTENER)
                            .setPayload(frameListener)
                            .send()
                    }.onFailure {
                        android.util.Log.w(LiveDiagnosticsLog.TAG, "frame hook send FAILED (${it.message}) — picture-freeze detection falls back to the no-progress backstop")
                    }
                }
            }
    }

    companion object {
        private const val MAX_VOLUME = 150          // same ceiling as mpv; 100–150 comes from LoudnessEnhancer
        private const val BOOST_MB_PER_PERCENT = 7  // millibels per boost percent — 150% ≈ +3.5 dB, mpv's own gain
        private const val MAX_RECONNECTS = 8        // ~consecutive failures before giving up (HUD Retry then)
        /** Playback must hold this long before the reconnect ladder is considered recovered. */
        internal const val HEALTHY_MS = 60_000L

        /**
         * The reconnect backoff ladder, in milliseconds. The old rule — `1500 * n` capped at 4 s — hammered
         * a dead feed eight times inside ~26 s and gave up, so a router reboot or a provider restart that
         * takes a minute always ended in "Lost connection". These steps span the ladder over ~35 s and
         * then hold at the last one, which comfortably outlives a typical blip.
         */
        private val RECONNECT_DELAYS_MS = longArrayOf(1_500L, 3_000L, 6_000L, 10_000L, 15_000L)

        /**
         * Delay before reconnect attempt [attempt] (1-based, as [retryCount] is post-increment). Attempts
         * past the ladder repeat its final step. Pure, so the schedule is unit-testable.
         */
        internal fun reconnectDelayMs(attempt: Int): Long =
            RECONNECT_DELAYS_MS[(attempt - 1).coerceIn(0, RECONNECT_DELAYS_MS.lastIndex)]

        /** HLS already performs request-level retries. Once a forbidden segment becomes fatal, fetch a
         * fresh manifest promptly instead of adding the generic outage ladder's 3–15 second UI freeze. */
        internal fun hlsHttpReconnectDelayMs(attempt: Int): Long =
            reconnectDelayMs(attempt).coerceAtMost(HLS_HTTP_RECONNECT_MAX_MS)

        internal fun isHlsResponse(url: String, contentType: String?): Boolean {
            val type = contentType.orEmpty().substringBefore(';').trim().lowercase()
            return type == "application/x-mpegurl" || type == "application/vnd.apple.mpegurl" ||
                url.substringBefore('?').endsWith(".m3u8", ignoreCase = true)
        }

        /** A segment URL a live playlist has already refused does not become valid by asking again at
         *  the same URL — with a signed, expiring token it provably never can. One quick retry covers a
         *  genuine blip, then let it go fatal so the mpv handoff can act instead of burning ~8 s on the
         *  stock ladder while the buffer drains. */
        internal fun edgeRefusalRetryDelayMs(errorCount: Int): Long =
            if (errorCount <= 1) EDGE_REFUSAL_RETRY_MS else C.TIME_UNSET

        private const val HLS_HTTP_RECONNECT_MAX_MS = 1_500L
        internal const val EDGE_REFUSAL_RETRY_MS = 500L

        /** How long to wait for a single-session panel to notice the other engine's socket is gone.
         *  Measured on the traced panel: the handoff's own ~500 ms was never enough, and the refusal
         *  persisted for the whole time mpv stayed connected — so this covers the release, not a poll. */
        internal const val SESSION_RELEASE_MS = 2_000L

        // --- LoadControl (see [build]) ----------------------------------------------------------
        /** Resume reading the socket once the buffer drains to this. */
        private const val MIN_BUFFER_MS = 8_000
        /** Stop reading at this. Only [MAX_BUFFER_MS] − [MIN_BUFFER_MS] above the resume point, so a raw-TS
         *  socket is never parked long enough for a provider to cull it. */
        private const val MAX_BUFFER_MS = 10_000
        /** Binds below [MIN_BUFFER_MS] on a UHD stream (≈7s at 25 Mbps) → a continuous read there, and a
         *  hard bound on what a 4K channel pins on the app heap. */
        private const val TARGET_BUFFER_BYTES = 24 * 1024 * 1024
        /** TV-class/low-RAM devices: still above ExoPlayer's ~13 MB video default. */
        private const val LOW_RAM_TARGET_BYTES = 16 * 1024 * 1024

        /** Grace for the old MediaCodec to tear down before its replacement is built (see [rebuildDecoderAndRetry]). */
        private const val DECODER_REBUILD_DELAY_MS = 500L

        private const val STALL_MS = 12_000L        // buffering this long after playing == a dropped feed
        private const val PROGRESS_CHECK_MS = 2_500L // poll interval for the silent-freeze watchdog
        private const val FROZEN_LIMIT = 3          // picture frozen this many polls (~7.5s) == a dropped feed
        private const val FREEZE_TIMEOUT_MS = 8_000L // zero forward progress this long while READY == dead feed
        private const val NO_VIDEO_TIMEOUT_MS = 8_000L // video track present, zero frames rendered this long == "audio plays, no picture"
        private const val FPS_BASELINE_MS = 500L
        private const val FPS_TICK_MS = 1_000L
        private const val FPS_MAX_ATTEMPTS = 5
    }
}
