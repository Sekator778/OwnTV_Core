package tv.own.owntv.player

import android.media.MediaCodecList
import android.os.Build
import java.util.concurrent.ConcurrentHashMap

/**
 * Whether a `MediaCodec` decoder name belongs to a hardware decoder — answered from what the device
 * actually offers, never from what the user asked for.
 *
 * The distinction is load-bearing because [ownTVRenderers] enables Media3's decoder fallback: when the
 * vendor decoder throws while configuring, playback continues on the *next* decoder the device offers,
 * which is normally a software one. That rescue is deliberate and worth having — but it is silent, and
 * the only symptom is a picture that degrades. Any readout that infers the decoder kind from the
 * **setting** then reports "hardware" while software is what is running, which is precisely the state a
 * support report has to be able to name.
 *
 * The answer never changes for a given name, so results are cached.
 */
object DecoderNames {

    private val cache = ConcurrentHashMap<String, Boolean>()

    /** Android's own software decoders, whatever the vendor-prefix rule below would otherwise say. */
    private val SOFTWARE_PREFIXES = listOf("omx.google.", "c2.android.", "c2.google.", "omx.ffmpeg.", "arc.")

    /** Everything else under a codec prefix is the vendor's — i.e. the real VPU. */
    private val VENDOR_PREFIXES = listOf("omx.", "c2.")

    /**
     * `true` = hardware, `false` = software, `null` = the name says nothing we can trust.
     *
     * Unknown is reported as unknown on purpose: a confidently wrong "software" in a diagnostic sends
     * the next reader down the wrong path, which costs more than saying nothing.
     */
    fun isHardware(name: String): Boolean? {
        if (name.isBlank()) return null
        val key = name.lowercase()
        cache[key]?.let { return it }
        val resolved = classify(key) ?: return null
        cache[key] = resolved
        return resolved
    }

    /**
     * Name rules first, the platform list only for names they don't cover. The prefixes classify every
     * decoder a real TV ships, so the common path never pays for enumerating the codec list — and that
     * enumeration would otherwise run on the thread delivering the decoder-initialised callback.
     */
    private fun classify(lower: String): Boolean? {
        if (SOFTWARE_PREFIXES.any { lower.startsWith(it) }) return false
        if (lower.contains(".sw.")) return false // e.g. OMX.SEC.avc.sw.dec
        if (VENDOR_PREFIXES.any { lower.startsWith(it) }) return true
        return platformCodecs[lower]
    }

    /** Authoritative, but only from API 29 — below it the name is all there is. */
    private val platformCodecs: Map<String, Boolean> by lazy {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return@lazy emptyMap()
        runCatching {
            MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
                .filter { !it.isEncoder }
                .associate { it.name.lowercase() to it.isHardwareAccelerated }
        }.getOrDefault(emptyMap())
    }
}
