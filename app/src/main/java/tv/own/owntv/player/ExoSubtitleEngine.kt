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
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import okhttp3.OkHttpClient
import tv.own.owntv.core.network.HttpClient
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
@OptIn(UnstableApi::class)
class ExoSubtitleEngine(
    private val context: Context,
    private val okHttpClient: OkHttpClient,
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
        fun onError(message: String)
        /** Playback reached the end of the file (drives VOD auto-play-next while this engine is active). */
        fun onEnded()
    }

    private var player: ExoPlayer? = null
    private var surface: Surface? = null
    private var pendingSubLang: String? = null
    private var pendingSubTypeIndex: Int = -1
    private var subtitleApplied = false
    // Engine-fallback playback (mpv terminally failed this VOD): no auto subtitle, engine-worded errors.
    private var fallbackMode = false
    // First-frame watchdog: this handoff only exists to show an image subtitle over otherwise-healthy
    // video, so if ExoPlayer never renders a frame (a format/decoder combo mpv handled fine but this
    // renderer can't), fall back rather than leaving the user on audio with a blank screen.
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var firstFrameSeen = false
    private val noVideoTimeout = Runnable {
        if (!firstFrameSeen) {
            android.util.Log.w(TAG, "no video frame after ${NO_VIDEO_TIMEOUT_MS}ms — falling back")
            callbacks.onError("Audio is playing, but video could not be rendered on this device.")
        }
    }
    // Maps the audio-track id the HUD selects (== its ordinal in the list we publish) → the ExoPlayer
    // track group + index to override. Rebuilt whenever the track list changes.
    private var audioSelections: List<AudioSel> = emptyList()

    private data class AudioSel(val id: Int, val group: TrackGroup, val trackIndex: Int)

    val isActive: Boolean get() = player != null

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
            rebuildAudioTracks(tracks)
            rebuildTextTracks(tracks)
            applyPendingSubtitle(tracks)
        }

        override fun onPlayerError(error: PlaybackException) {
            android.util.Log.w(TAG, "ExoPlayer error: ${error.errorCodeName}", error)
            callbacks.onError(friendlyError(error))
        }
    }

    /** Build (if needed) and start playback of [url] at [positionMs] on [surface], selecting the image
     *  subtitle identified by [subLang]/[subTypeIndex] (the track the user picked in mpv's list).
     *  [fallback] = engine-fallback playback after a terminal mpv failure: no subtitle is auto-selected
     *  (pass subLang = null, subTypeIndex = -1) and errors are worded as engine failures. */
    fun start(url: String, positionMs: Long, surface: Surface, subLang: String?, subTypeIndex: Int, fallback: Boolean = false) {
        this.surface = surface
        fallbackMode = fallback
        pendingSubLang = subLang
        pendingSubTypeIndex = subTypeIndex
        subtitleApplied = false
        firstFrameSeen = false
        mainHandler.removeCallbacks(noVideoTimeout)
        mainHandler.postDelayed(noVideoTimeout, NO_VIDEO_TIMEOUT_MS)

        val p = player ?: build().also { player = it }
        p.setVideoSurface(surface)
        p.setMediaItem(MediaItem.fromUri(url))
        p.prepare()
        if (positionMs > 0) p.seekTo(positionMs)
        p.playWhenReady = true
    }

    private fun build(): ExoPlayer {
        val dataSource = OkHttpDataSource.Factory(okHttpClient)
            .setUserAgent(HttpClient.DEFAULT_USER_AGENT)
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
        return ExoPlayer.Builder(context)
            .setRenderersFactory(DefaultRenderersFactory(context))
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource))
            .setLoadControl(loadControl)
            .setTrackSelector(trackSelector)
            .build()
            .apply { addListener(listener) }
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
    }

    /** Select an audio track by the id we published in [Callbacks.onAudioTracks]. */
    fun selectAudio(id: Int) {
        val p = player ?: return
        val sel = audioSelections.firstOrNull { it.id == id } ?: return
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(sel.group, listOf(sel.trackIndex)))
            .build()
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
                out.add(TrackOption(
                    label = audioLabel(format.label, format.language, id),
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
            ?.let { runCatching { Locale(it).displayLanguage }.getOrNull()?.ifBlank { it } ?: it }
        return listOfNotNull(title?.takeIf { it.isNotBlank() }, l).joinToString(" · ").ifBlank { "Track ${id + 1}" }
    }

    /** Technical readout for the stream-info overlay while this engine owns playback (main thread only —
     *  the overlay polls from composition). Mirrors [LivePreviewEngine.streamInfo]'s format. */
    fun streamInfo(): List<Pair<String, String>> {
        val p = player ?: return emptyList()
        val out = ArrayList<Pair<String, String>>()
        p.videoFormat?.let { f ->
            val line = listOfNotNull(
                f.sampleMimeType?.substringAfterLast('/')?.let { mimeName(it) },
                if (f.width > 0 && f.height > 0) "${f.width}×${f.height}" else null,
                if (f.frameRate > 0) "%.2f fps".format(f.frameRate) else null,
            ).joinToString(" · ")
            if (line.isNotBlank()) out += "Video" to line
            when (f.colorInfo?.colorTransfer) {
                C.COLOR_TRANSFER_ST2084 -> "HDR10 (PQ)"; C.COLOR_TRANSFER_HLG -> "HLG"; else -> null
            }?.let { out += "HDR" to it }
            if (f.bitrate > 0) out += "Bitrate" to "%.1f Mbps".format(f.bitrate / 1_000_000.0)
        }
        p.audioFormat?.let { f ->
            val line = listOfNotNull(
                f.sampleMimeType?.substringAfterLast('/')?.uppercase(),
                when (f.channelCount) { 1 -> "mono"; 2 -> "stereo"; 6 -> "5.1"; 8 -> "7.1"; else -> null },
                if (f.sampleRate > 0) "%.0f kHz".format(f.sampleRate / 1000.0) else null,
            ).joinToString(" · ")
            if (line.isNotBlank()) out += "Audio" to line
        }
        if (p.totalBufferedDuration > 0) out += "Buffer" to "%.1f s".format(p.totalBufferedDuration / 1000.0)
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
    }

    fun release() = stop()

    private companion object {
        const val TAG = "ExoSubtitleEngine"
        const val NO_VIDEO_TIMEOUT_MS = 8_000L
    }
}
