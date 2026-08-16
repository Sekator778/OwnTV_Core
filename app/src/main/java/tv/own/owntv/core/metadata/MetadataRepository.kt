package tv.own.owntv.core.metadata

import android.util.Log
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import tv.own.owntv.core.database.dao.MetadataDao
import tv.own.owntv.core.database.dao.ProfileDao
import tv.own.owntv.core.database.entity.MetadataCacheEntity
import tv.own.owntv.core.database.entity.MetadataMatchEntity
import tv.own.owntv.core.database.entity.MovieEntity
import tv.own.owntv.features.settings.data.SettingsRepository

internal fun profileAllowsAdultMetadata(isKids: Boolean?): Boolean = isKids != true

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
    private val overrideStore: MetadataOverrideStore,
    private val profileDao: ProfileDao,
) {
    /** Guards [healNegativeMatchesOnce] so the DataStore read happens once per process, not per resolve. */
    private val healNeeded = java.util.concurrent.atomic.AtomicBoolean(true)

    /** The metadata language the cache keys are scoped by; blank keeps the pre-language key format. */
    private suspend fun currentLang(): String = settings.metadataConfig().resolvedLanguage

    /** Search complete TMDB unless the active profile is explicitly marked as Kids. */
    private suspend fun currentProfileAllowsAdult(): Boolean {
        val profileId = settings.activeProfileId.first()
        return profileAllowsAdultMetadata(profileDao.getById(profileId)?.isKids)
    }

    /**
     * A cached row is only usable if its cast is in the current format. Rows written before cast photos
     * existed hold names only and can never render a photo, so they are treated as stale and re-fetched
     * the next time that title is opened — once each, spread across normal browsing.
     */
    private fun MetadataCacheEntity.isUsable(): Boolean = !MetadataCast.isLegacyFormat(castJson)

    /**
     * Resolve TMDB metadata for a movie. Returns the cached row (fresh or freshly fetched), or null when
     * enrichment is off, no confident match exists, or the network failed. Cheap on repeat calls.
     */
    suspend fun resolveMovie(movie: MovieEntity): MetadataCacheEntity? {
        if (!settings.metadataConfig().enabled) return null
        healNegativeMatchesOnce()

        val localKey = movieLocalKey(movie)
        val includeAdult = currentProfileAllowsAdult()
        val matchKey = maturityMatchKey(localKey, includeAdult)
        val lang = currentLang()
        val now = System.currentTimeMillis()

        // 1. Consult the local→tmdb mapping (incl. negative cache) before hitting the network.
        dao.getMatch(matchKey)?.let { match ->
            val ttl = if (match.tmdbId == null) NEGATIVE_TTL_MS else POSITIVE_TTL_MS
            if (now - match.updatedAt < ttl) {
                val tmdbId = match.tmdbId ?: return null // fresh negative cache
                dao.getCache(cacheKey(tmdbId, lang))?.takeIf { it.isUsable() }?.let { return it }
                // Match known but cache row missing/evicted → re-fetch details below.
                return fetchAndCache(tmdbId, lang, matchKey, match.confidence)
            }
        }

        // 2. Build the search query: a user override (plan §11.2 U5b) wins over the auto-normalizer.
        val q = resolveQuery(localKey, movie.name, movie.year)
        if (q.query.isBlank()) return null

        // null = transport failure (offline / rate-limited / proxy down): bail WITHOUT negative-caching,
        // so the title retries next time instead of showing no metadata for 7 days.
        val hits = runCatching { provider.searchMovie(q.query, q.year, includeAdult) }
            .onFailure { Log.w(TAG, "resolveMovie search failed: ${it.message}") }
            .getOrNull() ?: return null

        // An override is the user telling us the exact name → trust TMDB's top relevance hit directly
        // (no fuzzy threshold) so a hand-typed title isn't rejected over punctuation/formatting differences.
        val best: Scored? = if (q.isOverride) hits.firstOrNull()?.let { Scored(it, 1.0) } else pickBest(q.query, q.year, hits)
        if (best == null) {
            // Negative cache: remember "searched, no confident match" so we don't re-hammer on scroll.
            dao.upsertMatch(MetadataMatchEntity(matchKey, TYPE_MOVIE, tmdbId = null, confidence = 0.0, updatedAt = now))
            return null
        }

        dao.upsertMatch(MetadataMatchEntity(matchKey, TYPE_MOVIE, tmdbId = best.result.tmdbId, confidence = best.score, updatedAt = now))
        return fetchAndCache(best.result.tmdbId, lang, matchKey, best.score, fallback = best.result)
    }

    /**
     * Resolve TMDB metadata for a series (show-level). Same lazy resolve + cache + negative-cache as
     * [resolveMovie], but against TMDB's TV endpoints. Cache/match keyed with the "tv" type.
     */
    suspend fun resolveSeries(series: tv.own.owntv.core.database.entity.SeriesEntity): MetadataCacheEntity? {
        if (!settings.metadataConfig().enabled) return null
        healNegativeMatchesOnce()

        val localKey = seriesLocalKey(series)
        val includeAdult = currentProfileAllowsAdult()
        val matchKey = maturityMatchKey(localKey, includeAdult)
        val lang = currentLang()
        val now = System.currentTimeMillis()

        dao.getMatch(matchKey)?.let { match ->
            val ttl = if (match.tmdbId == null) NEGATIVE_TTL_MS else POSITIVE_TTL_MS
            if (now - match.updatedAt < ttl) {
                val tmdbId = match.tmdbId ?: return null
                dao.getCache(tvCacheKey(tmdbId, lang))?.takeIf { it.isUsable() }?.let { return it }
                return fetchAndCacheTv(tmdbId, lang, null)
            }
        }

        val q = resolveQuery(localKey, series.name, series.year)
        if (q.query.isBlank()) return null

        // Same as resolveMovie: null = transport failure → no negative-cache, retry next open.
        val hits = runCatching { provider.searchTv(q.query, q.year, includeAdult) }
            .onFailure { Log.w(TAG, "resolveSeries search failed: ${it.message}") }
            .getOrNull() ?: return null

        // An override is the user telling us the exact name → trust TMDB's top relevance hit directly.
        val best: Scored? = if (q.isOverride) hits.firstOrNull()?.let { Scored(it, 1.0) } else pickBest(q.query, q.year, hits)
        if (best == null) {
            dao.upsertMatch(MetadataMatchEntity(matchKey, TYPE_TV, tmdbId = null, confidence = 0.0, updatedAt = now))
            return null
        }
        dao.upsertMatch(MetadataMatchEntity(matchKey, TYPE_TV, tmdbId = best.result.tmdbId, confidence = best.score, updatedAt = now))
        return fetchAndCacheTv(best.result.tmdbId, lang, best.result)
    }

    /** Resolve a provider movie against the exact TMDB id already confirmed by Trending. */
    suspend fun resolveKnownMovie(movie: MovieEntity, tmdbId: Int): MetadataCacheEntity? {
        if (!settings.metadataConfig().enabled) return null
        val localKey = movieLocalKey(movie)
        val matchKey = maturityMatchKey(localKey, currentProfileAllowsAdult())
        val lang = currentLang()
        val now = System.currentTimeMillis()
        dao.upsertMatch(MetadataMatchEntity(matchKey, TYPE_MOVIE, tmdbId, confidence = 1.0, updatedAt = now))
        dao.getCache(cacheKey(tmdbId, lang))?.let { cached ->
            if (now - cached.updatedAt < POSITIVE_TTL_MS && cached.isUsable()) return cached
        }
        return fetchAndCache(tmdbId, lang, matchKey, confidence = 1.0)
    }

    /** Series counterpart to [resolveKnownMovie], using the exact Trending TV id. */
    suspend fun resolveKnownSeries(
        series: tv.own.owntv.core.database.entity.SeriesEntity,
        tmdbId: Int,
    ): MetadataCacheEntity? {
        if (!settings.metadataConfig().enabled) return null
        val localKey = seriesLocalKey(series)
        val matchKey = maturityMatchKey(localKey, currentProfileAllowsAdult())
        val lang = currentLang()
        val now = System.currentTimeMillis()
        dao.upsertMatch(MetadataMatchEntity(matchKey, TYPE_TV, tmdbId, confidence = 1.0, updatedAt = now))
        dao.getCache(tvCacheKey(tmdbId, lang))?.let { cached ->
            if (now - cached.updatedAt < POSITIVE_TTL_MS && cached.isUsable()) return cached
        }
        return fetchAndCacheTv(tmdbId, lang, fallback = null)
    }

    private suspend fun fetchAndCacheTv(tmdbId: Int, lang: String, fallback: MetadataSearchResult?): MetadataCacheEntity? {
        val now = System.currentTimeMillis()
        val details = provider.tvDetails(tmdbId)
        val entity = when {
            details != null -> MetadataCacheEntity(
                key = tvCacheKey(tmdbId, lang), tmdbId = tmdbId, imdbId = details.imdbId, type = TYPE_TV,
                title = details.title, year = details.year ?: fallback?.year,
                overview = details.overview ?: fallback?.overview,
                posterPath = details.posterPath ?: fallback?.posterPath,
                backdropPath = details.backdropPath, rating = details.rating,
                genresJson = details.genres.takeIf { it.isNotEmpty() }?.let { JSONArray(it).toString() },
                castJson = details.cast.takeIf { it.isNotEmpty() }?.let { MetadataCast.serialize(it) },
                trailerKey = details.trailerKey,
                logoPath = details.logoPath,
                updatedAt = now,
            )
            fallback != null -> MetadataCacheEntity(
                key = tvCacheKey(tmdbId, lang), tmdbId = tmdbId, imdbId = null, type = TYPE_TV,
                title = fallback.title, year = fallback.year, overview = fallback.overview,
                posterPath = fallback.posterPath, backdropPath = null, rating = null,
                genresJson = null, castJson = null, trailerKey = null, logoPath = null, updatedAt = now,
            )
            else -> return dao.getCache(tvCacheKey(tmdbId, lang))
        }
        dao.upsertCache(entity)
        return entity
    }

    /**
     * Cached details for a TMDB id the caller has ALREADY resolved — Now Trending confirms the id while
     * matching, so no search is needed. A hit inside [POSITIVE_TTL_MS] costs nothing, which is the point:
     * a Trending rebuild and the Home detail path ([resolveKnownMovie] / [resolveKnownSeries]) now share
     * one copy of the payload instead of each downloading it.
     *
     * [allowNetwork] false means cache or nothing. Now Trending re-matches on every sync but only
     * downloads on its own multi-day schedule, and a cold cache (a metadata language change wipes it)
     * would otherwise turn one of those free rebuilds into ten detail calls per playlist.
     *
     * Writes no `metadata_match` row — the caller owns the local item → tmdbId link. Returns null for
     * [MetadataType.EPISODE] (use [resolveEpisode]) and when the fetch fails with nothing cached.
     */
    suspend fun cachedDetails(tmdbId: Int, type: MetadataType, allowNetwork: Boolean): MetadataCacheEntity? {
        if (tmdbId <= 0) return null
        val lang = currentLang()
        val key = when (type) {
            MetadataType.MOVIE -> cacheKey(tmdbId, lang)
            MetadataType.TV -> tvCacheKey(tmdbId, lang)
            MetadataType.EPISODE -> return null
        }
        dao.getCache(key)?.let {
            if (System.currentTimeMillis() - it.updatedAt < POSITIVE_TTL_MS && it.isUsable()) return it
        }
        if (!allowNetwork) return null
        return when (type) {
            MetadataType.MOVIE -> fetchAndCache(tmdbId, lang)
            MetadataType.TV -> fetchAndCacheTv(tmdbId, lang, fallback = null)
            MetadataType.EPISODE -> null
        }
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
        val key = episodeCacheKey(tvId, season, ep, currentLang())
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
            genresJson = null, castJson = null, trailerKey = null, updatedAt = now,
            logoPath = null,
        )
        dao.upsertCache(entity)
        return entity
    }

    /**
     * Drop every cached TMDB detail row so the next resolve re-fetches. Used when the metadata language
     * changes: cached rows hold language-specific text (overview, genres, title) but the cache key is only
     * `<type>:<tmdbId>`, so without this users would keep seeing the old language until the 60-day TTL.
     *
     * Deliberately leaves POSITIVE `metadata_match` rows intact — a title→tmdbId match is
     * language-independent, and keeping it avoids re-running a search for every item in a ~220k catalog.
     * Negative rows do go: a miss can be an artefact of the language the search ran under, and leaving it
     * meant a bad language choice kept metadata (and the OpenSubtitles tmdb_id lookup) dead for 7 days
     * even after the user switched back.
     */
    /**
     * One-shot drop of the "no match" rows written by an older matcher generation. Installs that ran
     * with a non-English metadata language cached a miss for every title they opened (the search hit's
     * title came back translated and scored ~0), and those rows outlive both the language change and the
     * app upgrade — so without this the fix wouldn't reach the affected users for 7 days.
     *
     * Deliberately lazy: it rides the first detail-screen resolve, never cold start, and only the cheap
     * negative rows go. Failures are swallowed and simply re-tried on the next resolve.
     */
    private suspend fun healNegativeMatchesOnce() {
        if (!healNeeded.get()) return
        runCatching {
            if (settings.metadataMatchHealVersion() < MATCH_HEURISTICS_VERSION) {
                dao.clearNegativeMatches()
                settings.setMetadataMatchHealVersion(MATCH_HEURISTICS_VERSION)
            }
        }.onSuccess { healNeeded.set(false) }
            .onFailure { Log.w(TAG, "negative-match heal failed: ${it.message}") }
    }

    /**
     * Called when the metadata language changes.
     *
     * Deliberately does NOT wipe the details cache any more. Cache keys now carry the language
     * ([cacheKey]), so rows for the old language stop being read on their own and age out — the new
     * language simply misses and fetches. The old `dao.clearCache()` here was the direct cause of a
     * traffic spike on every language change: it re-downloaded details for every title the user had
     * ever opened, with no search calls, because the positive matches were (correctly) kept.
     *
     * Negative matches still go: a miss can be an artefact of the language the search ran under, and
     * leaving them meant a bad language choice kept metadata dead for 7 days even after switching back.
     */
    suspend fun clearCacheForLanguageChange() {
        dao.clearNegativeMatches()
    }

    /**
     * Clear a movie's TMDB match (negative OR positive) and its cached details so the next [resolveMovie]
     * re-searches from scratch (plan §11.2 U5a — manual "Refetch TMDB details"). Does NOT resolve; the caller
     * re-triggers [resolveMovie] afterwards.
     */
    suspend fun clearMovie(movie: MovieEntity) {
        val localKey = movieLocalKey(movie)
        val lang = currentLang()
        val matchKeys = listOf(localKey, maturityMatchKey(localKey, includeAdult = false))
        matchKeys.mapNotNull { dao.getMatch(it)?.tmdbId }.distinct().forEach {
            dao.deleteCache(cacheKey(it, lang))
            if (lang.isNotBlank()) dao.deleteCache(cacheKey(it)) // pre-language row
        }
        matchKeys.forEach { dao.deleteMatch(it) }
    }

    /**
     * Clear a series' match + cached show details (plan §11.2 U5a). Per-episode cache rows for the old tmdbId
     * are left in place — they're orphaned but harmless (episode resolve looks them up by tmdbId, so stale
     * rows under an old id are simply never read). Caller re-triggers [resolveSeries].
     */
    suspend fun clearSeries(series: tv.own.owntv.core.database.entity.SeriesEntity) {
        val localKey = seriesLocalKey(series)
        val lang = currentLang()
        val matchKeys = listOf(localKey, maturityMatchKey(localKey, includeAdult = false))
        matchKeys.mapNotNull { dao.getMatch(it)?.tmdbId }.distinct().forEach {
            dao.deleteCache(tvCacheKey(it, lang))
            if (lang.isNotBlank()) dao.deleteCache(tvCacheKey(it)) // pre-language row
        }
        matchKeys.forEach { dao.deleteMatch(it) }
    }

    /**
     * Clear an episode's cache AND its show's match + show cache (plan §11.2 U5a). Episodes inherit the show's
     * match, so an episode whose show was negative-cached can only recover by clearing the show match too.
     * Caller re-triggers [resolveEpisode].
     */
    suspend fun clearEpisode(
        series: tv.own.owntv.core.database.entity.SeriesEntity,
        episode: tv.own.owntv.core.database.entity.EpisodeEntity,
    ) {
        val localKey = seriesLocalKey(series)
        val lang = currentLang()
        val matchKeys = listOf(localKey, maturityMatchKey(localKey, includeAdult = false))
        matchKeys.mapNotNull { dao.getMatch(it)?.tmdbId }.distinct().forEach { tid ->
            dao.deleteCache(tvCacheKey(tid, lang)) // show details
            dao.deleteCache(episodeCacheKey(tid, episode.seasonNumber, episode.episodeNumber, lang))
            if (lang.isNotBlank()) { // pre-language rows
                dao.deleteCache(tvCacheKey(tid))
                dao.deleteCache(episodeCacheKey(tid, episode.seasonNumber, episode.episodeNumber))
            }
        }
        matchKeys.forEach { dao.deleteMatch(it) } // show match (negative OR positive)
    }

    // --- TMDB name overrides (plan §11.2 U5b) ---
    // Stored in DataStore (no Room schema change) and keyed by the same stable local key as matching, so
    // they survive re-sync. Setting/clearing also drops the cached match+details so the next resolve
    // re-searches under the new query (caller bumps the meta-refresh tick to trigger it).

    /** The saved override for this movie, if any (used to prefill the dialog). */
    suspend fun movieOverride(movie: MovieEntity): TmdbOverride? = overrideStore.get(movieLocalKey(movie))

    /** The saved override for this series, if any. */
    suspend fun seriesOverride(series: tv.own.owntv.core.database.entity.SeriesEntity): TmdbOverride? =
        overrideStore.get(seriesLocalKey(series))

    /** Save a movie's override and drop its cached match so the next resolve uses the new query. */
    suspend fun setMovieOverride(movie: MovieEntity, title: String, year: Int?) {
        overrideStore.set(movieLocalKey(movie), title, year)
        clearMovie(movie)
    }

    /** Save a series' override and drop its cached match so the next resolve uses the new query. */
    suspend fun setSeriesOverride(series: tv.own.owntv.core.database.entity.SeriesEntity, title: String, year: Int?) {
        overrideStore.set(seriesLocalKey(series), title, year)
        clearSeries(series)
    }

    /** Remove a movie's override and drop its cached match so the next resolve re-normalizes the provider title. */
    suspend fun clearMovieOverride(movie: MovieEntity) {
        overrideStore.clear(movieLocalKey(movie))
        clearMovie(movie)
    }

    /** Remove a series' override and drop its cached match so the next resolve re-normalizes the provider title. */
    suspend fun clearSeriesOverride(series: tv.own.owntv.core.database.entity.SeriesEntity) {
        overrideStore.clear(seriesLocalKey(series))
        clearSeries(series)
    }

    /**
     * Build the TMDB search query + year for [localKey]: a user override (§11.2 U5b) wins over the
     * auto-normalized provider title. [ResolvedQuery.isOverride] lets the caller bypass the fuzzy
     * threshold and trust TMDB's top relevance hit when the user hand-typed the name.
     */
    private suspend fun resolveQuery(localKey: String, rawName: String, providerYear: Int?): ResolvedQuery {
        overrideStore.get(localKey)?.let { return ResolvedQuery(it.title, it.year ?: providerYear, isOverride = true) }
        val norm = TitleNormalizer.normalize(rawName)
        return ResolvedQuery(norm.query, providerYear ?: norm.year, isOverride = false)
    }

    private data class ResolvedQuery(val query: String, val year: Int?, val isOverride: Boolean)

    /** Fetch full details for [tmdbId] and cache them; falls back to the search hit if details fail. */
    private suspend fun fetchAndCache(
        tmdbId: Int,
        lang: String,
        localKey: String = cacheKey(tmdbId, lang),
        confidence: Double = 1.0,
        fallback: MetadataSearchResult? = null,
    ): MetadataCacheEntity? {
        val now = System.currentTimeMillis()
        val details = provider.movieDetails(tmdbId)
        val entity = when {
            details != null -> MetadataCacheEntity(
                key = cacheKey(tmdbId, lang),
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
                castJson = details.cast.takeIf { it.isNotEmpty() }?.let { MetadataCast.serialize(it) },
                trailerKey = details.trailerKey,
                logoPath = details.logoPath,
                updatedAt = now,
            )
            fallback != null -> MetadataCacheEntity(
                key = cacheKey(tmdbId, lang), tmdbId = tmdbId, imdbId = null, type = TYPE_MOVIE,
                title = fallback.title, year = fallback.year, overview = fallback.overview,
                posterPath = fallback.posterPath, backdropPath = null, rating = null,
                genresJson = null, castJson = null, trailerKey = null, logoPath = null, updatedAt = now,
            )
            else -> return dao.getCache(cacheKey(tmdbId, lang)) // nothing to write; return existing if any
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

    /**
     * 0..1 confidence from title similarity + year agreement.
     *
     * Similarity takes the BEST of the localized and the original title. TMDB translates `title`/`name`
     * when `&language=` is set, so a user on e.g. Greek metadata got Greek titles scored against Latin
     * provider names — zero overlap, every match rejected, and the negative cache then hid metadata AND
     * broke the OpenSubtitles tmdb_id lookup for 7 days. `original_title` is language-independent.
     */
    private fun score(query: String, year: Int?, r: MetadataSearchResult): Double {
        return TitleMatchScorer.score(query, year, r.title, r.originalTitle, r.year)
    }

    companion object {
        private const val TAG = "MetadataRepository"
        private const val TYPE_MOVIE = "movie"
        private const val TYPE_TV = "tv"
        private const val TYPE_EPISODE = "episode"

        /** Accept a match at/above this confidence; below it, prefer no metadata over a wrong one. */
        private const val ACCEPT_THRESHOLD = 0.6

        /**
         * Bump when a matcher change makes previously cached misses wrong — existing installs then drop
         * their negative rows once ([healNegativeMatchesOnce]). 1 = scoring against `original_title`.
         */
        private const val MATCH_HEURISTICS_VERSION = 1

        /**
         * Focus debounce for on-demand metadata resolves, shared by the movie / series / episode panes.
         *
         * 700 ms rather than the original 350: at 350 ms a sustained D-pad scroll fired a lookup for
         * almost every card it passed over, which made browsing the single largest source of metadata
         * traffic. At 700 ms a scroll costs nothing and only settling on a title resolves it.
         */
        const val FOCUS_DEBOUNCE_MS = 700L

        // 180 days, not 60: TMDB details for a released title barely change, and a shorter TTL just buys
        // a re-download of identical JSON. Negative stays at 7 days — a miss is worth retrying sooner.
        private const val POSITIVE_TTL_MS = 180L * 24 * 3600 * 1000 // 180 days
        private const val NEGATIVE_TTL_MS = 7L * 24 * 3600 * 1000   // 7 days

        /** Stable, re-sync-proof local key (mirrors CustomizeKeys): sourceId + remoteId, or name fallback. */
        fun movieLocalKey(movie: MovieEntity): String = "$TYPE_MOVIE:${movie.sourceId}:${movie.remoteId ?: movie.name}"

        /**
         * Cache keys carry the metadata language.
         *
         * Cached rows hold language-specific text (title, overview, genres), so the key must distinguish
         * them — otherwise switching language means either showing stale text or wiping the whole cache.
         * Wiping is what used to happen, and it re-downloaded details for every title the user had ever
         * opened, with no search calls: a large, entirely avoidable traffic spike on every language change.
         *
         * With the language in the key, rows for the old language simply stop being read and age out on
         * their own, while remaining available as a fallback. Deliberately a key-format change and NOT a
         * schema change — no `language` column, no Room migration, no DB version bump.
         *
         * [lang] blank (no language configured) keeps the original `<type>:<id>` form, so every row cached
         * before this change stays readable for the default-language user.
         */
        fun cacheKey(tmdbId: Int, lang: String = ""): String =
            if (lang.isBlank()) "$TYPE_MOVIE:$tmdbId" else "$TYPE_MOVIE:$lang:$tmdbId"

        fun seriesLocalKey(series: tv.own.owntv.core.database.entity.SeriesEntity): String =
            "$TYPE_TV:${series.sourceId}:${series.remoteId ?: series.name}"

        fun tvCacheKey(tmdbId: Int, lang: String = ""): String =
            if (lang.isBlank()) "$TYPE_TV:$tmdbId" else "$TYPE_TV:$lang:$tmdbId"

        fun episodeCacheKey(tvId: Int, season: Int, episode: Int, lang: String = ""): String =
            if (lang.isBlank()) "$TYPE_TV:$tvId:s${season}e$episode"
            else "$TYPE_TV:$lang:$tvId:s${season}e$episode"

        internal fun maturityMatchKey(localKey: String, includeAdult: Boolean): String =
            if (includeAdult) localKey else "$localKey:kids"
    }
}
