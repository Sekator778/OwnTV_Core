package tv.own.owntv.core.metadata

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TmdbTrendingParserTest {
    @Test
    fun moviePage_keepsLocalizedAndOriginalPresentationFields() {
        val body = page(
            page = 1,
            totalPages = 2,
            items = listOf(
                JSONObject()
                    .put("id", 101)
                    .put("title", "Die Reise")
                    .put("original_title", "The Journey")
                    .put("release_date", "2026-05-04")
                    .put("overview", "Localized overview")
                    .put("poster_path", "/poster.jpg")
                    .put("backdrop_path", "/backdrop.jpg")
                    .put("vote_average", 8.3)
                    .put("popularity", 91.5),
            ),
        )

        val parsed = TmdbTrendingParser.parsePage(MetadataType.MOVIE, 1, body)
        assertNotNull(parsed)
        val item = parsed!!.candidates.single()
        assertEquals(101, item.tmdbId)
        assertEquals(MetadataType.MOVIE, item.type)
        assertEquals("Die Reise", item.localizedTitle)
        assertEquals("The Journey", item.originalTitle)
        assertEquals(2026, item.year)
        assertEquals("Localized overview", item.overview)
        assertEquals("/poster.jpg", item.posterPath)
        assertEquals("/backdrop.jpg", item.backdropPath)
        assertEquals(8.3, item.rating!!, 0.0)
        assertEquals(1, item.trendingRank)
    }

    @Test
    fun tvPage_usesTvNamesAndFirstAirYear() {
        val body = page(
            page = 2,
            totalPages = 3,
            items = listOf(
                JSONObject()
                    .put("id", 202)
                    .put("name", "Localized Show")
                    .put("original_name", "Original Show")
                    .put("first_air_date", "2019-04-14"),
            ),
        )

        val item = TmdbTrendingParser.parsePage(MetadataType.TV, 2, body)!!.candidates.single()
        assertEquals(MetadataType.TV, item.type)
        assertEquals("Localized Show", item.localizedTitle)
        assertEquals("Original Show", item.originalTitle)
        assertEquals(2019, item.year)
        assertEquals(21, item.trendingRank)
    }

    @Test
    fun twoPages_areDeduplicatedTrimmedAndKeepOriginalRanks() {
        val pageOne = TmdbTrendingParser.parsePage(
            MetadataType.MOVIE,
            1,
            rankedPage(page = 1, ids = (1..20).toList()),
        )!!
        val pageTwo = TmdbTrendingParser.parsePage(
            MetadataType.MOVIE,
            2,
            rankedPage(page = 2, ids = listOf(20) + (21..39)),
        )!!

        val merged = TrendingFeedPage.merge(listOf(pageOne, pageTwo))
        assertEquals(25, merged.size)
        assertEquals(25, merged.map { it.tmdbId }.distinct().size)
        assertEquals(1, merged.first().trendingRank)
        assertEquals(26, merged.last().trendingRank)
        assertEquals(25, merged.last().tmdbId)
    }

    @Test
    fun responseContract_distinguishesParseFailureFromSuccessfulEmpty() {
        assertNull(TmdbTrendingParser.parsePage(MetadataType.MOVIE, 1, "not-json"))
        assertNull(TmdbTrendingParser.parsePage(MetadataType.MOVIE, 1, "{}"))

        val empty = TmdbTrendingParser.parsePage(
            MetadataType.MOVIE,
            1,
            page(page = 1, totalPages = 1, items = emptyList()),
        )
        assertNotNull(empty)
        assertTrue(empty!!.candidates.isEmpty())
    }

    @Test
    fun trendingUrl_preservesTierAuthenticationAndMetadataLanguage() {
        assertEquals(
            "https://worker.example/3/trending/movie/day?page=1&language=de-DE",
            TmdbProvider.buildTrendingUrl(
                baseUrl = "https://worker.example/",
                apiKey = null,
                language = "de-DE",
                type = MetadataType.MOVIE,
                page = 1,
            ),
        )
        assertEquals(
            "https://api.themoviedb.org/3/trending/tv/day?page=2&language=pt-BR&api_key=a%2Bb",
            TmdbProvider.buildTrendingUrl(
                baseUrl = TmdbProvider.TMDB_DIRECT_BASE,
                apiKey = "a+b",
                language = "pt-BR",
                type = MetadataType.TV,
                page = 2,
            ),
        )
    }

    private fun rankedPage(page: Int, ids: List<Int>): String = page(
        page = page,
        totalPages = 2,
        items = ids.map { id ->
            JSONObject()
                .put("id", id)
                .put("title", "Movie $id")
                .put("original_title", "Movie $id")
        },
    )

    private fun page(page: Int, totalPages: Int, items: List<JSONObject>): String = JSONObject()
        .put("page", page)
        .put("total_pages", totalPages)
        .put("results", JSONArray(items))
        .toString()
}
