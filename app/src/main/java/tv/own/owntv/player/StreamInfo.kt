package tv.own.owntv.player

/** Stable labels and typed values for the technical stream overlay. */
enum class StreamInfoLabel { ENGINE, FORMAT, SOURCE, VIDEO, HDR, BITRATE, DECODER, AUDIO, AUDIO_OUTPUT, BUFFER, LIVE_BUFFER }

enum class StreamEngine { MPV, EXOPLAYER }

enum class StreamEngineMode { NORMAL, PREFERRED, FALLBACK, IMAGE_SUBTITLE_HANDOFF }

enum class StreamHdrMode { HDR10_PQ, HLG, SDR }

enum class DecoderKind { HARDWARE, SOFTWARE, NAMED }

enum class AudioOutputKind { PASSTHROUGH, DECODED_IN_APP, PCM }

sealed interface StreamInfoValue {
    data class Engine(val engine: StreamEngine, val mode: StreamEngineMode = StreamEngineMode.NORMAL) : StreamInfoValue
    data class Format(val name: String) : StreamInfoValue
    data class Source(val url: String) : StreamInfoValue
    data class Video(
        val codec: String? = null,
        val width: Int? = null,
        val height: Int? = null,
        val fps: Double? = null,
        val bitDepth: Int? = null,
    ) : StreamInfoValue
    data class Hdr(val mode: StreamHdrMode) : StreamInfoValue
    data class Bitrate(val bitsPerSecond: Long) : StreamInfoValue
    data class Decoder(
        val kind: DecoderKind,
        val name: String? = null,
        val direct: Boolean = false,
        /** True when the software decoder is followed by the GPU rendering path. */
        val gpu: Boolean = false,
        /** True only when [kind] is NAMED and the engine identified a hardware decoder. */
        val hardware: Boolean = false,
        /**
         * True only when [kind] is NAMED and the engine identified a *software* decoder. Both flags stay
         * false when the decoder's kind could not be established, so an unknown name reads as the bare
         * name rather than as a guess.
         */
        val software: Boolean = false,
    ) : StreamInfoValue
    data class Audio(
        val codec: String? = null,
        val channelCount: Int? = null,
        val sampleRateHz: Int? = null,
        val bitsPerSecond: Long? = null,
    ) : StreamInfoValue
    data class AudioOutput(
        val kind: AudioOutputKind,
        val channelCount: Int? = null,
        val multichannelAllowed: Boolean,
        val fallbackReason: String? = null,
    ) : StreamInfoValue
    data class Buffer(val bufferedMs: Long? = null, val droppedFrames: Long? = null) : StreamInfoValue
    data class LiveBuffer(
        val prerollEnabled: Boolean,
        val prerollSeconds: Double? = null,
        val depthSeconds: Double? = null,
        val readaheadSeconds: Double? = null,
        val playlistOverride: Boolean = false,
    ) : StreamInfoValue
    /** Only for genuinely unknown technical/provider text; fixed OwnTV prose must not use this. */
    data class Raw(val text: String) : StreamInfoValue
}

data class StreamInfoRow(val label: StreamInfoLabel, val value: StreamInfoValue)
