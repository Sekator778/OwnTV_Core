package tv.own.owntv.core.metadata

import org.json.JSONObject

/** Pure parser/merger for TMDB's media-specific `/trending/{type}/day` pages. */
internal object TmdbTrendingParser {
    data class Page(
        val pageNumber: Int,
        val totalPages: Int,
        val rawResultCount: Int,
        val candidates: List<TrendingCandidate>,
    )

    fun parsePage(type: MetadataType, requestedPage: Int, body: String): Page? = runCatching {
        require(type == MetadataType.MOVIE || type == MetadataType.TV)
        require(requestedPage > 0)
        val root = JSONObject(body)
        val results = root.optJSONArray("results") ?: return null
        val responsePage = root.optInt("page", requestedPage).takeIf { it > 0 } ?: requestedPage
        if (responsePage != requestedPage) return null
        val totalPages = root.optInt("total_pages", responsePage).coerceAtLeast(responsePage)
        val candidates = ArrayList<TrendingCandidate>(results.length())

        for (index in 0 until results.length()) {
            val item = results.optJSONObject(index) ?: continue
            val tmdbId = item.optInt("id", 0)
            if (tmdbId <= 0) continue

            val localized = if (type == MetadataType.TV) item.optString("name") else item.optString("title")
            val original =
                if (type == MetadataType.TV) item.optString("original_name") else item.optString("original_title")
            val title = localized.nullUnlessValue() ?: original.nullUnlessValue() ?: continue
            val date = if (type == MetadataType.TV) item.optString("first_air_date") else item.optString("release_date")

            candidates += TrendingCandidate(
                tmdbId = tmdbId,
                type = type,
                localizedTitle = title,
                originalTitle = original.nullUnlessValue(),
                year = date.take(4).toIntOrNull(),
                overview = item.optString("overview").nullUnlessValue(),
                posterPath = item.optString("poster_path").nullUnlessValue(),
                backdropPath = item.optString("backdrop_path").nullUnlessValue(),
                rating = item.optDouble("vote_average", 0.0).takeIf { it > 0.0 },
                popularity = item.optDouble("popularity", 0.0),
                trendingRank = ((responsePage - 1) * TMDB_PAGE_SIZE) + index + 1,
            )
        }

        Page(
            pageNumber = responsePage,
            totalPages = totalPages,
            rawResultCount = results.length(),
            candidates = candidates,
        )
    }.getOrNull()

    /** Keeps TMDB order, removes duplicate IDs, and returns at most the requested candidate count. */
    fun merge(pages: List<Page>, limit: Int = TRENDING_CANDIDATE_LIMIT): List<TrendingCandidate> {
        require(limit > 0)
        val seen = HashSet<Pair<MetadataType, Int>>()
        return pages
            .asSequence()
            .flatMap { it.candidates.asSequence() }
            .sortedBy { it.trendingRank }
            .filter { seen.add(it.type to it.tmdbId) }
            .take(limit)
            .toList()
    }

    private fun String.nullUnlessValue(): String? = takeIf { it.isNotBlank() && it != "null" }

    const val TRENDING_CANDIDATE_LIMIT = 25
    private const val TMDB_PAGE_SIZE = 20
}
