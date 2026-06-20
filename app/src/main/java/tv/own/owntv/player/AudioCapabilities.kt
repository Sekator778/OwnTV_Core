package tv.own.owntv.player

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build

/**
 * Which compressed audio formats the *current* audio output can play back directly (bitstream
 * passthrough) — e.g. an Atmos TV or an AV receiver over HDMI. Used to build mpv's `audio-spdif` list
 * so we only pass through what the sink can decode, and otherwise fall back to PCM (the default).
 */
object AudioCapabilities {

    // mpv `audio-spdif` codec name → the Android encoding to probe. eac3 also carries Dolby Atmos
    // (E-AC3 JOC) when the sink supports it; truehd/dts-hd are lossless (rare outside Blu-ray rips).
    private val CANDIDATES: List<Pair<String, Int>> = listOf(
        "eac3" to AudioFormat.ENCODING_E_AC3,
        "ac3" to AudioFormat.ENCODING_AC3,
        "dts" to AudioFormat.ENCODING_DTS,
        "dts-hd" to AudioFormat.ENCODING_DTS_HD,
        "truehd" to AudioFormat.ENCODING_DOLBY_TRUEHD,
    )

    /** Comma-separated mpv `audio-spdif` value for the formats the output supports, or "" if none /
     *  unknown / pre-Android 10 (where we can't reliably query, so we stay safe and decode to PCM). */
    fun spdifCodecs(context: Context): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return "" // isDirectPlaybackSupported needs API 29
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
            .build()
        return CANDIDATES.filter { (_, encoding) ->
            runCatching {
                val format = AudioFormat.Builder()
                    .setEncoding(encoding)
                    .setSampleRate(48_000)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_5POINT1)
                    .build()
                AudioTrack.isDirectPlaybackSupported(format, attrs)
            }.getOrDefault(false)
        }.joinToString(",") { it.first }
    }
}
