package tv.own.owntv.core.metadata

import org.json.JSONObject

/** Pure parser/merger for TMDB's media-specific `/trending/{type}/day` pages. */
internal object TmdbTrendingParser {

    fun parsePage(type: MetadataType, requestedPage: Int, body: String): TrendingFeedPage? = runCatching {
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

        TrendingFeedPage(
            page = responsePage,
            totalPages = totalPages,
            candidates = candidates,
        )
    }.getOrNull()

    private fun String.nullUnlessValue(): String? = takeIf { it.isNotBlank() && it != "null" }

    private const val TMDB_PAGE_SIZE = 20
}
