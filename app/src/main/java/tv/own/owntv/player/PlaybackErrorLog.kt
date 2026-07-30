package tv.own.owntv.player

import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * Small rolling on-disk history of playback failures (last [MAX] entries), so a user who dismissed
 * the error screen — or whose app restarted — can still read/report what happened from
 * Settings → "Playback error log". Most TV users can't pull logcat; this is their only record.
 *
 * Plain JSON file in filesDir; all IO runs on a single background thread so the player's error
 * path never blocks. Timestamps are wall-clock (for display), unlike the monotonic diagnostics.
 */
object PlaybackErrorLog {
    private const val MAX = 10
    private const val FILE_NAME = "playback_errors.json"

    data class Entry(
        val atMs: Long,
        val engine: String,
        val live: Boolean,
        val reason: PlayerFailureReason?,
        /** Legacy single-string diagnostic, retained for old files only. */
        val spec: String?,
        val raw: String?,
        val model: String,
        val android: String,
        /** Structured fields written by current builds. */
        val codec: String? = null,
        val resolution: String? = null,
        val decoderKind: String? = null,
        val decoderName: String? = null,
        val decoderHardware: Boolean = false,
        val decoderDirect: Boolean = false,
    ) {
        /** Converts new stable fields back to the semantic presentation model. */
        fun mediaSpec(): MediaSpec? {
            val kind = decoderKind?.let { runCatching { DecoderKind.valueOf(it) }.getOrNull() }
            val decoder = when (kind) {
                DecoderKind.HARDWARE -> DecoderSpec.Hardware(direct = decoderDirect)
                DecoderKind.SOFTWARE -> DecoderSpec.Software(gpu = !decoderDirect)
                DecoderKind.NAMED, null -> (decoderName ?: decoderKind)?.let {
                    // Unknown future decoder ids remain visible as raw names rather than being
                    // silently dropped from the user's diagnostic history.
                    DecoderSpec.Named(it, hardware = decoderHardware, direct = decoderDirect)
                }
            }
            val structured = MediaSpec(codec = codec, resolution = resolution, decoder = decoder)
                .takeIf { it.codec != null || it.resolution != null || it.decoder != null }
            return structured ?: legacyMediaSpec()
        }

        /**
         * Reads the pre-structured `spec` format without making it the new persistence contract.
         * Known decoder forms are rendered through the current localized mapper; an unrecognised
         * legacy string remains available through the Settings screen's explicit raw fallback.
         */
        private fun legacyMediaSpec(): MediaSpec? {
            val fields = spec?.split(" • ")?.map(String::trim)?.filter(String::isNotEmpty) ?: return null
            if (fields.isEmpty()) return null
            val decoderToken = fields.last()
            val decoder = when {
                decoderToken == "hardware" || decoderToken == "hardware:direct" ->
                    DecoderSpec.Hardware(direct = decoderToken.endsWith(":direct"))
                decoderToken == "software" || decoderToken == "software:gpu" ->
                    DecoderSpec.Software(gpu = decoderToken.endsWith(":gpu"))
                decoderToken.endsWith(":hardware") ->
                    DecoderSpec.Named(decoderToken.removeSuffix(":hardware"), hardware = true)
                else -> null
            }
            val technical = if (decoder != null) fields.dropLast(1) else fields
            return MediaSpec(
                codec = technical.getOrNull(0),
                resolution = technical.getOrNull(1),
                decoder = decoder,
            ).takeIf { it.codec != null || it.resolution != null || it.decoder != null }
        }
    }

    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "owntv-errorlog").apply { isDaemon = true } }

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)

    /** Append an error (fire-and-forget; trims to the newest [MAX]). */
    fun log(context: Context, engine: String, live: Boolean, info: ErrorInfo) {
        if (info.reason == null && info.raw == null) return // nothing useful to keep
        val appContext = context.applicationContext
        io.execute {
            runCatching {
                val entries = readSync(appContext).toMutableList()
                entries.add(
                    Entry(
                        atMs = System.currentTimeMillis(),
                        engine = engine,
                        live = live,
                        reason = info.reason,
                        // Persist stable semantic fields, not a localized/English diagnostic sentence.
                        spec = null,
                        raw = info.raw,
                        codec = info.spec?.codec,
                        resolution = info.spec?.resolution,
                        decoderKind = info.spec?.decoder?.storageKind,
                        decoderName = (info.spec?.decoder as? DecoderSpec.Named)?.value,
                        decoderHardware = info.spec?.decoder?.isHardware == true,
                        decoderDirect = info.spec?.decoder?.isDirect == true,
                        model = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
                        android = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
                    ),
                )
                writeSync(appContext, entries.takeLast(MAX))
            }
        }
    }

    /** Newest-first list for the Settings viewer. Safe to call from a coroutine on Dispatchers.IO. */
    fun read(context: Context): List<Entry> = runCatching { readSync(context).reversed() }.getOrDefault(emptyList())

    /** Synchronous (tiny file delete) so a viewer re-read right after can't race a queued delete. */
    fun clear(context: Context) {
        runCatching { file(context.applicationContext).delete() }
    }

    private fun readSync(context: Context): List<Entry> {
        val f = file(context)
        if (!f.exists()) return emptyList()
        val arr = JSONArray(f.readText())
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            Entry(
                atMs = o.optLong("atMs"),
                engine = o.optString("engine"),
                live = o.optBoolean("live"),
                reason = o.optString("reason").takeIf { it.isNotEmpty() }?.let { key ->
                    runCatching { PlayerFailureReason.valueOf(key) }.getOrNull()
                },
                spec = o.optString("spec").takeIf { it.isNotEmpty() },
                raw = o.optString("raw").takeIf { it.isNotEmpty() },
                model = o.optString("model"),
                android = o.optString("android"),
                codec = o.optString("codec").takeIf { it.isNotEmpty() },
                resolution = o.optString("resolution").takeIf { it.isNotEmpty() },
                decoderKind = o.optString("decoderKind").takeIf { it.isNotEmpty() },
                decoderName = o.optString("decoderName").takeIf { it.isNotEmpty() },
                decoderHardware = o.optBoolean("decoderHardware"),
                decoderDirect = o.optBoolean("decoderDirect"),
            )
        }
    }

    private fun writeSync(context: Context, entries: List<Entry>) {
        val arr = JSONArray()
        entries.forEach { e ->
            arr.put(
                JSONObject()
                    .put("atMs", e.atMs)
                    .put("engine", e.engine)
                    .put("live", e.live)
                    .put("reason", e.reason?.name ?: "")
                    // `spec` remains for backward compatibility with pre-structured entries.
                    .put("spec", e.spec ?: "")
                    .put("codec", e.codec ?: "")
                    .put("resolution", e.resolution ?: "")
                    .put("decoderKind", e.decoderKind ?: "")
                    .put("decoderName", e.decoderName ?: "")
                    .put("decoderHardware", e.decoderHardware)
                    .put("decoderDirect", e.decoderDirect)
                    .put("raw", e.raw ?: "")
                    .put("model", e.model)
                    .put("android", e.android),
            )
        }
        file(context).writeText(arr.toString())
    }
}

private val DecoderSpec.storageKind: String
    get() = when (this) {
        is DecoderSpec.Hardware -> DecoderKind.HARDWARE.name
        is DecoderSpec.Software -> DecoderKind.SOFTWARE.name
        is DecoderSpec.Named -> DecoderKind.NAMED.name
    }

private val DecoderSpec.isHardware: Boolean
    get() = when (this) {
        is DecoderSpec.Hardware -> true
        is DecoderSpec.Software -> false
        is DecoderSpec.Named -> hardware
    }

private val DecoderSpec.isDirect: Boolean
    get() = when (this) {
        is DecoderSpec.Hardware -> direct
        is DecoderSpec.Software -> !gpu
        is DecoderSpec.Named -> direct
    }
