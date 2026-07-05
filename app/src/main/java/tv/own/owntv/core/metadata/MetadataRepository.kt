package tv.own.owntv.core.metadata

import android.util.Log
import org.json.JSONArray
import tv.own.owntv.core.database.dao.MetadataDao
import tv.own.owntv.core.database.entity.MetadataCacheEntity
import tv.own.owntv.core.database.entity.MetadataMatchEntity
import tv.own.owntv.core.database.entity.MovieEntity
import tv.own.owntv.features.settings.data.SettingsRepository

/**
 * On-demand TMDB enrichment orchestrator (plan §3, §7). Resolves a local content item → TMDB metadata,
 * caching both the resolution (match table) and the metadata (cache table) so a second view is instant
 * and offline. NEVER bulk — callers invoke this lazily when a detail screen opens.
 *
 * Merge rule (§7.1) is applied by the UI at render time (`providerField ?: tmdbField`); this layer only
 * fetches and caches TMDB fields, never mutating the provider content tables.
 */
class MetadataRepository(
    private val provider: MetadataProvider,
    private val dao: MetadataDao,
    private val settings: SettingsRepository,
) {

    /**
     * Resolve TMDB metadata for a movie. Returns the cached row (fresh or freshly fetched), or null when
     * enrichment is off, no confident match exists, or the network failed. Cheap on repeat calls.
     */
    suspend fun resolveMovie(movie: MovieEntity): MetadataCacheEntity? {
        if (!settings.metadataConfig().enabled) return null

        val localKey = movieLocalKey(movie)
        val now = System.currentTimeMillis()

        // 1. Consult the local→tmdb mapping (incl. negative cache) before hitting the network.
        dao.getMatch(localKey)?.let { match ->
            val ttl = if (match.tmdbId == null) NEGATIVE_TTL_MS else POSITIVE_TTL_MS
            if (now - match.updatedAt < ttl) {
                val tmdbId = match.tmdbId ?: return null // fresh negative cache
                dao.getCache(cacheKey(tmdbId))?.let { return it } // fresh positive cache
                // Match known but cache row missing/evicted → re-fetch details below.
                return fetchAndCache(tmdbId, localKey, match.confidence)
            }
        }

        // 2. Normalize the messy provider title and search TMDB.
        val norm = TitleNormalizer.normalize(movie.name)
        val year = movie.year ?: norm.year
        if (norm.query.isBlank()) return null

        val hits = runCatching { provider.searchMovie(norm.query, year) }
            .onFailure { Log.w(TAG, "resolveMovie search failed: ${it.message}") }
            .getOrNull().orEmpty()

        val best = pickBest(norm.query, year, hits)
        if (best == null) {
            // Negative cache: remember "searched, no confident match" so we don't re-hammer on scroll.
            dao.upsertMatch(MetadataMatchEntity(localKey, TYPE_MOVIE, tmdbId = null, confidence = 0.0, updatedAt = now))
            return null
        }

        dao.upsertMatch(MetadataMatchEntity(localKey, TYPE_MOVIE, tmdbId = best.result.tmdbId, confidence = best.score, updatedAt = now))
        return fetchAndCache(best.result.tmdbId, localKey, best.score, fallback = best.result)
    }

    /**
     * Resolve TMDB metadata for a series (show-level). Same lazy resolve + cache + negative-cache as
     * [resolveMovie], but against TMDB's TV endpoints. Cache/match keyed with the "tv" type.
     */
    suspend fun resolveSeries(series: tv.own.owntv.core.database.entity.SeriesEntity): MetadataCacheEntity? {
        if (!settings.metadataConfig().enabled) return null

        val localKey = seriesLocalKey(series)
        val now = System.currentTimeMillis()

        dao.getMatch(localKey)?.let { match ->
            val ttl = if (match.tmdbId == null) NEGATIVE_TTL_MS else POSITIVE_TTL_MS
            if (now - match.updatedAt < ttl) {
                val tmdbId = match.tmdbId ?: return null
                dao.getCache(tvCacheKey(tmdbId))?.let { return it }
                return fetchAndCacheTv(tmdbId, null)
            }
        }

        val norm = TitleNormalizer.normalize(series.name)
        val year = series.year ?: norm.year
        if (norm.query.isBlank()) return null

        val hits = runCatching { provider.searchTv(norm.query, year) }
            .onFailure { Log.w(TAG, "resolveSeries search failed: ${it.message}") }
            .getOrNull().orEmpty()

        val best = pickBest(norm.query, year, hits)
        if (best == null) {
            dao.upsertMatch(MetadataMatchEntity(localKey, TYPE_TV, tmdbId = null, confidence = 0.0, updatedAt = now))
            return null
        }
        dao.upsertMatch(MetadataMatchEntity(localKey, TYPE_TV, tmdbId = best.result.tmdbId, confidence = best.score, updatedAt = now))
        return fetchAndCacheTv(best.result.tmdbId, best.result)
    }

    private suspend fun fetchAndCacheTv(tmdbId: Int, fallback: MetadataSearchResult?): MetadataCacheEntity? {
        val now = System.currentTimeMillis()
        val details = provider.tvDetails(tmdbId)
        val entity = when {
            details != null -> MetadataCacheEntity(
                key = tvCacheKey(tmdbId), tmdbId = tmdbId, imdbId = details.imdbId, type = TYPE_TV,
                title = details.title, year = details.year ?: fallback?.year,
                overview = details.overview ?: fallback?.overview,
                posterPath = details.posterPath ?: fallback?.posterPath,
                backdropPath = details.backdropPath, rating = details.rating,
                genresJson = details.genres.takeIf { it.isNotEmpty() }?.let { JSONArray(it).toString() },
                castJson = details.cast.takeIf { it.isNotEmpty() }?.let { JSONArray(it).toString() },
                updatedAt = now,
            )
            fallback != null -> MetadataCacheEntity(
                key = tvCacheKey(tmdbId), tmdbId = tmdbId, imdbId = null, type = TYPE_TV,
                title = fallback.title, year = fallback.year, overview = fallback.overview,
                posterPath = fallback.posterPath, backdropPath = null, rating = null,
                genresJson = null, castJson = null, updatedAt = now,
            )
            else -> return dao.getCache(tvCacheKey(tmdbId))
        }
        dao.upsertCache(entity)
        return entity
    }

    /**
     * Resolve per-episode TMDB metadata (still, plot, air date, rating). First resolves the show (cached)
     * to get its TMDB id, then fetches the episode lazily and caches it under `tv:<id>:s<n>e<m>`. Returns
     * null when enrichment is off, the show has no match, or that episode isn't on TMDB.
     */
    suspend fun resolveEpisode(
        series: tv.own.owntv.core.database.entity.SeriesEntity,
        episode: tv.own.owntv.core.database.entity.EpisodeEntity,
    ): MetadataCacheEntity? {
        if (!settings.metadataConfig().enabled) return null
        val show = resolveSeries(series) ?: return null // no confident show match → no episode lookup
        val tvId = show.tmdbId
        val season = episode.seasonNumber
        val ep = episode.episodeNumber
        val key = episodeCacheKey(tvId, season, ep)
        val now = System.currentTimeMillis()

        dao.getCache(key)?.let { if (now - it.updatedAt < POSITIVE_TTL_MS) return it }

        val d = provider.tvEpisodeDetails(tvId, season, ep) ?: return dao.getCache(key)
        val entity = MetadataCacheEntity(
            key = key, tmdbId = tvId, imdbId = null, type = TYPE_EPISODE,
            title = d.name?.takeIf { it.isNotBlank() } ?: episode.name,
            year = d.airDate?.take(4)?.toIntOrNull(),
            overview = d.overview,
            posterPath = d.stillPath, // 16:9 still, rendered via MetadataImages.backdrop sizing
            backdropPath = d.stillPath,
            rating = d.rating,
            genresJson = null, castJson = null, updatedAt = now,
        )
        dao.upsertCache(entity)
        return entity
    }

    /** Fetch full details for [tmdbId] and cache them; falls back to the search hit if details fail. */
    private suspend fun fetchAndCache(
        tmdbId: Int,
        localKey: String,
        confidence: Double,
        fallback: MetadataSearchResult? = null,
    ): MetadataCacheEntity? {
        val now = System.currentTimeMillis()
        val details = provider.movieDetails(tmdbId)
        val entity = when {
            details != null -> MetadataCacheEntity(
                key = cacheKey(tmdbId),
                tmdbId = tmdbId,
                imdbId = details.imdbId,
                type = TYPE_MOVIE,
                title = details.title,
                year = details.year ?: fallback?.year,
                overview = details.overview ?: fallback?.overview,
                posterPath = details.posterPath ?: fallback?.posterPath,
                backdropPath = details.backdropPath,
                rating = details.rating,
                genresJson = details.genres.takeIf { it.isNotEmpty() }?.let { JSONArray(it).toString() },
                castJson = details.cast.takeIf { it.isNotEmpty() }?.let { JSONArray(it).toString() },
                updatedAt = now,
            )
            fallback != null -> MetadataCacheEntity(
                key = cacheKey(tmdbId), tmdbId = tmdbId, imdbId = null, type = TYPE_MOVIE,
                title = fallback.title, year = fallback.year, overview = fallback.overview,
                posterPath = fallback.posterPath, backdropPath = null, rating = null,
                genresJson = null, castJson = null, updatedAt = now,
            )
            else -> return dao.getCache(cacheKey(tmdbId)) // nothing to write; return existing if any
        }
        dao.upsertCache(entity)
        return entity
    }

    /** Best confident match, or null (plan §12: "no art beats wrong art"). */
    private fun pickBest(query: String, year: Int?, hits: List<MetadataSearchResult>): Scored? {
        if (hits.isEmpty()) return null
        return hits.asSequence()
            .map { Scored(it, score(query, year, it)) }
            .filter { it.score >= ACCEPT_THRESHOLD }
            .maxByOrNull { it.score }
    }

    private data class Scored(val result: MetadataSearchResult, val score: Double)

    /** 0..1 confidence from title similarity + year agreement. */
    private fun score(query: String, year: Int?, r: MetadataSearchResult): Double {
        val q = query.lowercase().trim()
        val t = r.title.lowercase().trim()
        var s = when {
            q == t -> 1.0
            t.contains(q) || q.contains(t) -> 0.75
            else -> tokenOverlap(q, t)
        }
        if (year != null && r.year != null) {
            val diff = kotlin.math.abs(year - r.year)
            s += when {
                diff == 0 -> 0.15
                diff == 1 -> 0.0
                else -> -0.35
            }
        }
        return s.coerceIn(0.0, 1.0)
    }

    /** Jaccard overlap of word tokens — a cheap similarity for near-miss titles. */
    private fun tokenOverlap(a: String, b: String): Double {
        val sa = a.split(WORD_SPLIT).filter { it.isNotBlank() }.toSet()
        val sb = b.split(WORD_SPLIT).filter { it.isNotBlank() }.toSet()
        if (sa.isEmpty() || sb.isEmpty()) return 0.0
        val inter = sa.intersect(sb).size.toDouble()
        return inter / (sa.size + sb.size - inter)
    }

    companion object {
        private const val TAG = "MetadataRepository"
        private const val TYPE_MOVIE = "movie"
        private const val TYPE_TV = "tv"
        private const val TYPE_EPISODE = "episode"

        /** Accept a match at/above this confidence; below it, prefer no metadata over a wrong one. */
        private const val ACCEPT_THRESHOLD = 0.6

        private const val POSITIVE_TTL_MS = 60L * 24 * 3600 * 1000  // 60 days
        private const val NEGATIVE_TTL_MS = 7L * 24 * 3600 * 1000   // 7 days

        private val WORD_SPLIT = Regex("""\s+""")

        /** Stable, re-sync-proof local key (mirrors CustomizeKeys): sourceId + remoteId, or name fallback. */
        fun movieLocalKey(movie: MovieEntity): String = "$TYPE_MOVIE:${movie.sourceId}:${movie.remoteId ?: movie.name}"

        fun cacheKey(tmdbId: Int): String = "$TYPE_MOVIE:$tmdbId"

        fun seriesLocalKey(series: tv.own.owntv.core.database.entity.SeriesEntity): String =
            "$TYPE_TV:${series.sourceId}:${series.remoteId ?: series.name}"

        fun tvCacheKey(tmdbId: Int): String = "$TYPE_TV:$tmdbId"

        fun episodeCacheKey(tvId: Int, season: Int, episode: Int): String = "$TYPE_TV:$tvId:s${season}e$episode"
    }
}
