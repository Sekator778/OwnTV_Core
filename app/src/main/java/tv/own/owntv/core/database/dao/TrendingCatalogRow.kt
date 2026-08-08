package tv.own.owntv.core.database.dao

/** Bounded provider row returned by indexed exact or FTS Trending lookups. */
data class TrendingCatalogRow(
    val id: Long,
    val sourceId: Long,
    val categoryId: Long?,
    val name: String,
    val year: Int?,
    val remoteId: String?,
    val sortOrder: Int,
    val canonicalTitle: String,
    val titleSignature: String,
    val parsedYear: Int?,
    val providerLanguage: String?,
    val qualityRank: Int,
    val advertisedCapabilities: String?,
)

data class SeriesSeasonCountRow(
    val seriesId: Long,
    val seasonCount: Int,
)
