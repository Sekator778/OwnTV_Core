package tv.own.owntv.player

import android.os.SystemClock
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * Tails the app's OWN logcat for the below-the-engine playback failures the player objects can't expose:
 * Android **MediaCodec** (e.g. `Codec reported err 0x80001000`) and **AudioTrack** errors. mpv/ExoPlayer sit
 * on top of MediaCodec, so a hardware decode/audio failure only names itself in the system log — and most
 * users can't run adb. Reading your *own* process's logs needs no permission.
 *
 * Best-effort: if logcat can't be spawned (rare/locked-down devices), it silently no-ops and the player
 * falls back to the engine's own error text. A single daemon reader thread, tag-filtered to stay cheap.
 */
class PlayerDiagnostics {
    private data class Entry(val atMs: Long, val tag: String, val text: String)

    private val ring = ConcurrentLinkedDeque<Entry>()
    @Volatile private var started = false
    @Volatile private var loadStartMs = 0L

    /** Start tailing (idempotent). Call once the player initialises. */
    fun start() {
        if (started) return
        started = true
        // Self-healing: some boxes kill the logcat process mid-session, which would otherwise silently
        // end diagnostics for the rest of the app's life. Restart with backoff; a run that survived a
        // while resets the attempt counter, so only genuinely-blocked devices give up.
        Thread({
            var attempt = 0
            while (attempt < MAX_TAIL_RESTARTS) {
                val ranAt = SystemClock.elapsedRealtime()
                readLoop()
                attempt = if (SystemClock.elapsedRealtime() - ranAt >= STABLE_RUN_MS) 1 else attempt + 1
                SystemClock.sleep(TAIL_RESTART_DELAY_MS)
            }
        }, "owntv-logcat").apply { isDaemon = true; start() }
    }

    /** Mark the start of a new stream load, so [recentError] only reports failures from the CURRENT item. */
    fun markLoad() { loadStartMs = SystemClock.elapsedRealtime() }

    private fun readLoop() {
        runCatching {
            // `-T 1`: start near "now" (don't replay the whole backlog). Tag-filtered to codec/audio errors.
            val cmd = listOf(
                "logcat", "-v", "tag", "-T", "1",
                "MediaCodec:E", "ACodec:E", "OMXClient:E", "CCodec:E", "Codec2Client:E", "C2PlatformStore:E",
                "MediaCodecRenderer:E", "MediaCodecVideoRenderer:E", "MediaCodecAudioRenderer:E",
                "AudioTrack:W", "AudioSink:E", "AudioFlinger:E", "*:S",
            )
            val proc = ProcessBuilder(cmd).redirectErrorStream(true).start()
            BufferedReader(InputStreamReader(proc.inputStream)).useLines { lines -> lines.forEach(::record) }
        }
    }

    private fun record(line: String) {
        // `-v tag` format: "E/MediaCodec: Codec reported err 0x80001000, actionCode 0, …"
        val slash = line.indexOf('/')
        if (slash != 1) return
        val colon = line.indexOf(':', slash)
        if (colon < 0) return
        val tag = line.substring(slash + 1, colon).trim()
        val text = line.substring(colon + 1).trim()
        if (text.isEmpty()) return
        ring.addLast(Entry(SystemClock.elapsedRealtime(), tag, text))
        while (ring.size > 80) ring.pollFirst()
    }

    /** The most recent codec/audio error from the current stream (≤15 s old), raw "tag: text"; null if none.
     *  [PlayerErrors.reasonFor] is applied by the consumer so all error sources share one humanization map. */
    fun recentError(): String? {
        // elapsedRealtime is monotonic — the wall clock can jump on NTP sync and skew this window.
        val cutoff = maxOf(loadStartMs, SystemClock.elapsedRealtime() - 15_000)
        val e = ring.descendingIterator().asSequence().firstOrNull { it.atMs >= cutoff } ?: return null
        return "${e.tag}: ${e.text}"
    }

    private companion object {
        const val MAX_TAIL_RESTARTS = 5
        const val TAIL_RESTART_DELAY_MS = 5_000L
        /** A tail that lived this long was healthy — reset the restart budget. */
        const val STABLE_RUN_MS = 60_000L
    }
}

/** Semantic decoder details for the media specification shown by the playback error renderer. */
sealed interface DecoderSpec {
    data class Hardware(val direct: Boolean = false) : DecoderSpec
    data class Software(val gpu: Boolean = false) : DecoderSpec
    data class Named(val value: String, val hardware: Boolean = false, val direct: Boolean = false) : DecoderSpec
}

/** Technical media details. It deliberately carries values, not a locale-resolved sentence. */
data class MediaSpec(
    val codec: String?,
    val resolution: String?,
    val decoder: DecoderSpec?,
)

/** Typed playback failures. Fixed OwnTV wording is resolved by the Compose HUD; raw provider/engine text stays raw. */
sealed interface PlaybackFailure {
    data object Channel : PlaybackFailure
    data object LostConnection : PlaybackFailure
    data object StreamLink : PlaybackFailure
    data object NotStreaming : PlaybackFailure
    data object AudioNoVideo : PlaybackFailure
    data object FileCorrupt : PlaybackFailure
    data object MultipleVideos : PlaybackFailure
    data object DecoderBusy : PlaybackFailure
    data object NoInternet : PlaybackFailure
    data object Surround : PlaybackFailure
    data object ImageSubtitleAudio : PlaybackFailure
    data object ImageFormat : PlaybackFailure
    data object ImageShow : PlaybackFailure
    data object BothEnginesExoFirst : PlaybackFailure
    data class BothEnginesMpvFirst(val exoError: PlaybackFailure) : PlaybackFailure
    data class ExoDecode(val code: String) : PlaybackFailure
    data class ExoPlay(val code: String) : PlaybackFailure
    data class HardwareFallback(val resolution: String) : PlaybackFailure
    data class HardwareDisabled(val resolution: String) : PlaybackFailure
    data class HardwareFormat(val resolution: String, val codec: String) : PlaybackFailure
    data class StreamUnavailable(val customUserAgentHint: Boolean) : PlaybackFailure
    /** Fixed mpv diagnostics produced by OwnTV, not provider text; resolve at the UI boundary. */
    data object MpvOpenDecode : PlaybackFailure
    data object MpvStreamNeverStarted : PlaybackFailure
    /** Genuine unknown engine/provider text; intentionally not translated. */
    data class Raw(val message: String) : PlaybackFailure
}

/** A playback failure broken into semantic reason, technical media details, and raw engine text. */
data class ErrorInfo(val reason: PlayerFailureReason?, val spec: MediaSpec?, val raw: String?)

/** Semantic playback failures; wording belongs to the presentation layer. */
enum class PlayerFailureReason {
    DECODER_BUSY,
    DECODER_TRANSIENT,
    UNSUPPORTED_VIDEO,
    DECODER_MEMORY,
    DRM,
    HTTP_509,
    HTTP_403,
    HTTP_401,
    HTTP_404,
    HTTP_400,
    SSL,
    FORMAT,
    NETWORK,
    AUDIO,
    SOFTWARE_FALLBACK,
    COPY_MODE_FALLBACK,
    ARCHIVE_SOFTWARE_FALLBACK,
    STEREO_FALLBACK,
    VOD_ENGINE_SESSION_FALLBACK,
    ONE_SESSION_PROVIDER,
    MPV_HANDOFF,
    LIVE_FALLBACK,
    LIVE_NO_FALLBACK,
    STREAM_REPORT,
}

/** Maps cryptic playback-failure strings to semantic state. Comparison needles stay English because
 * they are protocol/log inputs, never display text. */
object PlayerErrors {
    private val HTTP_STATUS_RX =
        Regex("""(?:\bhttp(?: error)? |\bresponse code[:= ]+|\bstatus(?: code)?[:= ]+)(\d{3})\b""")
    /** MediaCodec ENOMEM forms: "err -12", "status -12", "error -12" — NOT any "-12" substring
     *  (which matched URLs and timestamps like "-123ms"). MediaCodec usually logs the errno as
     *  unsigned hex instead ("err 0xfffffff4"), so accept that spelling too. */
    private val ENOMEM_RX = Regex("""\b(?:err(?:or)?|status|code)\s*[:=]?\s*(?:-12|0xfffffff4)\b""")

    fun classify(raw: String): PlayerFailureReason? {
        val l = raw.lowercase()
        val httpCode = HTTP_STATUS_RX.find(l)?.groupValues?.get(1)
        return when {
            "0x80001000" in l -> PlayerFailureReason.DECODER_BUSY
            "0x80001001" in l -> PlayerFailureReason.DECODER_TRANSIENT
            "0xfffffff3" in l || "0xffffffea" in l || "format_unsupported" in l || "omx_errorformat" in l -> PlayerFailureReason.UNSUPPORTED_VIDEO
            "enomem" in l || "out of memory" in l || "no memory" in l || "insufficient" in l ||
                "0xfffffff4" in l || ENOMEM_RX.containsMatchIn(l) -> PlayerFailureReason.DECODER_MEMORY
            "error_key" in l || "cryptoinfo" in l || "0x80001100" in l || ("drm" in l && "error" in l) -> PlayerFailureReason.DRM
            httpCode == "509" -> PlayerFailureReason.HTTP_509
            httpCode == "403" -> PlayerFailureReason.HTTP_403
            httpCode == "401" -> PlayerFailureReason.HTTP_401
            httpCode == "404" -> PlayerFailureReason.HTTP_404
            httpCode == "400" -> PlayerFailureReason.HTTP_400
            "certificate verify failed" in l || ("ssl" in l && "certif" in l) || "cert_" in l -> PlayerFailureReason.SSL
            "unrecognized file format" in l || "invalid data found" in l -> PlayerFailureReason.FORMAT
            "connection refused" in l || "connection reset" in l || "timed out" in l || "timeout" in l -> PlayerFailureReason.NETWORK
            "audiotrack" in l || "audiosink" in l || "audioflinger" in l || "audio codec" in l ->
                PlayerFailureReason.AUDIO
            else -> null
        }
    }

    fun reasonFor(raw: String): PlayerFailureReason? = classify(raw)
}
