package tv.own.owntv.core.trending

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlin.random.Random
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import tv.own.owntv.core.metadata.MetadataType
import tv.own.owntv.core.metadata.TrendingCandidate

private val Context.trendingScheduleStore: DataStore<Preferences> by
    preferencesDataStore(name = "owntv_trending_schedule")

/**
 * When each playlist is next allowed to *fetch* Trending candidates, plus the shared candidate list
 * those fetches produce.
 *
 * TMDB's daily Trending feed is the same list for everybody, so re-downloading it after every sync is
 * pure waste: a user who resyncs ten times a day used to spend ten identical rounds of calls. What
 * actually has to re-run on every sync is the *local matching* — the catalog changes, the trending
 * list does not — and matching costs nothing once the candidates are on disk.
 *
 * Two mechanisms keep the fetch rate down:
 *  - a per-playlist deadline set a random 5–8 days out ([spanDaysFor]), re-rolled after each fetch and
 *    never repeating the previous span. The randomness matters at release time: without it every
 *    install that updates on day one would come due again on the same day forever, turning a smooth
 *    load into a weekly spike.
 *  - a shared candidate list. A second playlist coming due within [SAME_DAY_WINDOW_MS] of the last
 *    fetch reuses what is already stored instead of asking again.
 *
 * Deliberately NOT in Room: this is derived scheduling state, not user data. Keeping it out means no
 * schema version, no migration, and nothing extra for backup/export to reason about. Losing it (clear
 * data, restore to a new box) costs exactly one rebuild.
 */
class TrendingScheduleStore(private val context: Context) {

    /** Next time [sourceId] may hit the network, and the span that produced it (0 when never set). */
    data class Schedule(val dueAt: Long, val spanDays: Int)

    /**
     * The Trending candidates on disk, shared by every playlist.
     *
     * [language] records which metadata language TMDB returned these titles in. It is informational
     * only: changing the metadata language deliberately does NOT invalidate the list or bring the next
     * fetch forward, so the row keeps its old-language titles until the normal 5–8 day deadline.
     *
     * [moviePagesLoaded] / [seriesPagesLoaded] record how much of the feed was actually downloaded:
     * page 2 is only fetched when matching runs short (see TrendingRepository), so a stored list is
     * usually page 1 alone. [movieTotalPages] / [seriesTotalPages] say whether a page 2 even exists.
     */
    data class Candidates(
        val language: String,
        val fetchedAt: Long,
        val movies: List<TrendingCandidate>,
        val series: List<TrendingCandidate>,
        val movieTotalPages: Int,
        val seriesTotalPages: Int,
        val moviePagesLoaded: Int,
        val seriesPagesLoaded: Int,
    )

    suspend fun schedule(sourceId: Long): Schedule? {
        val raw = context.trendingScheduleStore.data.first()[DEADLINES] ?: return null
        val entry = runCatching { JSONObject(raw).optJSONObject(sourceId.toString()) }.getOrNull() ?: return null
        return Schedule(entry.optLong("at", 0L), entry.optInt("days", 0))
    }

    /**
     * Books the next fetch a random 5–8 days after [now], never reusing the previous span so a playlist
     * cannot settle into a fixed weekday. Returns the chosen deadline.
     */
    suspend fun rollDeadline(sourceId: Long, now: Long): Long {
        val previous = schedule(sourceId)?.spanDays ?: 0
        val span = spanDaysFor(previous)
        val dueAt = now + span * DAY_MS
        writeSchedule(sourceId, dueAt, span)
        return dueAt
    }

    /** Books a short retry (a below-threshold build is worth another look sooner than a full span). */
    suspend fun setRetry(sourceId: Long, dueAt: Long) {
        writeSchedule(sourceId, dueAt, spanDays = 0)
    }

    private suspend fun writeSchedule(sourceId: Long, dueAt: Long, spanDays: Int) {
        context.trendingScheduleStore.edit { prefs ->
            val root = runCatching { JSONObject(prefs[DEADLINES] ?: "{}") }.getOrElse { JSONObject() }
            root.put(
                sourceId.toString(),
                JSONObject().put("at", dueAt).put("days", spanDays),
            )
            prefs[DEADLINES] = root.toString()
        }
    }

    suspend fun candidates(): Candidates? {
        val raw = context.trendingScheduleStore.data.first()[CANDIDATES] ?: return null
        return runCatching { decodeCandidates(JSONObject(raw)) }.getOrNull()
    }

    suspend fun storeCandidates(value: Candidates) {
        val encoded = encodeCandidates(value).toString()
        context.trendingScheduleStore.edit { prefs -> prefs[CANDIDATES] = encoded }
    }

    companion object {
        private val DEADLINES = stringPreferencesKey("fetch_deadlines")
        private val CANDIDATES = stringPreferencesKey("shared_candidates")

        const val DAY_MS = 24L * 60 * 60 * 1000
        /** A playlist coming due inside this window of the last fetch reuses the stored candidates. */
        const val SAME_DAY_WINDOW_MS = DAY_MS

        private val SPAN_DAYS = listOf(5, 6, 7, 8)

        /** Picks a fresh span from [SPAN_DAYS], excluding [previous] so two runs never land alike. */
        fun spanDaysFor(previous: Int, random: Random = Random.Default): Int {
            val options = SPAN_DAYS.filterNot { it == previous }
            return options[random.nextInt(options.size)]
        }

        internal fun encodeCandidates(value: Candidates): JSONObject = JSONObject()
            .put("language", value.language)
            .put("fetchedAt", value.fetchedAt)
            .put("movieTotalPages", value.movieTotalPages)
            .put("seriesTotalPages", value.seriesTotalPages)
            .put("moviePagesLoaded", value.moviePagesLoaded)
            .put("seriesPagesLoaded", value.seriesPagesLoaded)
            .put("movies", JSONArray(value.movies.map { it.encode() }))
            .put("series", JSONArray(value.series.map { it.encode() }))

        internal fun decodeCandidates(root: JSONObject): Candidates = Candidates(
            language = root.optString("language"),
            fetchedAt = root.optLong("fetchedAt", 0L),
            movies = root.optJSONArray("movies").decode(MetadataType.MOVIE),
            series = root.optJSONArray("series").decode(MetadataType.TV),
            movieTotalPages = root.optInt("movieTotalPages", 1),
            seriesTotalPages = root.optInt("seriesTotalPages", 1),
            moviePagesLoaded = root.optInt("moviePagesLoaded", 1),
            seriesPagesLoaded = root.optInt("seriesPagesLoaded", 1),
        )

        private fun TrendingCandidate.encode(): JSONObject = JSONObject()
            .put("id", tmdbId)
            .put("t", localizedTitle)
            .put("o", originalTitle)
            .put("y", year)
            .put("d", overview)
            .put("p", posterPath)
            .put("b", backdropPath)
            .put("r", rating)
            .put("pop", popularity)
            .put("rank", trendingRank)

        private fun JSONArray?.decode(type: MetadataType): List<TrendingCandidate> {
            val array = this ?: return emptyList()
            val out = ArrayList<TrendingCandidate>(array.length())
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val tmdbId = item.optInt("id", 0)
                val title = item.optString("t").takeIf { it.isNotBlank() } ?: continue
                if (tmdbId <= 0) continue
                out += TrendingCandidate(
                    tmdbId = tmdbId,
                    type = type,
                    localizedTitle = title,
                    originalTitle = item.optStringOrNull("o"),
                    year = if (item.isNull("y")) null else item.optInt("y").takeIf { it > 0 },
                    overview = item.optStringOrNull("d"),
                    posterPath = item.optStringOrNull("p"),
                    backdropPath = item.optStringOrNull("b"),
                    rating = if (item.isNull("r")) null else item.optDouble("r").takeIf { !it.isNaN() },
                    popularity = item.optDouble("pop", 0.0).takeIf { !it.isNaN() } ?: 0.0,
                    trendingRank = item.optInt("rank", index + 1),
                )
            }
            return out
        }

        private fun JSONObject.optStringOrNull(key: String): String? =
            if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }
    }
}
