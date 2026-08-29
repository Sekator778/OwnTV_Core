package tv.own.owntv.core.trending

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.metadata.MetadataType
import tv.own.owntv.core.metadata.TrendingCandidate

class TrendingScheduleStoreTest {

    @Test
    fun fetchSpan_staysInRangeAndNeverRepeatsThePreviousOne() {
        val random = Random(1234)
        var previous = 0
        repeat(200) {
            val span = TrendingScheduleStore.spanDaysFor(previous, random)
            assertTrue("span=$span", span in 5..8)
            assertNotEquals(previous, span)
            previous = span
        }
    }

    @Test
    fun fetchSpan_coversEveryValueSoReleaseDayInstallsDoNotClusterForever() {
        val random = Random(7)
        val seen = (1..400).map { TrendingScheduleStore.spanDaysFor(previous = 0, random = random) }.toSet()
        assertEquals(setOf(5, 6, 7, 8), seen)
    }

    @Test
    fun storedCandidates_surviveTheRoundTripIncludingAbsentOptionalFields() {
        val original = TrendingScheduleStore.Candidates(
            language = "pt-BR",
            fetchedAt = 1_700_000_000_000L,
            movies = listOf(
                TrendingCandidate(
                    tmdbId = 101,
                    type = MetadataType.MOVIE,
                    localizedTitle = "A Viagem",
                    originalTitle = "The Journey",
                    year = 2026,
                    overview = "Overview",
                    posterPath = "/poster.jpg",
                    backdropPath = "/backdrop.jpg",
                    rating = 8.3,
                    popularity = 91.5,
                    trendingRank = 1,
                ),
                TrendingCandidate(
                    tmdbId = 102,
                    type = MetadataType.MOVIE,
                    localizedTitle = "Bare Minimum",
                    originalTitle = null,
                    year = null,
                    overview = null,
                    posterPath = null,
                    backdropPath = null,
                    rating = null,
                    popularity = 0.0,
                    trendingRank = 2,
                ),
            ),
            series = listOf(
                TrendingCandidate(
                    tmdbId = 202,
                    type = MetadataType.TV,
                    localizedTitle = "Show",
                    originalTitle = "Original Show",
                    year = 2019,
                    overview = null,
                    posterPath = null,
                    backdropPath = null,
                    rating = 7.1,
                    popularity = 12.0,
                    trendingRank = 21,
                ),
            ),
            movieTotalPages = 500,
            seriesTotalPages = 250,
            moviePagesLoaded = 2,
            seriesPagesLoaded = 1,
        )

        val restored = TrendingScheduleStore.decodeCandidates(TrendingScheduleStore.encodeCandidates(original))

        assertEquals(original, restored)
        // The media type is implied by which list a candidate was stored in, never written per row.
        assertTrue(restored.series.all { it.type == MetadataType.TV })
        assertNull(restored.movies[1].originalTitle)
        assertNull(restored.movies[1].rating)
    }
}
