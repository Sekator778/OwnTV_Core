package tv.own.owntv.core.parser

import android.util.Base64
import android.util.JsonReader
import android.util.JsonToken
import android.util.Log
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.network.HttpClient
import java.io.InputStream
import java.net.URLEncoder

// --- Parsed Xtream models ---
data class XtCategory(val id: String, val name: String)
data class XtLiveStream(
    val streamId: String, val name: String, val icon: String?, val epgChannelId: String?,
    val categoryId: String?, val num: Int?,
    /** `tv_archive` = 1 → catch-up available; `tv_archive_duration` = days of archive kept. */
    val archive: Boolean = false, val archiveDays: Int = 0,
)
data class XtVod(
    val streamId: String, val name: String, val icon: String?, val rating: Double?, val plot: String?,
    val categoryId: String?, val containerExt: String?, val added: Long?,
)
data class XtSeries(
    val seriesId: String, val name: String, val cover: String?, val plot: String?,
    val rating: Double?, val categoryId: String?, val year: Int?,
)
data class XtEpisode(
    val id: String, val seasonNumber: Int, val episodeNumber: Int, val title: String, val containerExt: String?,
)
data class XtSeriesInfo(val episodes: List<XtEpisode>)
data class XtEpgEntry(val title: String, val description: String?, val startMs: Long, val stopMs: Long)

/**
 * Xtream Codes `player_api.php` client. Category lists are small and collected to a list; the large
 * stream lists are streamed object-by-object with [android.util.JsonReader] and pushed to a callback,
 * so a 340k-channel response never sits fully in memory.
 */
class XtreamClient(private val http: HttpClient) {

    // --- Categories ---
    suspend fun liveCategories(s: SourceEntity, onProgress: ((Long, Long?) -> Unit)? = null) = categories(s, "get_live_categories", onProgress)
    suspend fun vodCategories(s: SourceEntity, onProgress: ((Long, Long?) -> Unit)? = null) = categories(s, "get_vod_categories", onProgress)
    suspend fun seriesCategories(s: SourceEntity, onProgress: ((Long, Long?) -> Unit)? = null) = categories(s, "get_series_categories", onProgress)

    private suspend fun categories(s: SourceEntity, action: String, onProgress: ((Long, Long?) -> Unit)? = null): List<XtCategory> {
        val out = ArrayList<XtCategory>()
        http.get(api(s, action), s.userAgent, onProgress) { input ->
            streamObjects(input) { m ->
                val id = m["category_id"] ?: return@streamObjects
                out.add(XtCategory(id, m["category_name"] ?: id))
            }
        }
        return out
    }

    // --- Streams (callback-streamed) ---
    // Each returns true if the full list parsed cleanly, false if the server truncated it mid-stream
    // (issue #15) — the sync uses that to fall back to per-category fetching. [categoryId] filters the
    // request server-side (`&category_id=X`), keeping payloads small enough to dodge the truncation.
    suspend fun streamLive(
        s: SourceEntity,
        categoryId: String? = null,
        onItem: suspend (XtLiveStream) -> Unit,
        onProgress: ((Long, Long?) -> Unit)? = null,
    ): Boolean {
        return http.get(api(s, "get_live_streams", categoryParam(categoryId)), s.userAgent, onProgress) { input ->
            streamArray(input) { reader ->
                readLiveStream(reader)?.let { onItem(it) }
            }
        }
    }

    suspend fun streamVod(
        s: SourceEntity,
        categoryId: String? = null,
        onItem: suspend (XtVod) -> Unit,
        onProgress: ((Long, Long?) -> Unit)? = null,
    ): Boolean {
        return http.get(api(s, "get_vod_streams", categoryParam(categoryId)), s.userAgent, onProgress) { input ->
            streamArray(input) { reader ->
                readVod(reader)?.let { onItem(it) }
            }
        }
    }

    suspend fun streamSeries(
        s: SourceEntity,
        categoryId: String? = null,
        onItem: suspend (XtSeries) -> Unit,
        onProgress: ((Long, Long?) -> Unit)? = null,
    ): Boolean {
        return http.get(api(s, "get_series", categoryParam(categoryId)), s.userAgent, onProgress) { input ->
            streamArray(input) { reader ->
                readSeries(reader)?.let { onItem(it) }
            }
        }
    }

    /**
     * Fetches seasons/episodes for a series (lazy, on open). Panels vary in how they shape the `episodes`
     * field — usually an OBJECT mapping season-number → array of episode objects, but some return a flat
     * ARRAY of episodes (season taken from each episode's own `season` field). Both are handled, so series
     * that showed no episodes on stricter panels now populate.
     */
    suspend fun getSeriesInfo(s: SourceEntity, seriesId: String): XtSeriesInfo {
        val episodes = ArrayList<XtEpisode>()
        http.get(api(s, "get_series_info", "&series_id=$seriesId"), s.userAgent) { input ->
            JsonReader(input.reader(Charsets.UTF_8)).use { reader ->
                reader.isLenient = true
                if (reader.peek() != JsonToken.BEGIN_OBJECT) { reader.skipValue(); return@use }
                reader.beginObject()
                while (reader.hasNext()) {
                    if (reader.nextName() == "episodes") {
                        when (reader.peek()) {
                            JsonToken.BEGIN_OBJECT -> readEpisodesObject(reader, episodes) // { "1": [ep,…], … }
                            JsonToken.BEGIN_ARRAY -> readEpisodesArray(reader, episodes)    // [ ep, ep, … ]
                            else -> reader.skipValue()
                        }
                    } else {
                        reader.skipValue()
                    }
                }
                reader.endObject()
            }
        }
        return XtSeriesInfo(episodes)
    }

    /** `episodes` as `{ season → [episodes] }`. */
    private fun readEpisodesObject(reader: JsonReader, out: MutableList<XtEpisode>) {
        reader.beginObject()
        while (reader.hasNext()) {
            val season = reader.nextName().toIntOrNull() ?: 0
            if (reader.peek() != JsonToken.BEGIN_ARRAY) { reader.skipValue(); continue }
            reader.beginArray()
            while (reader.hasNext()) readEpisode(reader, out, season)
            reader.endArray()
        }
        reader.endObject()
    }

    /** `episodes` as a flat `[episodes]` — season comes from each episode's own field. */
    private fun readEpisodesArray(reader: JsonReader, out: MutableList<XtEpisode>) {
        reader.beginArray()
        while (reader.hasNext()) readEpisode(reader, out, 0)
        reader.endArray()
    }

    /** One episode object — tolerant of string/number/null fields and an in-object `season`. */
    private fun readEpisode(reader: JsonReader, out: MutableList<XtEpisode>, seasonFallback: Int) {
        if (reader.peek() != JsonToken.BEGIN_OBJECT) { reader.skipValue(); return }
        var id: String? = null
        var epNum = 0
        var title = ""
        var ext: String? = null
        var season = seasonFallback
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "id" -> id = reader.nextStringOrNull()
                "episode_num" -> epNum = reader.nextIntOrNull() ?: epNum
                "title" -> title = reader.nextStringOrNull() ?: title
                "container_extension" -> ext = reader.nextStringOrNull()
                "season" -> reader.nextIntOrNull()?.let { if (it > 0) season = it }
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        id?.let { out.add(XtEpisode(it, season, epNum, title.ifBlank { "Episode $epNum" }, ext)) }
    }

    /** Reads a string, coercing numbers and tolerating JSON null. */
    private fun JsonReader.nextStringOrNull(): String? =
        if (peek() == JsonToken.NULL) { nextNull(); null } else nextString()

    /** Reads an int from a number or a numeric string, tolerating null/other. */
    private fun JsonReader.nextIntOrNull(): Int? = when (peek()) {
        JsonToken.NUMBER -> nextInt()
        JsonToken.STRING -> nextString().trim().toIntOrNull()
        JsonToken.NULL -> { nextNull(); null }
        else -> { skipValue(); null }
    }

    /**
     * Short EPG (now + a few upcoming programmes) for a single live channel via `get_short_epg`.
     * Titles/descriptions are base64-encoded; timestamps are unix seconds. Returns entries sorted by
     * start time (empty if the panel has no guide for this channel).
     */
    suspend fun getShortEpg(s: SourceEntity, streamId: String, limit: Int = 6): List<XtEpgEntry> {
        val out = ArrayList<XtEpgEntry>()
        http.get(api(s, "get_short_epg", "&stream_id=$streamId&limit=$limit"), s.userAgent) { input ->
            JsonReader(input.reader(Charsets.UTF_8)).use { reader ->
                reader.isLenient = true
                if (reader.peek() != JsonToken.BEGIN_OBJECT) { reader.skipValue(); return@use }
                reader.beginObject()
                while (reader.hasNext()) {
                    if (reader.nextName() == "epg_listings" && reader.peek() == JsonToken.BEGIN_ARRAY) {
                        readEpgListings(reader, out)
                    } else {
                        reader.skipValue()
                    }
                }
                reader.endObject()
            }
        }
        return out.sortedBy { it.startMs }
    }

    private fun readEpgListings(reader: JsonReader, out: MutableList<XtEpgEntry>) {
        reader.beginArray()
        while (reader.hasNext()) {
            var title = ""
            var desc: String? = null
            var startTs = 0L
            var stopTs = 0L
            reader.beginObject()
            while (reader.hasNext()) {
                val name = reader.nextName()
                if (reader.peek() == JsonToken.NULL) { reader.nextNull(); continue }
                when (name) {
                    "title" -> title = decodeBase64(reader.nextString())
                    "description" -> desc = decodeBase64(reader.nextString()).takeIf { it.isNotBlank() }
                    "start_timestamp" -> startTs = reader.nextString().toLongOrNull() ?: 0
                    "stop_timestamp" -> stopTs = reader.nextString().toLongOrNull() ?: 0
                    else -> reader.skipValue()
                }
            }
            reader.endObject()
            if (startTs > 0 && stopTs > startTs) {
                out.add(XtEpgEntry(title.ifBlank { "—" }, desc, startTs * 1000, stopTs * 1000))
            }
        }
        reader.endArray()
    }

    private fun decodeBase64(s: String): String =
        runCatching { String(Base64.decode(s, Base64.DEFAULT), Charsets.UTF_8).trim() }.getOrDefault(s)

    // --- Stream URL builders ---
    // Live uses the raw MPEG-TS endpoint (.ts) — the universal Xtream live format. The .m3u8/HLS
    // wrapper isn't served by every panel (mpegts-only providers 404 on it, so channels won't load),
    // whereas every panel serves .ts and mpv plays it natively.
    fun liveUrl(s: SourceEntity, streamId: String) = "${base(s)}/live/${s.username}/${s.password}/$streamId.ts"

    /**
     * Catch-up (timeshift) URL for a past programme:
     * `…/timeshift/user/pass/{durationMinutes}/{yyyy-MM-dd:HH-mm}/{streamId}.ts`. The start is formatted
     * in [tz] (UTC by default — EPG timestamps are UTC; some panels expect server-local, hence the knob).
     */
    fun timeshiftUrl(s: SourceEntity, streamId: String, startMs: Long, durationMinutes: Int, tz: java.util.TimeZone = java.util.TimeZone.getTimeZone("UTC")): String {
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd:HH-mm", java.util.Locale.US).apply { timeZone = tz }
        return "${base(s)}/timeshift/${s.username}/${s.password}/$durationMinutes/${fmt.format(java.util.Date(startMs))}/$streamId.ts"
    }
    fun movieUrl(s: SourceEntity, streamId: String, ext: String?) =
        "${base(s)}/movie/${s.username}/${s.password}/$streamId.${ext ?: "mp4"}"
    fun seriesEpisodeUrl(s: SourceEntity, episodeId: String, ext: String?) =
        "${base(s)}/series/${s.username}/${s.password}/$episodeId.${ext ?: "mp4"}"

    /** Full XMLTV guide for the whole account (all channels) — the bulk EPG used by the guide grid. */
    fun xmltvUrl(s: SourceEntity): String {
        val u = URLEncoder.encode(s.username.orEmpty(), "UTF-8")
        val p = URLEncoder.encode(s.password.orEmpty(), "UTF-8")
        return "${base(s)}/xmltv.php?username=$u&password=$p"
    }

    // --- helpers ---
    private fun base(s: SourceEntity) = s.url.trimEnd('/')

    private fun api(s: SourceEntity, action: String, extra: String = ""): String {
        val u = URLEncoder.encode(s.username.orEmpty(), "UTF-8")
        val p = URLEncoder.encode(s.password.orEmpty(), "UTF-8")
        return "${base(s)}/player_api.php?username=$u&password=$p&action=$action$extra"
    }

    /** `&category_id=X` query suffix (server-side filter), or "" when fetching everything. */
    private fun categoryParam(categoryId: String?): String =
        categoryId?.takeIf { it.isNotBlank() }?.let { "&category_id=$it" } ?: ""

    /** Category lists are small, so keeping the map reader here keeps that path simple. */
    private suspend fun streamObjects(input: InputStream, onObject: suspend (Map<String, String?>) -> Unit): Boolean {
        return streamArray(input) { reader -> onObject(readObject(reader)) }
    }

    /**
     * Streams a top-level JSON array. Returns true if the array parsed to its end, false if the server
     * truncated it mid-stream (issue #15). A failure before any item is read is fatal and rethrown.
     */
    private suspend fun streamArray(input: InputStream, readItem: suspend (JsonReader) -> Unit): Boolean {
        val ctx = currentCoroutineContext()
        val startedAt = android.os.SystemClock.elapsedRealtime()
        var lastLogAt = startedAt
        var count = 0
        Log.d(TAG, "streamArray start")
        try {
            JsonReader(input.reader(Charsets.UTF_8)).use { reader ->
                reader.isLenient = true
                if (reader.peek() != JsonToken.BEGIN_ARRAY) {
                    // Some servers return {} or an error object instead of an array.
                    reader.skipValue()
                    Log.d(TAG, "streamArray non-array totalMs=${android.os.SystemClock.elapsedRealtime() - startedAt}")
                    return true
                }
                reader.beginArray()
                while (reader.hasNext()) {
                    ctx.ensureActive()
                    readItem(reader)
                    count++
                    if (count % STREAM_LOG_ITEM_STEP == 0) {
                        val now = android.os.SystemClock.elapsedRealtime()
                        Log.d(TAG, "streamArray parsed count=$count deltaMs=${now - lastLogAt} totalMs=${now - startedAt}")
                        lastLogAt = now
                    }
                }
                reader.endArray()
            }
            Log.d(TAG, "streamArray end count=$count totalMs=${android.os.SystemClock.elapsedRealtime() - startedAt}")
            return true
        } catch (c: kotlin.coroutines.cancellation.CancellationException) {
            throw c
        } catch (e: Exception) {
            // Truncated mid-stream (JsonReader reports "Unterminated string …"). Keep everything parsed
            // so far; only a failure before ANY item is read is fatal.
            if (count == 0) throw e
            Log.w(TAG, "Stream truncated after $count items — partial list kept totalMs=${android.os.SystemClock.elapsedRealtime() - startedAt}", e)
            return false
        }
    }

    private companion object {
        const val TAG = "XtreamClient"
        const val STREAM_LOG_ITEM_STEP = 10_000
    }

    private fun readLiveStream(reader: JsonReader): XtLiveStream? {
        if (reader.peek() != JsonToken.BEGIN_OBJECT) {
            reader.skipValue()
            return null
        }
        var streamId: String? = null
        var name = ""
        var icon: String? = null
        var epgChannelId: String? = null
        var categoryId: String? = null
        var num: Int? = null
        var archive = false
        var archiveDays = 0

        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "stream_id" -> streamId = reader.nextScalarStringOrNull()
                "name" -> name = reader.nextScalarStringOrNull().orEmpty()
                "stream_icon" -> icon = reader.nextScalarStringOrNull()
                "epg_channel_id" -> epgChannelId = reader.nextScalarStringOrNull()?.takeIf { it.isNotBlank() }
                "category_id" -> categoryId = reader.nextScalarStringOrNull()
                "num" -> num = reader.nextScalarStringOrNull()?.toIntOrNull()
                "tv_archive" -> archive = (reader.nextScalarStringOrNull()?.toIntOrNull() ?: 0) > 0
                "tv_archive_duration" -> archiveDays = reader.nextScalarStringOrNull()?.toIntOrNull() ?: 0
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        return streamId?.let { XtLiveStream(it, name, icon, epgChannelId, categoryId, num, archive, archiveDays) }
    }

    private fun readVod(reader: JsonReader): XtVod? {
        if (reader.peek() != JsonToken.BEGIN_OBJECT) {
            reader.skipValue()
            return null
        }
        var streamId: String? = null
        var name = ""
        var icon: String? = null
        var rating: Double? = null
        var plot: String? = null
        var description: String? = null
        var categoryId: String? = null
        var containerExt: String? = null
        var added: Long? = null

        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "stream_id" -> streamId = reader.nextScalarStringOrNull()
                "name" -> name = reader.nextScalarStringOrNull().orEmpty()
                "stream_icon" -> icon = reader.nextScalarStringOrNull()
                "rating" -> rating = reader.nextScalarStringOrNull()?.toDoubleOrNull()
                "plot" -> plot = reader.nextScalarStringOrNull()
                "description" -> description = reader.nextScalarStringOrNull()
                "category_id" -> categoryId = reader.nextScalarStringOrNull()
                "container_extension" -> containerExt = reader.nextScalarStringOrNull()
                "added" -> added = reader.nextScalarStringOrNull()?.toLongOrNull()
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        val cleanPlot = plot?.takeIf { it.isNotBlank() } ?: description?.takeIf { it.isNotBlank() }
        return streamId?.let { XtVod(it, name, icon, rating, cleanPlot, categoryId, containerExt, added) }
    }

    private fun readSeries(reader: JsonReader): XtSeries? {
        if (reader.peek() != JsonToken.BEGIN_OBJECT) {
            reader.skipValue()
            return null
        }
        var seriesId: String? = null
        var name = ""
        var cover: String? = null
        var plot: String? = null
        var rating: Double? = null
        var categoryId: String? = null
        var year: Int? = null

        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "series_id" -> seriesId = reader.nextScalarStringOrNull()
                "name" -> name = reader.nextScalarStringOrNull().orEmpty()
                "cover" -> cover = reader.nextScalarStringOrNull()
                "plot" -> plot = reader.nextScalarStringOrNull()
                "rating" -> rating = reader.nextScalarStringOrNull()?.toDoubleOrNull()
                "category_id" -> categoryId = reader.nextScalarStringOrNull()
                "year" -> year = reader.nextScalarStringOrNull()?.toIntOrNull()
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        return seriesId?.let { XtSeries(it, name, cover, plot, rating, categoryId, year) }
    }

    private fun readObject(reader: JsonReader): Map<String, String?> {
        val map = HashMap<String, String?>()
        reader.beginObject()
        while (reader.hasNext()) {
            val name = reader.nextName()
            when (reader.peek()) {
                JsonToken.NULL -> {
                    reader.nextNull()
                    map[name] = null
                }
                JsonToken.BEGIN_ARRAY, JsonToken.BEGIN_OBJECT -> reader.skipValue()
                else -> map[name] = reader.nextString()
            }
        }
        reader.endObject()
        return map
    }

    private fun JsonReader.nextScalarStringOrNull(): String? = when (peek()) {
        JsonToken.NULL -> {
            nextNull()
            null
        }
        JsonToken.STRING, JsonToken.NUMBER -> nextString()
        JsonToken.BOOLEAN -> nextBoolean().toString()
        else -> {
            skipValue()
            null
        }
    }
}
