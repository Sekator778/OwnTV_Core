package tv.own.owntv.player

import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import tv.own.owntv.core.network.HttpClient
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
    private const val MAX = 25
    private const val FILE_NAME = "playback_errors.json"
    private const val EXPORT_NAME = "owntv-playback-report.txt"

    /** What an entry is. The log used to hold hard failures only, which is exactly why a "the picture
     *  judders / the sound drifts" report produced an empty log (F18). */
    enum class Kind {
        /** Playback stopped with an error screen. */
        ERROR,

        /** Playback carried on, but something notable happened — a decode rescue, an engine handoff,
         *  the audio safety net firing. These are the events a quality complaint needs. */
        EVENT,

        /** The user pressed "Report this stream" in the player: a full snapshot of a *working* stream. */
        REPORT,
        ;

        companion object {
            fun from(name: String?): Kind = entries.firstOrNull { it.name.equals(name, true) } ?: ERROR
        }
    }

    data class Entry(
        val atMs: Long,
        val engine: String,
        val live: Boolean,
        val reason: String?,
        val spec: String?,
        val raw: String?,
        val model: String,
        val android: String,
        val kind: Kind = Kind.ERROR,
    )

    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "owntv-errorlog").apply { isDaemon = true } }

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)

    /** Append an error (fire-and-forget; trims to the newest [MAX]). */
    fun log(context: Context, engine: String, live: Boolean, info: ErrorInfo) {
        if (info.reason == null && info.raw == null) return // nothing useful to keep
        append(context, engine, live, Kind.ERROR, info.reason, info.spec, info.raw)
    }

    /**
     * Record something that happened *without* stopping playback — a decode rescue, an engine handoff,
     * the stereo safety net. [what] is the headline ("Fell back to software decoding"), [detail] the
     * technical line under it.
     *
     * This is the other half of F18: a user whose picture judders or whose sound drifts has no failure to
     * report, so the log stayed empty and every report became a guessing game. Callers must keep these
     * rare — one per genuine event, never per frame or per retry tick.
     */
    fun event(context: Context, engine: String, live: Boolean, what: String, detail: String? = null) {
        append(context, engine, live, Kind.EVENT, what, null, detail)
    }

    /** The player's "Report this stream" action: a snapshot of a stream that is playing right now. */
    fun report(context: Context, engine: String, live: Boolean, title: String?, snapshot: String) {
        append(context, engine, live, Kind.REPORT, "Stream report", title, snapshot)
    }

    private fun append(
        context: Context,
        engine: String,
        live: Boolean,
        kind: Kind,
        reason: String?,
        spec: String?,
        raw: String?,
    ) {
        val appContext = context.applicationContext
        io.execute {
            runCatching {
                val entries = readSync(appContext).toMutableList()
                entries.add(
                    Entry(
                        atMs = System.currentTimeMillis(),
                        engine = engine,
                        live = live,
                        reason = reason?.let(HttpClient::redactUrl),
                        spec = spec?.let(HttpClient::redactUrl),
                        raw = raw?.let(HttpClient::redactUrl),
                        model = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
                        android = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
                        kind = kind,
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
                // Sanitize while reading too: older versions persisted mpv failure lines containing
                // the account embedded in an Xtream /live|movie/user/password/ URL.
                reason = o.optString("reason").takeIf { it.isNotEmpty() }?.let(HttpClient::redactUrl),
                spec = o.optString("spec").takeIf { it.isNotEmpty() }?.let(HttpClient::redactUrl),
                raw = o.optString("raw").takeIf { it.isNotEmpty() }?.let(HttpClient::redactUrl),
                model = o.optString("model"),
                android = o.optString("android"),
                // Written since the diagnostics phase; anything older is a hard error by definition.
                kind = Kind.from(o.optString("kind").takeIf { it.isNotEmpty() }),
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
                    .put("reason", e.reason ?: "")
                    .put("spec", e.spec ?: "")
                    .put("raw", e.raw ?: "")
                    .put("model", e.model)
                    .put("android", e.android)
                    .put("kind", e.kind.name),
            )
        }
        file(context).writeText(arr.toString())
    }

    /**
     * Write the whole log — plus the live diagnostics ring, when it has anything — as plain text to the
     * app's **external** files dir, and return the file. That directory needs no permission and no root
     * to read back:
     *
     * ```
     * adb pull /sdcard/Android/data/tv.own.owntv/files/owntv-playback-report.txt
     * ```
     *
     * A TV has nowhere to "share" to and no clipboard worth the name, so a pullable file plus the path
     * on screen is the export that actually works. Runs synchronously — the caller is a dialog button
     * on a background dispatcher, and the file is a few kB.
     */
    fun export(context: Context): File? = runCatching {
        val appContext = context.applicationContext
        val dir = appContext.getExternalFilesDir(null) ?: appContext.filesDir
        val out = File(dir, EXPORT_NAME)
        val stamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
        val text = buildString {
            appendLine("OwnTV playback report")
            appendLine("${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Exported ${stamp.format(java.util.Date())}")
            appendLine()
            read(appContext).forEach { e ->
                appendLine("[${stamp.format(java.util.Date(e.atMs))}] ${e.kind} · ${e.engine} · ${if (e.live) "live" else "vod"}")
                e.reason?.let { appendLine("  reason: $it") }
                e.spec?.let { appendLine("  spec  : $it") }
                e.raw?.let { appendLine("  raw   : $it") }
                appendLine()
            }
            val live = LiveDiagnosticsLog.snapshot()
            if (live.isNotBlank()) {
                appendLine("--- live diagnostics (most recent) ---")
                appendLine(live)
            }
        }
        out.writeText(text)
        out
    }.getOrNull()
}
