package tv.own.owntv.player

import android.content.Context
import android.view.Surface
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tv.own.owntv.core.network.HttpClient
import tv.own.owntv.core.network.StreamHeaders
import java.util.Locale

/**
 * ExoPlayer (Media3) used **only** for the one case mpv's direct path can't handle: a VOD with an
 * **image-based** subtitle (PGS/VOBSUB/DVB) selected. ExoPlayer keeps the video on the same zero-copy
 * decoder→SurfaceView path *and* renders the bitmap subtitle on its own UI layer ([Cue]s → SubtitleView),
 * which mpv can't do without GL-compositing the whole 4K frame (the heavy path we removed).
 *
 * It is a **handoff**, not a sidecar: mpv is stopped first, so the provider only ever sees one connection
 * (IPTV panels routinely cap VOD at one). [OwnTVPlayer] owns this and mirrors its state into the same
 * StateFlows the HUD already observes, so the player UI is unchanged. All methods run on the main thread.
 *
 * It also serves as the **VOD engine fallback**: when mpv terminally fails to play a VOD (demuxer reject,
 * decoder stall, retry budget exhausted), [OwnTVPlayer] retries the same item here once ([start] with
 * `fallback = true`) before surfacing an error — some devices/streams play on ExoPlayer's MediaCodec
 * path where mpv's can't. In fallback mode no subtitle is auto-selected and errors are worded as
 * engine failures rather than subtitle failures.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class ExoSubtitleEngine(
    private val context: Context,
    private val streamingHttp: tv.own.owntv.core.network.StreamingHttpClient,
    private val budget: PlayerBudget,
    private val callbacks: Callbacks,
) {
    /** Hooks back into [OwnTVPlayer]'s StateFlows (all fire on the main thread). */
    interface Callbacks {
        fun onPlayingChanged(playing: Boolean)
        fun onBuffering(buffering: Boolean)
        fun onVideoSize(width: Int, height: Int)
        fun onPositionDuration(positionMs: Long, durationMs: Long)
        fun onFirstFrame()
        fun onCues(cues: List<Cue>)
        fun onAudioTracks(tracks: List<TrackOption>)
        /** Text/image subtitle tracks from the active file — [OwnTVPlayer] shows these in the HUD while
         *  this engine owns playback as a VOD engine (mpv never probed the file, so its list is empty). */
        fun onTextTracks(tracks: List<TrackOption>)
        fun onVideoFps(fps: Float)
        /** [decodeFailure] = this device could not decode the video, on hardware OR software (the
         *  software rung was already spent or refused). A verdict about the file+device, not about the
         *  network — so the owner may remember it, unlike a transient load error. */
        fun onError(message: String, decodeFailure: Boolean = false)
        /** Playback reached the end of the file (drives VOD auto-play-next while this engine is active). */
        fun onEnded()
    }

    private var player: ExoPlayer? = null
    private var surface: Surface? = null
    private var pendingSubLang: String? = null
    private var pendingSubTypeIndex: Int = -1
    private var subtitleApplied = false
    // Side-loaded external subtitle files (OpenSubtitles/local — subtitle plan §6.5/§10). ExoPlayer
    // can't attach a subtitle to a playing item, so each add/restore re-prepares the same URL with
    // SubtitleConfigurations at the current position (the plan's "seamless re-prepare").
    private data class ExternalSubCfg(val path: String, val title: String, val lang: String?)
    private var currentUrl: String? = null
    private val externalSubs = ArrayList<ExternalSubCfg>()
    // Label of a just-added external sub to select once its track appears in onTracksChanged.
    private var pendingExternalLabel: String? = null
    // Subtitle-timing offset for the active external sub (§8): applied by side-loading a
    // timestamp-shifted copy of that file on the next (re-)prepare.
    private var subDelayMs = 0
    private var delayLabel: String? = null
    // X2: shifted copies, keyed by source path + offset. Generating one reads, re-times and rewrites
    // the whole subtitle file — on a big ASS that is tens to hundreds of milliseconds, and
    // buildMediaItem runs on the main thread on every (re-)prepare, so it used to freeze the HUD on
    // every delay nudge. The work now happens on IO once per (file, offset) and the re-prepare waits
    // for it; returning to a previous offset is a map lookup.
    private val shiftedSubs = java.util.concurrent.ConcurrentHashMap<String, java.io.File>()
    private val shiftScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate,
    )
    private var shiftJob: kotlinx.coroutines.Job? = null

    private fun shiftKey(path: String, offsetMs: Int) = "$path|$offsetMs"
    // Engine-fallback playback (mpv terminally failed this VOD): no auto subtitle, engine-worded errors.
    private var fallbackMode = false
    // First-frame watchdog: this handoff only exists to show an image subtitle over otherwise-healthy
    // video, so if ExoPlayer never renders a frame (a format/decoder combo mpv handled fine but this
    // renderer can't), fall back rather than leaving the user on audio with a blank screen.
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var firstFrameSeen = false
    // X1: whether this file declares a video track at all. Radio stations filed under Movies, music
    // VOD and audio-only catch-up recordings have none — nothing is broken there and the watchdog
    // below must stay quiet. Assumed true until onTracksChanged says otherwise, so a file whose
    // tracks never arrive is still covered. Mirrors LivePreviewEngine's `hasVideo` gate.
    private var hasVideoTrack = true
    private val noVideoTimeout = Runnable {
        if (!firstFrameSeen && hasVideoTrack) {
            android.util.Log.w(
                TAG,
                "no video frame after ${noVideoTimeoutMs()}ms — falling back " +
                    "(decodedDrops=${currentDroppedFrames(player) - dropsBaseline} format=${player?.videoFormat?.sampleMimeType})",
            )
            // One software retry before giving up on this engine. "Audio plays, no frame ever renders"
            // is precisely what a hardware decoder does when it accepts a format it can't actually
            // handle — and a software decoder usually can. For a catch-up archive it is also the
            // mid-GOP signature (a software decoder picks up at the next keyframe), so the panel is
            // remembered and the session's remaining archives open in software straight away.
            val url = currentUrl
            if (softwareRungAvailable() && url != null) {
                if (isArchiveItem) LiveStreamQuirks.rememberArchiveNeedsSoftware(url)
                android.util.Log.w(TAG, "software rescue: no frame on the hardware decoder, restarting in software decode")
                onSoftwareRescue?.invoke(url, isArchiveItem)
                return@Runnable
            }
            callbacks.onError(
                "Audio is playing, but video could not be rendered on this device.",
                decodeFailure = true,
            )
        }
    }

    /**
     * Whether this item can still be retried on a software decoder in THIS engine.
     *
     * Already on software → the rung is spent (and `start` sets `softwarePreferred` for the retry, so
     * this is also what stops it looping). Above 1080p → refused, exactly as mpv's
     * `canTrySoftwareRescue` refuses it: software-decoding 4K on TV silicon is a slideshow, not a
     * rescue, and the time is better spent handing the item to the other engine. An unknown height is
     * allowed — by the time a decoder fails the format is normally known, and the no-frame watchdog
     * still catches a bad guess.
     */
    private fun softwareRungAvailable(): Boolean {
        if (softwarePreferred) return false
        val height = player?.videoFormat?.height ?: 0
        if (height > 1080) {
            android.util.Log.i(TAG, "software rescue declined: ${height}p is above the software-decode ceiling")
            return false
        }
        return true
    }

    /** How long to wait for the first frame. A catch-up archive gets far longer: it decodes in SOFTWARE
     *  and starts mid-GOP, so the decoder must chew through inter-frames until the next keyframe before
     *  it can render anything — on a low-spec box that can comfortably exceed the normal 8 s budget. */
    private fun noVideoTimeoutMs(): Long =
        if (softwarePreferred) NO_VIDEO_TIMEOUT_SOFTWARE_MS else NO_VIDEO_TIMEOUT_MS
    // Maps the audio-track id the HUD selects (== its ordinal in the list we publish) → the ExoPlayer
    // track group + index to override. Rebuilt whenever the track list changes.
    private var audioSelections: List<AudioSel> = emptyList()

    private data class AudioSel(val id: Int, val group: TrackGroup, val trackIndex: Int)

    val isActive: Boolean get() = player != null

    private val throughputTracker = ThroughputTracker()
    private val fpsSample = FpsSample()
    private var dropsBaseline = 0
    private val analytics = object : androidx.media3.exoplayer.analytics.AnalyticsListener {
        override fun onVideoDecoderInitialized(eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
            // Which decoder actually won the selector, and how long it took to come up. The name is the
            // only reliable way to tell a software decoder (c2.android.* / OMX.google.*) from the vendor
            // hardware one, and mid-GOP archives hinge on getting the former.
            android.util.Log.i(TAG, "video decoder: $decoderName (init ${initializationDurationMs}ms, software=${softwarePreferred})")
            dropsBaseline = currentDroppedFrames(player) // a new decoder session may start its own counters
        }

        override fun onVideoInputFormatChanged(
            eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
            format: androidx.media3.common.Format,
            decoderReuseEvaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?,
        ) {
            android.util.Log.i(TAG, "video input format: ${format.sampleMimeType} ${format.width}x${format.height} fps=${format.frameRate}")
        }
    }

    /** Declared fps if present, else a live measurement. */
    fun currentFps(): Float? {
        val p = player ?: return null
        return p.videoFormat?.frameRate?.takeIf { it > 0 } ?: fpsSample.sample(p)
    }

    /** Declared bitrate in Mbps for the top-bar chip, or null when the provider didn't set one (raw
     *  MPEG-TS). Declared-only by design: measuring live throughput on every playback drags 4K, so the
     *  debug overlay is the sole place a measured value is shown (and only while it's open). */
    fun currentBitrateMbps(): Double? =
        player?.videoFormat?.bitrate?.takeIf { it > 0 }?.let { it / 1_000_000.0 }

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            callbacks.onPlayingChanged(isPlaying)
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            callbacks.onBuffering(playbackState == Player.STATE_BUFFERING)
            if (playbackState == Player.STATE_READY) emitPositionDuration()
            if (playbackState == Player.STATE_ENDED) callbacks.onEnded()
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (videoSize.width > 0 && videoSize.height > 0) {
                callbacks.onVideoSize(videoSize.width, videoSize.height)
            }
            // Report the video frame rate so the display can switch to match it (kills 24fps-on-60Hz judder).
            player?.videoFormat?.frameRate?.let { if (it > 0f) callbacks.onVideoFps(it) }
        }

        override fun onRenderedFirstFrame() {
            firstFrameSeen = true
            mainHandler.removeCallbacks(noVideoTimeout)
            callbacks.onFirstFrame()
        }

        override fun onCues(cueGroup: CueGroup) {
            callbacks.onCues(cueGroup.cues)
        }

        override fun onTracksChanged(tracks: Tracks) {
            updateVideoTrackPresence(tracks)
            rebuildAudioTracks(tracks)
            rebuildTextTracks(tracks)
            applyPendingSubtitle(tracks)
        }

        override fun onPlayerError(error: PlaybackException) {
            android.util.Log.w(TAG, "ExoPlayer error: ${error.errorCodeName}", error)
            // A decode-class failure gets this engine's software rung before playback leaves it. The
            // old behaviour handed EVERY Exo error straight to mpv, so a file the software decoder
            // could play was thrown at the other engine to discover that — the ladder is now
            // Exo hardware → Exo software → mpv hardware → mpv copy → mpv software.
            // Network / source / DRM errors are NOT retried here: a different decoder cannot fix them,
            // and mpv (with its own retry ladder and different HTTP stack) is the better next step.
            val url = currentUrl
            if (error.errorCode in DECODE_ERROR_CODES && softwareRungAvailable() && url != null) {
                android.util.Log.w(TAG, "software rescue: ${error.errorCodeName} on the hardware decoder, restarting in software decode")
                onSoftwareRescue?.invoke(url, isArchiveItem)
                return
            }
            callbacks.onError(friendlyError(error), decodeFailure = error.errorCode in DECODE_ERROR_CODES)
        }
    }

    /** Build (if needed) and start playback of [url] at [positionMs] on [surface], selecting the image
     *  subtitle identified by [subLang]/[subTypeIndex] (the track the user picked in mpv's list).
     *  [fallback] = engine-fallback playback after a terminal mpv failure: no subtitle is auto-selected
     *  (pass subLang = null, subTypeIndex = -1) and errors are worded as engine failures. */
    fun start(
        url: String, positionMs: Long, surface: Surface, subLang: String?, subTypeIndex: Int,
        fallback: Boolean = false,
        // External subtitle files to side-load from the first prepare (engine toggle carry-over, §10);
        // [selectExternalLabel] names the one to select, or null to attach them all unselected.
        sideloadSubs: List<OwnTVPlayer.ExternalSub> = emptyList(),
        selectExternalLabel: String? = null,
        /** Decode this item on a software decoder — see [ownTVRenderers]. */
        preferSoftware: Boolean = false,
        /** This item is a catch-up / live-rewind archive: mid-GOP, so a blank picture on the hardware
         *  decoder is rescued in software instead of erroring — see [noVideoTimeout]. */
        isArchive: Boolean = false,
    ) {
        // "Hardware decoding = Off" used to reach mpv only, so a user who turned it off to work around a
        // broken vendor decoder still got that decoder the moment playback landed on ExoPlayer. The
        // selector puts software first and keeps hardware as a backstop, so this can only add a route.
        softwarePreferred = preferSoftware || !hwDecodingEnabled
        isArchiveItem = isArchive
        this.surface = surface
        fallbackMode = fallback
        pendingSubLang = subLang
        pendingSubTypeIndex = subTypeIndex
        subtitleApplied = false
        firstFrameSeen = false
        hasVideoTrack = true
        throughputTracker.reset(); fpsSample.resetAll(); dropsBaseline = currentDroppedFrames(player)
        audioWatchdog.reset()
        mainHandler.removeCallbacks(noVideoTimeout)
        mainHandler.postDelayed(noVideoTimeout, noVideoTimeoutMs())

        // Applied per item, before prepare: the factory is created once but each load builds a fresh
        // data source from it, so a changed UA/header set takes effect without rebuilding the player.
        applyRequestHeaders()

        currentUrl = url
        externalSubs.clear()
        sideloadSubs.forEach { externalSubs.add(ExternalSubCfg(it.path, it.title, it.lang)) }
        pendingExternalLabel = selectExternalLabel
        subDelayMs = 0
        delayLabel = null

        // The renderer factory (and so the decoder selector) is baked in at construction: if this item
        // wants the other decode path than the cached player was built for, drop and rebuild it.
        // The audio sink's capabilities are baked in at construction too, so the same rule applies: a
        // cached player built before the session latched to stereo would keep the sink that failed.
        val wantStereo = !AudioOutputPolicy.allowsMultichannel(surroundMode)
        if (player != null && (builtForSoftware != softwarePreferred || builtForStereo != wantStereo)) {
            android.util.Log.i(TAG, "rebuilding ExoPlayer for ${if (softwarePreferred) "software" else "hardware"} decode, ${if (wantStereo) "stereo" else "device"} audio")
            player?.release()
            player = null
        }
        val p = player ?: build().also { player = it; builtForSoftware = softwarePreferred; builtForStereo = wantStereo }
        p.setVideoSurface(surface)
        p.setMediaItem(buildMediaItem(url))
        p.prepare()
        if (positionMs > 0) p.seekTo(positionMs)
        p.playWhenReady = true
    }

    private fun buildMediaItem(url: String): MediaItem {
        val builder = MediaItem.Builder().setUri(url)
        if (externalSubs.isNotEmpty()) {
            builder.setSubtitleConfigurations(externalSubs.map { s ->
                // Timing offset for the active external sub: side-load a timestamp-shifted copy (§8).
                // X2 — never generate it here (main thread); setSubtitleDelayMs prepares the copy off
                // the main thread and only re-prepares once it is in the cache. The original is the
                // fallback, which is also what shiftedCopy itself returns on a parse/IO failure.
                val file = if (s.title == delayLabel && subDelayMs != 0) {
                    shiftedSubs[shiftKey(s.path, subDelayMs)] ?: java.io.File(s.path)
                } else java.io.File(s.path)
                MediaItem.SubtitleConfiguration.Builder(android.net.Uri.fromFile(file))
                    .setMimeType(subtitleMime(s.path))
                    .setLabel(s.title)
                    .apply { s.lang?.let { setLanguage(it) } }
                    .build()
            })
        }
        return builder.build()
    }

    private fun subtitleMime(path: String): String = when (path.substringAfterLast('.').lowercase()) {
        "ass", "ssa" -> androidx.media3.common.MimeTypes.TEXT_SSA
        "vtt", "webvtt" -> androidx.media3.common.MimeTypes.TEXT_VTT
        else -> androidx.media3.common.MimeTypes.APPLICATION_SUBRIP
    }

    /** Attach + select an external subtitle file (plan §6.5): re-prepare the same URL with the sub
     *  side-loaded, at the same position, and select it by label once its track appears. */
    fun addExternalSubtitle(path: String, title: String, lang: String?) {
        val p = player ?: return
        val url = currentUrl ?: return
        if (externalSubs.none { it.path == path }) externalSubs.add(ExternalSubCfg(path, title, lang))
        pendingExternalLabel = title
        subtitleApplied = false
        reprepareKeepingPosition(p, url)
    }

    /** Re-attach previously downloaded subtitles WITHOUT changing the current selection (plan §9 —
     *  they show in the Subtitles list; the user re-picks). No-op when nothing new to attach. */
    fun restoreExternalSubtitles(subs: List<OwnTVPlayer.ExternalSub>) {
        val p = player ?: return
        val url = currentUrl ?: return
        var added = false
        subs.forEach { s ->
            if (externalSubs.none { it.path == s.path }) {
                externalSubs.add(ExternalSubCfg(s.path, s.title, s.lang)); added = true
            }
        }
        if (!added) return
        // Capture the currently-selected text track (by ordinal) so applyPendingSubtitle re-applies it
        // after the re-prepare — a TrackSelectionOverride holds TrackGroup instances, which the new
        // prepare replaces, so without this the default selector could auto-pick a sub the user never chose.
        var idx = 0; var selIdx = -1; var selLang: String? = null
        for (group in p.currentTracks.groups) {
            if (group.type != C.TRACK_TYPE_TEXT) continue
            for (i in 0 until group.length) {
                if (group.isTrackSelected(i)) { selIdx = idx; selLang = group.getTrackFormat(i).language }
                idx++
            }
        }
        pendingSubTypeIndex = selIdx // -1 + null lang → applyPendingSubtitle keeps text OFF
        pendingSubLang = if (selIdx >= 0) selLang else null
        pendingExternalLabel = null
        subtitleApplied = false
        reprepareKeepingPosition(p, url)
    }

    /** Apply a subtitle-timing offset to the active external sub [activeLabel] (§8): re-prepare with a
     *  shifted copy of its file at the same position, then re-select it. The caller debounces. */
    fun setSubtitleDelayMs(ms: Int, activeLabel: String) {
        val p = player ?: return
        val url = currentUrl ?: return
        if (subDelayMs == ms && delayLabel == activeLabel) return
        val source = externalSubs.firstOrNull { it.title == activeLabel }?.path
        subDelayMs = ms
        delayLabel = activeLabel
        pendingExternalLabel = activeLabel // re-select after the re-prepare
        subtitleApplied = false
        // Nothing to generate (offset cleared, unknown label) or already generated: re-prepare now.
        if (source == null || ms == 0 || shiftedSubs.containsKey(shiftKey(source, ms))) {
            reprepareKeepingPosition(p, url)
            return
        }
        // X2 — build the shifted copy on IO, then re-prepare. A newer offset arriving meanwhile
        // cancels this one, and the staleness check stops a late result from re-preparing over it.
        shiftJob?.cancel()
        shiftJob = shiftScope.launch {
            val shifted = withContext(kotlinx.coroutines.Dispatchers.IO) {
                SubtitleShift.shiftedCopy(context, java.io.File(source), ms)
            }
            shiftedSubs[shiftKey(source, ms)] = shifted
            if (subDelayMs != ms || delayLabel != activeLabel) return@launch
            val current = player ?: return@launch
            reprepareKeepingPosition(current, currentUrl ?: return@launch)
        }
    }

    private fun reprepareKeepingPosition(p: ExoPlayer, url: String) {
        val pos = p.currentPosition.coerceAtLeast(0)
        val wasPlaying = p.playWhenReady
        p.setMediaItem(buildMediaItem(url), pos)
        p.prepare()
        p.playWhenReady = wasPlaying
    }

    /** Decode path requested for the item being started, and the one the built [player] actually holds —
     *  the renderer factory is fixed at construction, so a change forces a rebuild in [start]. */
    private var softwarePreferred = false
    private var isArchiveItem = false
    /** Mirrors Settings → Video player → Hardware decoding, pushed in by [OwnTVPlayer]. */
    @Volatile var hwDecodingEnabled = true
    private var builtForSoftware = false

    /** The per-source User-Agent and the per-channel headers of the item mpv handed over, pushed in by
     *  [OwnTVPlayer] (F16). Without them a stream that only opens with a custom UA/Referer on mpv would
     *  fail the moment the engine fallback took over. */
    @Volatile var userAgent: String? = null
    @Volatile var httpHeaders: Map<String, String> = emptyMap()
    private var httpFactory: OkHttpDataSource.Factory? = null

    private fun applyRequestHeaders() {
        val factory = httpFactory ?: return
        val perChannelUa = StreamHeaders.userAgentOf(httpHeaders)
        factory.setUserAgent(perChannelUa ?: userAgent?.takeIf { it.isNotBlank() } ?: HttpClient.DEFAULT_USER_AGENT)
        // Always set — an empty map clears the previous item's headers rather than leaking them.
        factory.setDefaultRequestProperties(httpHeaders.filterKeys { !it.equals("User-Agent", ignoreCase = true) })
    }
    /** Whether the cached player's audio sink was pinned to stereo PCM. See the rebuild check in `start`. */
    private var builtForStereo = false

    private fun build(): ExoPlayer {
        // OkHttp for the stream itself, wrapped in DefaultDataSource so file:// URIs (side-loaded
        // external subtitle files in app storage) route to FileDataSource — the bare OkHttp factory
        // can't open them, and Media3 swallows a side-loaded subtitle's load failure silently (the
        // track lists but never produces cues). The TransferListener stays on the inner OkHttp factory
        // so local subtitle-file bytes don't inflate the measured network bitrate.
        val http = OkHttpDataSource.Factory(streamingHttp.client)
            .setUserAgent(HttpClient.DEFAULT_USER_AGENT)
            .setTransferListener(throughputTracker)
        httpFactory = http
        applyRequestHeaders()
        val dataSource = androidx.media3.datasource.DefaultDataSource.Factory(context, http)
        // Match mpv's buffering depth so stability doesn't drop after the handoff (Dev refinement #3).
        val maxBufferMs = (budget.cacheSecs.toIntOrNull() ?: 30) * 1000
        val minBufferMs = (maxBufferMs / 2).coerceIn(15_000, maxBufferMs)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(minBufferMs, maxBufferMs, 2_500, 5_000)
            .build()
        val trackSelector = DefaultTrackSelector(context).apply {
            // Honor the user's preferred languages where present; subtitle selection is forced explicitly.
            parameters = buildUponParameters().build()
        }
        // Software decoding is a rescue, not the default. A catch-up archive starts mid-GOP and SOME
        // TV-class hardware decoders can't recover from that — the Realtek OMX decoder accepts the
        // format, plays the audio, then never emits a video frame ("setPortMode ... DynamicANWBuffer
        // failed", "BAD CODEC: stride 1920 -> 64") — so [noVideoTimeout] catches that case and restarts
        // the item here with softwarePreferred set, which resyncs at the next keyframe.
        // Stereo pinning mirrors the live engine: "Stereo only", or a session latch tripped by ANY engine,
        // means this player must not be given a sink that can bitstream Dolby/DTS.
        val renderers = ownTVRenderers(
            context,
            forceStereo = !AudioOutputPolicy.allowsMultichannel(surroundMode),
            softwareFirst = softwarePreferred,
        )
        return ExoPlayer.Builder(context)
            .setRenderersFactory(renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource))
            .setLoadControl(loadControl)
            .setTrackSelector(trackSelector)
            .build()
            .apply { addListener(listener); addAnalyticsListener(analytics); addAnalyticsListener(audioWatchdog) }
    }

    /** Re-point ExoPlayer at a (re)created surface, or null to release it (surfaceDestroyed). */
    fun setSurface(surface: Surface?) {
        this.surface = surface
        if (surface != null) player?.setVideoSurface(surface) else player?.clearVideoSurface()
    }

    fun play() { player?.play() }
    fun pause() { player?.pause() }
    fun togglePlayPause() { player?.let { if (it.isPlaying) it.pause() else it.play() } }

    fun seekTo(positionMs: Long) { player?.seekTo(positionMs.coerceAtLeast(0)); emitPositionDuration() }
    fun seekBy(deltaMs: Long) {
        val p = player ?: return
        p.seekTo((p.currentPosition + deltaMs).coerceAtLeast(0))
        emitPositionDuration()
    }

    fun setSpeed(speed: Double) { player?.setPlaybackSpeed(speed.toFloat()) }

    /** Set output volume. mpv uses 0–150 (%) with software boost; ExoPlayer is a 0–1 linear gain, so
     *  values above 100% just clamp to full (no boost in the handoff). */
    fun setVolume(percent: Int) { player?.volume = (percent / 100f).coerceIn(0f, 1f) }

    /** Called on a ~0.5s tick by [OwnTVPlayer] while active, so the HUD scrubber advances. */
    fun emitPositionDuration() {
        val p = player ?: return
        val dur = p.duration.let { if (it == C.TIME_UNSET) 0L else it }
        callbacks.onPositionDuration(p.currentPosition.coerceAtLeast(0), dur.coerceAtLeast(0))
        // The audio-output safety net rides the tick that is already running. On a hit the whole session
        // latches to stereo and the owner restarts this item — the sink's capabilities are decided at
        // construction, so nothing short of a rebuild can undo a bad choice.
        audioWatchdog.poll(p.isPlaying)?.let { reason ->
            android.util.Log.w("ExoSubtitleEngine", "audio watchdog: $reason — forcing stereo for this session")
            AudioOutputPolicy.latchStereo("exo/vod: $reason")
            PlaybackErrorLog.event(context, "ExoPlayer", live = false, what = "Switched to stereo audio", detail = reason)
            onAudioFallback?.invoke("Your TV or soundbar couldn't play this audio — switched to stereo.")
        }
    }

    /** The user's Auto / Stereo only / Surround choice, pushed in by [OwnTVPlayer]; read at build time. */
    @Volatile var surroundMode: SurroundMode = SurroundMode.AUTO

    /** Fired once when the audio watchdog forces stereo; the owner shows the message and restarts. */
    var onAudioFallback: ((String) -> Unit)? = null

    /** Fired when this item failed on the hardware decoder and the software rung is still available;
     *  the owner restarts it here with `preferSoftware`. [fromStart] when the item must restart at 0
     *  rather than resume (a catch-up archive has no Range support, so re-opening at an offset fails).
     *
     *  This is ExoPlayer's own software rung, the mirror of mpv's [OwnTVPlayer] rescue ladder: without
     *  it a file that only a software decoder can handle left the preferred engine immediately, even
     *  though the very next thing tried (mpv in software) proved software decoding was the answer. */
    var onSoftwareRescue: ((url: String, fromStart: Boolean) -> Unit)? = null

    private val audioWatchdog = AudioWatchdog()

    /** Select an audio track by the id we published in [Callbacks.onAudioTracks]. */
    fun selectAudio(id: Int) {
        val p = player ?: return
        val sel = audioSelections.firstOrNull { it.id == id } ?: return
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(sel.group, listOf(sel.trackIndex)))
            .build()
    }

    /** X1: audio-only media is a valid state, not a device fault — cancel the no-video watchdog for
     *  it. Only a file that *declares* a video track and never renders a frame is a real problem. */
    private fun updateVideoTrackPresence(tracks: Tracks) {
        if (tracks.groups.isEmpty()) return // nothing known yet — keep the watchdog armed
        val hasVideo = tracks.groups.any { it.type == C.TRACK_TYPE_VIDEO }
        hasVideoTrack = hasVideo
        if (!hasVideo) {
            android.util.Log.i(TAG, "no video track in this file — audio-only, no-video watchdog disarmed")
            mainHandler.removeCallbacks(noVideoTimeout)
        }
    }

    private fun rebuildAudioTracks(tracks: Tracks) {
        val out = ArrayList<TrackOption>()
        val sels = ArrayList<AudioSel>()
        var id = 0
        for (group in tracks.groups) {
            if (group.type != C.TRACK_TYPE_AUDIO) continue
            for (i in 0 until group.length) {
                val format = group.getTrackFormat(i)
                val label = audioLabel(format.label, format.language, id)
                out.add(TrackOption(label = label, mpvId = id, selected = group.isTrackSelected(i)))
                sels.add(AudioSel(id, group.mediaTrackGroup, i))
                id++
            }
        }
        audioSelections = sels
        callbacks.onAudioTracks(out)
    }

    /** Enumerate the file's text/image subtitle tracks for the HUD menu. [TrackOption.mpvId] and
     *  [TrackOption.typeIndex] are both the ordinal among text tracks — [selectTextTrack] selects by it. */
    private fun rebuildTextTracks(tracks: Tracks) {
        val out = ArrayList<TrackOption>()
        var id = 0
        for (group in tracks.groups) {
            if (group.type != C.TRACK_TYPE_TEXT) continue
            for (i in 0 until group.length) {
                val format = group.getTrackFormat(i)
                val mime = format.sampleMimeType
                val image = mime == androidx.media3.common.MimeTypes.APPLICATION_PGS ||
                    mime == androidx.media3.common.MimeTypes.APPLICATION_VOBSUB ||
                    mime == androidx.media3.common.MimeTypes.APPLICATION_DVBSUBS
                // Side-loaded external subs keep their configured label verbatim ("Bengali — OpenSubtitles")
                // — audioLabel would append the language again, and the engine-toggle carry-over matches
                // the selected row back to the session's external subs by exact title.
                val external = format.label != null && externalSubs.any { it.title == format.label }
                out.add(TrackOption(
                    label = if (external) format.label!! else audioLabel(format.label, format.language, id),
                    mpvId = id, selected = group.isTrackSelected(i), image = image,
                    lang = format.language, typeIndex = id,
                ))
                id++
            }
        }
        callbacks.onTextTracks(out)
    }

    /** Force-select the image subtitle track that matches the one the user picked in mpv's list:
     *  prefer the same ordinal among text tracks, then fall back to language. */
    private fun applyPendingSubtitle(tracks: Tracks) {
        if (subtitleApplied) return
        val p = player ?: return
        // A just-side-loaded external subtitle: select it by its label (set on the SubtitleConfiguration).
        pendingExternalLabel?.let { label ->
            for (group in tracks.groups) {
                if (group.type != C.TRACK_TYPE_TEXT) continue
                for (i in 0 until group.length) {
                    if (group.getTrackFormat(i).label == label) {
                        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, listOf(i)))
                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                            .build()
                        pendingExternalLabel = null
                        subtitleApplied = true
                        return
                    }
                }
            }
            return // its track hasn't appeared in this update yet — wait for the next onTracksChanged
        }
        // Engine-fallback playback with no subtitle picked: keep text tracks OFF rather than letting the
        // "?: textTracks.first()" recovery below auto-select one the user never asked for.
        if (pendingSubTypeIndex < 0 && pendingSubLang == null) {
            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .build()
            subtitleApplied = true
            return
        }
        // Flatten text tracks in declaration order so the mpv sub ordinal lines up with ExoPlayer's.
        data class TextTrack(val group: TrackGroup, val index: Int, val lang: String?)
        val textTracks = ArrayList<TextTrack>()
        for (group in tracks.groups) {
            if (group.type != C.TRACK_TYPE_TEXT) continue
            for (i in 0 until group.length) {
                textTracks.add(TextTrack(group.mediaTrackGroup, i, group.getTrackFormat(i).language))
            }
        }
        if (textTracks.isEmpty()) return
        val target = textTracks.getOrNull(pendingSubTypeIndex)
            ?: pendingSubLang?.let { lang -> textTracks.firstOrNull { it.lang.equals(lang, ignoreCase = true) } }
            ?: textTracks.first()
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(target.group, listOf(target.index)))
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .build()
        subtitleApplied = true
    }

    /** HUD subtitle pick while fallback playback is active: select the text/image subtitle by its
     *  ordinal among this file's text tracks (mpv's typeIndex lines up with ExoPlayer's order). */
    fun selectTextTrack(typeIndex: Int, lang: String?) {
        pendingSubTypeIndex = typeIndex
        pendingSubLang = lang
        subtitleApplied = false
        player?.let { applyPendingSubtitle(it.currentTracks) }
    }

    /** HUD "subtitles off" while fallback playback is active. */
    fun disableTextTracks() {
        pendingSubTypeIndex = -1
        pendingSubLang = null
        subtitleApplied = true
        val p = player ?: return
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
    }

    private fun audioLabel(title: String?, lang: String?, id: Int): String {
        val l = lang?.takeIf { it.isNotBlank() && it != "und" }
            ?.let { runCatching { Locale.forLanguageTag(it).displayLanguage }.getOrNull()?.ifBlank { it } ?: it }
        return listOfNotNull(title?.takeIf { it.isNotBlank() }, l).joinToString(" · ").ifBlank { "Track ${id + 1}" }
    }

    fun setBitrateTrackingEnabled(enabled: Boolean) = throughputTracker.setEnabled(enabled)

    /** Technical readout for the stream-info overlay while this engine owns playback (main thread only —
     *  the overlay polls from composition). Mirrors [LivePreviewEngine.streamInfo]'s format. */
    fun streamInfo(): List<Pair<String, String>> {
        val p = player ?: return emptyList()
        val out = ArrayList<Pair<String, String>>()
        p.videoFormat?.let { f ->
            val fps = currentFps()
            val line = listOfNotNull(
                f.sampleMimeType?.substringAfterLast('/')?.let { mimeName(it) },
                if (f.width > 0 && f.height > 0) "${f.width}×${f.height}" else null,
                fps?.let { "%.2f fps".format(it) },
            ).joinToString(" · ")
            if (line.isNotBlank()) out += "Video" to line
            when (f.colorInfo?.colorTransfer) {
                C.COLOR_TRANSFER_ST2084 -> "HDR10 (PQ)"; C.COLOR_TRANSFER_HLG -> "HLG"; else -> null
            }?.let { out += "HDR" to it }
            out += bitrateRow(f, throughputTracker)
        }
        p.audioFormat?.let { f ->
            val line = listOfNotNull(
                f.sampleMimeType?.substringAfterLast('/')?.uppercase(),
                when (f.channelCount) { 1 -> "mono"; 2 -> "stereo"; 6 -> "5.1"; 8 -> "7.1"; else -> null },
                if (f.sampleRate > 0) "%.0f kHz".format(f.sampleRate / 1000.0) else null,
            ).joinToString(" · ")
            if (line.isNotBlank()) out += "Audio" to line
        }
        bufferRow(p, dropsBaseline)?.let { out += it }
        // Same readout as the live engine: who is decoding the audio, and whether the safety net fired.
        out += "Audio out" to buildString {
            append(if (audioWatchdog.passthrough) "passthrough (TV/receiver decodes)" else "decoded in app")
            append(" · ")
            append(if (AudioOutputPolicy.allowsMultichannel(surroundMode)) "multichannel allowed" else "stereo only")
            AudioOutputPolicy.latchReason?.let { append(" · fell back: $it") }
        }
        return out
    }

    private fun mimeName(m: String) = when (m.lowercase()) {
        "hevc" -> "HEVC"; "avc" -> "H.264"; "av01" -> "AV1"; "x-vnd.on2.vp9", "vp9" -> "VP9"
        "mp4v-es" -> "MPEG-4"; "mpeg2" -> "MPEG-2"; else -> m.uppercase()
    }

    private fun friendlyError(error: PlaybackException): String = when (error.errorCode) {
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED ->
            if (fallbackMode) "ExoPlayer can't decode this video's format on this device (${error.errorCodeName})."
            else "Image subtitles aren't available for this file's format."
        else ->
            if (fallbackMode) "ExoPlayer couldn't play this stream (${error.errorCodeName})."
            else "Couldn't show image subtitles for this file."
    }

    /** Stop and free the ExoPlayer (the handoff back to mpv, or stop()). Keeps the instance? No —
     *  fully release so we never hold a second decoder/connection while mpv plays. */
    fun stop() {
        surface = null
        mainHandler.removeCallbacks(noVideoTimeout)
        callbacks.onCues(emptyList())
        player?.let { p ->
            p.removeListener(listener)
            p.clearVideoSurface()
            p.release()
        }
        player = null
        audioSelections = emptyList()
        fallbackMode = false
        currentUrl = null
        externalSubs.clear()
        pendingExternalLabel = null
        subDelayMs = 0
        delayLabel = null
        // X2 — the shifted copies only make sense for the session that generated them; drop them so
        // they don't accumulate in cacheDir. The name guard keeps a fallback result (which is the
        // user's own subtitle file) safe from deletion.
        shiftJob?.cancel()
        shiftJob = null
        shiftedSubs.values.forEach { f -> runCatching { if (f.name.startsWith("subshift_")) f.delete() } }
        shiftedSubs.clear()
    }

    fun release() = stop()

    private companion object {
        const val TAG = "ExoSubtitleEngine"
        const val NO_VIDEO_TIMEOUT_MS = 8_000L
        /** Mid-GOP + software decode needs a much longer first-frame budget — see [noVideoTimeoutMs]. */
        const val NO_VIDEO_TIMEOUT_SOFTWARE_MS = 25_000L

        /** Failures a different (software) decoder can plausibly fix — see the software rescue in
         *  `onPlayerError`. Everything else (network, source, DRM, renderer/timeout) goes to mpv. */
        val DECODE_ERROR_CODES = setOf(
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        )
    }
}
