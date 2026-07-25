package tv.own.owntv.core.metadata

import android.util.Log
import org.json.JSONObject
import tv.own.owntv.core.network.HttpClient
import tv.own.owntv.features.settings.data.SettingsRepository
import java.net.URLEncoder

/**
 * TMDB-backed [MetadataProvider]. All three access tiers run through this one class — [resolveEndpoint]
 * turns the current [MetadataConfig] into a base URL + optional api_key, so search/details calls are
 * tier-agnostic (plan §4).
 *
 * Only small JSON calls go through here. Images load directly from image.tmdb.org (no key), never via the
 * provider, so a proxy/Worker never sees heavy image traffic.
 */
class TmdbProvider(
    private val http: HttpClient,
    private val settings: SettingsRepository,
) : MetadataProvider {

    /**
     * Resolved base URL + auth + content language for one call. [apiKey] is null for Worker / self-host
     * tiers; [language] is blank when the user hasn't chosen one (TMDB then defaults to en-US).
     */
    private data class Endpoint(val baseUrl: String, val apiKey: String?, val language: String)

    /** Precedence per plan §4: self-host URL > user key > default Worker. */
    private suspend fun resolveEndpoint(): Endpoint {
        val cfg = settings.metadataConfig()
        val lang = cfg.resolvedLanguage
        return when (cfg.tier) {
            MetadataConfig.Tier.SELF_HOST -> Endpoint(cfg.customServerUrl.trimEnd('/'), apiKey = null, language = lang)
            MetadataConfig.Tier.OWN_KEY -> Endpoint(TMDB_DIRECT_BASE, apiKey = cfg.tmdbApiKey.trim(), language = lang)
            MetadataConfig.Tier.DEFAULT_WORKER -> Endpoint(DEFAULT_WORKER_BASE, apiKey = null, language = lang)
        }
    }

    /** `&language=<code>`, or "" when no language is configured (TMDB falls back to en-US). */
    private fun Endpoint.langParam(): String =
        if (language.isBlank()) "" else "&language=" + enc(language)

    /**
     * `include_image_language` for detail calls. Always keeps `en,null` (null = textless art, which is
     * what most posters/backdrops are) and prepends the chosen language so localized artwork wins when
     * TMDB has it. Uses the base language only — TMDB indexes images by ISO 639-1, not by region.
     */
    private fun Endpoint.imageLangParam(): String {
        val base = language.substringBefore('-').takeIf { it.isNotBlank() && it != "en" }
        return "&include_image_language=" + (if (base != null) "$base,en,null" else "en,null")
    }

    override suspend fun searchMovie(title: String, year: Int?): List<MetadataSearchResult>? =
        search(MetadataType.MOVIE, title, year)

    override suspend fun searchTv(title: String, year: Int?): List<MetadataSearchResult>? =
        search(MetadataType.TV, title, year)

    private suspend fun search(type: MetadataType, title: String, year: Int?): List<MetadataSearchResult>? {
        val query = title.trim()
        if (query.isEmpty()) return emptyList()
        val ep = resolveEndpoint()
        val path = if (type == MetadataType.TV) "/3/search/tv" else "/3/search/movie"
        val yearParam = when {
            year == null -> ""
            type == MetadataType.TV -> "&first_air_date_year=$year"
            else -> "&year=$year"
        }
        val url = buildString {
            append(ep.baseUrl).append(path)
            append("?query=").append(enc(query))
            append(yearParam)
            append("&include_adult=false")
            append(ep.langParam())
            ep.apiKey?.takeIf { it.isNotBlank() }?.let { append("&api_key=").append(enc(it)) }
        }
        // Transport failure (network down, HTTP 429 rate limit, proxy/Worker error) → null, NOT empty:
        // an empty list means "TMDB said no results" and gets negative-cached for 7 days upstream.
        val json = runCatching { http.getText(url) }
            .onFailure { Log.w(TAG, "TMDB search failed type=$type: ${it.message}") }
            .getOrNull() ?: return null

        return parseResults(type, json)
    }

    override suspend fun movieDetails(tmdbId: Int): MovieDetails? {
        if (tmdbId <= 0) return null
        val ep = resolveEndpoint()
        val url = buildString {
            append(ep.baseUrl).append("/3/movie/").append(tmdbId)
            append("?append_to_response=credits,external_ids,videos,images")
            append(ep.imageLangParam())
            append(ep.langParam())
            ep.apiKey?.takeIf { it.isNotBlank() }?.let { append("&api_key=").append(enc(it)) }
        }
        val json = runCatching { http.getText(url) }
            .onFailure { Log.w(TAG, "TMDB movie details failed id=$tmdbId: ${it.message}") }
            .getOrNull() ?: return null
        return runCatching { parseMovieDetails(json, ep.language.substringBefore('-')) }.getOrNull()
    }

    override suspend fun tvDetails(tmdbId: Int): MovieDetails? {
        if (tmdbId <= 0) return null
        val ep = resolveEndpoint()
        val url = buildString {
            append(ep.baseUrl).append("/3/tv/").append(tmdbId)
            append("?append_to_response=credits,external_ids,videos,images")
            append(ep.imageLangParam())
            append(ep.langParam())
            ep.apiKey?.takeIf { it.isNotBlank() }?.let { append("&api_key=").append(enc(it)) }
        }
        val json = runCatching { http.getText(url) }
            .onFailure { Log.w(TAG, "TMDB tv details failed id=$tmdbId: ${it.message}") }
            .getOrNull() ?: return null
        return runCatching { parseTvDetails(json, ep.language.substringBefore('-')) }.getOrNull()
    }

    override suspend fun tvEpisodeDetails(tvId: Int, season: Int, episode: Int): EpisodeDetails? {
        if (tvId <= 0) return null
        val ep = resolveEndpoint()
        val url = buildString {
            append(ep.baseUrl).append("/3/tv/").append(tvId)
            append("/season/").append(season).append("/episode/").append(episode)
            // Unlike the other endpoints this one has no base query string, so build the params as a list
            // and join — otherwise whichever of language/api_key comes first has to own the "?".
            val params = buildList {
                if (ep.language.isNotBlank()) add("language=" + enc(ep.language))
                ep.apiKey?.takeIf { it.isNotBlank() }?.let { add("api_key=" + enc(it)) }
            }
            if (params.isNotEmpty()) append("?").append(params.joinToString("&"))
        }
        val json = runCatching { http.getText(url) }
            .onFailure { Log.w(TAG, "TMDB episode details failed tv=$tvId s$season e$episode: ${it.message}") }
            .getOrNull() ?: return null
        return runCatching {
            val o = JSONObject(json)
            if (o.optInt("id", 0) == 0) return@runCatching null
            EpisodeDetails(
                name = o.optString("name").takeIf { it.isNotBlank() },
                overview = o.optString("overview").takeIf { it.isNotBlank() },
                stillPath = o.optString("still_path").takeIf { it.isNotBlank() && it != "null" },
                airDate = o.optString("air_date").takeIf { it.isNotBlank() && it != "null" },
                rating = o.optDouble("vote_average", 0.0).takeIf { it > 0.0 },
            )
        }.getOrNull()
    }

    private fun parseTvDetails(body: String, preferredLang: String): MovieDetails? {
        val o = JSONObject(body)
        val id = o.optInt("id", 0)
        if (id == 0) return null
        val genres = o.optJSONArray("genres")?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("name")?.takeIf { n -> n.isNotBlank() } }
        }.orEmpty()
        val cast = o.optJSONObject("credits")?.optJSONArray("cast")?.let { arr ->
            (0 until minOf(arr.length(), CAST_LIMIT))
                .mapNotNull { arr.optJSONObject(it)?.optString("name")?.takeIf { n -> n.isNotBlank() } }
        }.orEmpty()
        val imdb = o.optJSONObject("external_ids")?.optString("imdb_id")?.takeIf { it.isNotBlank() && it != "null" }
        return MovieDetails(
            tmdbId = id,
            imdbId = imdb,
            title = o.optString("name").ifBlank { "?" },
            year = o.optString("first_air_date").take(4).toIntOrNull(),
            overview = o.optString("overview").takeIf { it.isNotBlank() },
            posterPath = o.optString("poster_path").takeIf { it.isNotBlank() && it != "null" },
            backdropPath = o.optString("backdrop_path").takeIf { it.isNotBlank() && it != "null" },
            rating = o.optDouble("vote_average", 0.0).takeIf { it > 0.0 },
            genres = genres,
            cast = cast,
            trailerKey = parseTrailerKey(o),
            logoPath = parseLogoPath(o, preferredLang),
        )
    }

    private fun parseMovieDetails(body: String, preferredLang: String): MovieDetails? {
        val o = JSONObject(body)
        val id = o.optInt("id", 0)
        if (id == 0) return null
        val genres = o.optJSONArray("genres")?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("name")?.takeIf { n -> n.isNotBlank() } }
        }.orEmpty()
        val cast = o.optJSONObject("credits")?.optJSONArray("cast")?.let { arr ->
            (0 until minOf(arr.length(), CAST_LIMIT))
                .mapNotNull { arr.optJSONObject(it)?.optString("name")?.takeIf { n -> n.isNotBlank() } }
        }.orEmpty()
        val imdb = o.optJSONObject("external_ids")?.optString("imdb_id")?.takeIf { it.isNotBlank() && it != "null" }
            ?: o.optString("imdb_id").takeIf { it.isNotBlank() && it != "null" }
        return MovieDetails(
            tmdbId = id,
            imdbId = imdb,
            title = o.optString("title").ifBlank { "?" },
            year = o.optString("release_date").take(4).toIntOrNull(),
            overview = o.optString("overview").takeIf { it.isNotBlank() },
            posterPath = o.optString("poster_path").takeIf { it.isNotBlank() && it != "null" },
            backdropPath = o.optString("backdrop_path").takeIf { it.isNotBlank() && it != "null" },
            rating = o.optDouble("vote_average", 0.0).takeIf { it > 0.0 },
            genres = genres,
            cast = cast,
            trailerKey = parseTrailerKey(o),
            logoPath = parseLogoPath(o, preferredLang),
        )
    }

    /**
     * Best YouTube trailer key from an `append_to_response=videos` payload (plan §7.3):
     * official Trailer > any Trailer > Teaser. Only `site == "YouTube"` entries qualify
     * (the in-app player is a YouTube IFrame wrapper). Null when the title has no usable video.
     */
    private fun parseTrailerKey(details: JSONObject): String? {
        val arr = details.optJSONObject("videos")?.optJSONArray("results") ?: return null
        var trailer: String? = null
        var officialTrailer: String? = null
        var teaser: String? = null
        for (i in 0 until arr.length()) {
            val v = arr.optJSONObject(i) ?: continue
            if (!v.optString("site").equals("YouTube", ignoreCase = true)) continue
            val key = v.optString("key").takeIf { it.isNotBlank() } ?: continue
            when (v.optString("type")) {
                "Trailer" -> {
                    if (v.optBoolean("official") && officialTrailer == null) officialTrailer = key
                    if (trailer == null) trailer = key
                }
                "Teaser" -> if (teaser == null) teaser = key
            }
        }
        return officialTrailer ?: trailer ?: teaser
    }

    private data class LogoCandidate(val path: String, val languageRank: Int, val width: Int)

    /**
     * Best title logo. [preferredLang] (base ISO 639-1 of the user's chosen metadata language, blank when
     * unset) ranks first so a localized logo wins; English and textless art stay as the fallbacks.
     */
    private fun parseLogoPath(details: JSONObject, preferredLang: String): String? {
        val arr = details.optJSONObject("images")?.optJSONArray("logos") ?: return null
        val candidates = ArrayList<LogoCandidate>(arr.length())
        for (i in 0 until arr.length()) {
            val logo = arr.optJSONObject(i) ?: continue
            val path = logo.optString("file_path").takeIf { it.isNotBlank() && it != "null" } ?: continue
            if (path.endsWith(".svg", ignoreCase = true)) continue
            val languageRank = when (logo.optString("iso_639_1").takeIf { it.isNotBlank() && it != "null" }) {
                preferredLang.takeIf { it.isNotBlank() && it != "en" } -> 0
                "en" -> 1
                null -> 2
                else -> 3
            }
            candidates += LogoCandidate(path = path, languageRank = languageRank, width = logo.optInt("width", 0))
        }
        return candidates.minWithOrNull(compareBy<LogoCandidate> { it.languageRank }.thenByDescending { it.width })?.path
    }

    private fun parseResults(type: MetadataType, body: String): List<MetadataSearchResult> {
        val results = runCatching { JSONObject(body).optJSONArray("results") }.getOrNull() ?: return emptyList()
        val out = ArrayList<MetadataSearchResult>(results.length())
        for (i in 0 until results.length()) {
            val o = results.optJSONObject(i) ?: continue
            val id = o.optInt("id", 0)
            if (id == 0) continue
            val name = if (type == MetadataType.TV) o.optString("name") else o.optString("title")
            val date = if (type == MetadataType.TV) o.optString("first_air_date") else o.optString("release_date")
            out += MetadataSearchResult(
                tmdbId = id,
                type = type,
                title = name.ifBlank { "?" },
                year = date.take(4).toIntOrNull(),
                overview = o.optString("overview").takeIf { it.isNotBlank() },
                posterPath = o.optString("poster_path").takeIf { it.isNotBlank() && it != "null" },
                popularity = o.optDouble("popularity", 0.0),
            )
        }
        // TMDB already sorts by relevance; keep its order but drop obvious empties.
        return out
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    companion object {
        private const val TAG = "TmdbProvider"
        private const val CAST_LIMIT = 15

        /** Direct TMDB API base (Tier 2, user's own key). */
        const val TMDB_DIRECT_BASE = "https://api.themoviedb.org"

        /** Tier 0 default caching Worker (plan §0.5) — maintainer's key lives in the Worker secret,
         *  never in the APK. The app never sends api_key on this tier; the Worker injects it. */
        const val DEFAULT_WORKER_BASE = "https://owntv-tmdb-meta.xiannero.workers.dev"

        /** TMDB image CDN — poster/backdrop paths render straight from here, no key. */
        const val IMAGE_BASE = "https://image.tmdb.org/t/p"
    }
}
