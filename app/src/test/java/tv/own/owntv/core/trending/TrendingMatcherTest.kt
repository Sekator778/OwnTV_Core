package tv.own.owntv.core.trending

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.catalog.ProviderCatalogMetadataParser
import tv.own.owntv.core.database.dao.TrendingCatalogRow
import tv.own.owntv.core.metadata.MetadataType
import tv.own.owntv.core.metadata.TrendingCandidate
import tv.own.owntv.core.model.MediaType

class TrendingMatcherTest {
    @Test
    fun parserExtractsApprovedDecoratedVariant() {
        val parsed = ProviderCatalogMetadataParser.parse("4K |DE| Supergirl (2026) UHD HDR", null)
        assertEquals("Supergirl", parsed.canonicalTitle)
        assertEquals(2026, parsed.parsedYear)
        assertEquals("DE", parsed.language)
        assertEquals(4, parsed.qualityRank)
        assertEquals("HDR", parsed.capabilities)
    }

    @Test
    fun selectedLanguageBeatsHigherQuality() = runBlocking {
        val rows = listOf(
            row(1, "|EN| Supergirl (2026) 4K"),
            row(2, "|DE| Supergirl (2026) HD"),
        )
        val selected = match(listOf(candidate(1, MetadataType.MOVIE, "Supergirl", 2026)), rows, "de-DE").single()
        assertEquals(2L, selected.variant.item.id)
    }

    @Test
    fun languageFallbackOrderIsEnglishThenUntaggedThenOther() = runBlocking {
        val candidate = candidate(1, MetadataType.MOVIE, "Arrival", 2016)
        val rows = listOf(
            row(1, "|FR| Arrival (2016) 8K"),
            row(2, "Arrival (2016) HD"),
            row(3, "|EN| Arrival (2016) 4K"),
        )
        assertEquals(3L, match(listOf(candidate), rows, "de").single().variant.item.id)
        assertEquals(2L, match(listOf(candidate), rows.filter { it.id != 3L }, "de").single().variant.item.id)
    }

    @Test
    fun originalTitleCanMatchWhenLocalizedTitleCannot() = runBlocking {
        val result = match(
            listOf(candidate(1, MetadataType.TV, "Localized Greek", 2024, original = "The Show")),
            listOf(row(1, "The Show (2024) EN")),
            "en",
            MediaType.SERIES,
        )
        assertEquals(1L, result.single().variant.item.id)
    }

    @Test
    fun wrongMovieYearIsRejected() = runBlocking {
        val result = match(
            listOf(candidate(1, MetadataType.MOVIE, "Dune", 2021)),
            listOf(row(1, "Dune (1984)")),
            "en",
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun assemblerUsesMovieReserveAfterSeriesShortage() {
        val movies = (1..10).map { selection(it, MetadataType.MOVIE, MediaType.MOVIE) }
        val series = (101..102).map { selection(it, MetadataType.TV, MediaType.SERIES) }
        val result = TrendingMatcher.assemble(movies, series)
        assertEquals(8, result.count { it.candidate.type == MetadataType.MOVIE })
        assertEquals(2, result.count { it.candidate.type == MetadataType.TV })
        assertEquals(10, result.size)
    }

    private suspend fun match(
        candidates: List<TrendingCandidate>,
        rows: List<TrendingCatalogRow>,
        language: String,
        mediaType: MediaType = MediaType.MOVIE,
    ): List<TrendingSelection> = TrendingMatcher.matchMedia(
        candidates = candidates,
        mediaType = mediaType,
        preferredLanguage = language,
        limit = 10,
        exactLookup = { signatures -> rows.filter { it.titleSignature in signatures } },
        ftsLookup = { _, _ -> rows },
    ).selections

    private fun selection(id: Int, type: MetadataType, mediaType: MediaType): TrendingSelection {
        val row = row(id.toLong(), "Title $id (2026)")
        return TrendingSelection(candidate(id, type, "Title $id", 2026), ProviderVariant.from(row, mediaType), 1.0)
    }

    private fun candidate(
        id: Int,
        type: MetadataType,
        title: String,
        year: Int?,
        original: String? = title,
        rank: Int = 1,
    ) = TrendingCandidate(
        tmdbId = id,
        type = type,
        localizedTitle = title,
        originalTitle = original,
        year = year,
        overview = null,
        posterPath = null,
        backdropPath = null,
        rating = null,
        popularity = 0.0,
        trendingRank = rank,
    )

    private fun row(id: Long, name: String, year: Int? = null): TrendingCatalogRow {
        val parsed = ProviderCatalogMetadataParser.parse(name, year)
        return TrendingCatalogRow(
            id = id,
            sourceId = 10,
            categoryId = 20,
            name = name,
            year = year,
            remoteId = "remote-$id",
            sortOrder = id.toInt(),
            canonicalTitle = parsed.canonicalTitle,
            titleSignature = parsed.titleSignature,
            parsedYear = parsed.parsedYear,
            providerLanguage = parsed.language,
            qualityRank = parsed.qualityRank,
            advertisedCapabilities = parsed.capabilities,
        )
    }
}
