package tv.own.owntv.core.trending

import android.os.SystemClock
import kotlin.math.abs
import tv.own.owntv.core.catalog.ProviderCatalogMetadataParser
import tv.own.owntv.core.database.dao.TrendingCatalogRow
import tv.own.owntv.core.metadata.MetadataType
import tv.own.owntv.core.metadata.TitleMatchScorer
import tv.own.owntv.core.metadata.TrendingCandidate
import tv.own.owntv.core.model.MediaType

data class TrendingProviderItem(
    val id: Long,
    val sourceId: Long,
    val categoryId: Long?,
    val mediaType: MediaType,
    val name: String,
    val year: Int?,
    val remoteId: String?,
    val sortOrder: Int,
) {
    companion object {
        fun from(row: TrendingCatalogRow, mediaType: MediaType) = TrendingProviderItem(
            id = row.id,
            sourceId = row.sourceId,
            categoryId = row.categoryId,
            mediaType = mediaType,
            name = row.name,
            year = row.parsedYear ?: row.year,
            remoteId = row.remoteId,
            sortOrder = row.sortOrder,
        )
    }
}

enum class AdvertisedQuality(val label: String?, val rank: Int) {
    UNKNOWN(null, 0), SD("SD", 1), HD("720p HD", 2), FHD("1080p FHD", 3),
    UHD("4K UHD", 4), EIGHT_K("8K", 5);

    companion object {
        fun fromRank(rank: Int): AdvertisedQuality = entries.firstOrNull { it.rank == rank } ?: UNKNOWN
    }
}

data class ProviderVariant(
    val item: TrendingProviderItem,
    val canonicalTitle: String,
    val titleSignature: String,
    val year: Int?,
    val language: String?,
    val quality: AdvertisedQuality,
    val capabilities: List<String>,
) {
    val stableKey: String get() = item.remoteId?.takeIf { it.isNotBlank() } ?: item.name

    companion object {
        fun from(row: TrendingCatalogRow, mediaType: MediaType) = ProviderVariant(
            item = TrendingProviderItem.from(row, mediaType),
            canonicalTitle = row.canonicalTitle,
            titleSignature = row.titleSignature,
            year = row.parsedYear ?: row.year,
            language = row.providerLanguage,
            quality = AdvertisedQuality.fromRank(row.qualityRank),
            capabilities = row.advertisedCapabilities?.split(" • ").orEmpty(),
        )
    }
}

data class ProviderDisplaySignals(val quality: AdvertisedQuality, val capabilities: List<String>)

/** UI compatibility facade; parsing itself is shared with the write-time catalog parser. */
object ProviderVariantParser {
    fun displaySignals(raw: String): ProviderDisplaySignals {
        val parsed = ProviderCatalogMetadataParser.parse(raw, null)
        return ProviderDisplaySignals(
            quality = AdvertisedQuality.fromRank(parsed.qualityRank),
            capabilities = parsed.capabilities?.split(" • ").orEmpty(),
        )
    }
}

data class TrendingSelection(
    val candidate: TrendingCandidate,
    val variant: ProviderVariant,
    val confidence: Double,
)

data class TrendingMatchResult(
    val selections: List<TrendingSelection>,
    val exactLookupMs: Long,
    val ftsLookupMs: Long,
    val ftsFallbacks: Int,
)

object TrendingMatcher {
    suspend fun matchMedia(
        candidates: List<TrendingCandidate>,
        mediaType: MediaType,
        preferredLanguage: String,
        limit: Int,
        exactLookup: suspend (List<String>) -> List<TrendingCatalogRow>,
        ftsLookup: suspend (String, Int) -> List<TrendingCatalogRow>,
        onCandidateChecked: ((checked: Int, match: TrendingSelection?) -> Unit)? = null,
    ): TrendingMatchResult {
        if (limit <= 0 || candidates.isEmpty()) return TrendingMatchResult(emptyList(), 0, 0, 0)
        val ranked = candidates.sortedBy { it.trendingRank }
        val candidateKeys = ranked.associateWith(::keysFor)
        val signatures = candidateKeys.values.flatten().map { it.signature }.filter { it.isNotBlank() }.distinct()
        val exactStarted = SystemClock.elapsedRealtime()
        val exactRows = if (signatures.isEmpty()) emptyList() else exactLookup(signatures)
        val exactMs = SystemClock.elapsedRealtime() - exactStarted
        val exactBuckets = exactRows.groupBy { it.titleSignature }
        val preferredBase = preferredLanguage.substringBefore('-').ifBlank { "en" }.uppercase()
        val usedProviderIds = HashSet<Long>()
        val output = ArrayList<TrendingSelection>(limit)
        var ftsMs = 0L
        var ftsFallbacks = 0
        var checked = 0

        for (candidate in ranked) {
            if (output.size >= limit) break
            if (candidate.type.toMediaType() != mediaType) continue
            checked++
            val keys = candidateKeys.getValue(candidate)
            val exactCandidates = keys.flatMap { exactBuckets[it.signature].orEmpty() }.distinctBy { it.id }
            val rows = if (exactCandidates.isNotEmpty()) {
                exactCandidates
            } else {
                ftsFallbacks++
                val started = SystemClock.elapsedRealtime()
                val fallbackById = LinkedHashMap<Long, TrendingCatalogRow>()
                for (query in keys.mapNotNull { it.ftsQuery }.distinct()) {
                    for (row in ftsLookup(query, FTS_BUCKET_LIMIT)) fallbackById.putIfAbsent(row.id, row)
                }
                ftsMs += SystemClock.elapsedRealtime() - started
                fallbackById.values.toList()
            }
            val best = chooseBest(candidate, keys, rows, mediaType, preferredBase, usedProviderIds)
            if (best != null) {
                usedProviderIds += best.variant.item.id
                output += best
            }
            onCandidateChecked?.invoke(checked, best)
        }
        return TrendingMatchResult(output, exactMs, ftsMs, ftsFallbacks)
    }

    /** Series are kept; the ranked movie reserve fills every remaining place up to ten. */
    fun assemble(movies: List<TrendingSelection>, series: List<TrendingSelection>): List<TrendingSelection> {
        val selectedSeries = series.take(MAX_TOTAL)
        val selectedMovies = movies.take(MAX_TOTAL - selectedSeries.size)
        return buildList(MAX_TOTAL) {
            val rounds = maxOf(selectedMovies.size, selectedSeries.size)
            for (index in 0 until rounds) {
                selectedMovies.getOrNull(index)?.let(::add)
                selectedSeries.getOrNull(index)?.let(::add)
            }
        }.take(MAX_TOTAL)
    }

    private fun chooseBest(
        candidate: TrendingCandidate,
        keys: List<CandidateKey>,
        rows: List<TrendingCatalogRow>,
        mediaType: MediaType,
        preferredBase: String,
        usedIds: Set<Long>,
    ): TrendingSelection? {
        val scored = rows.asSequence()
            .filter { it.id !in usedIds && it.titleSignature.isNotBlank() }
            .mapNotNull { row ->
                val variant = ProviderVariant.from(row, mediaType)
                val exactTitle = keys.any { it.signature == variant.titleSignature }
                if (!yearCompatible(mediaType, variant.year, candidate.year, exactTitle)) return@mapNotNull null
                val shortTitle = variant.titleSignature.length <= SHORT_TITLE_LENGTH
                if (shortTitle && !exactTitle) return@mapNotNull null
                val confidence = if (exactTitle) 1.0 else TitleMatchScorer.score(
                    query = variant.canonicalTitle,
                    queryYear = variant.year,
                    localizedTitle = keys.first().canonicalTitle,
                    originalTitle = keys.getOrNull(1)?.canonicalTitle,
                    candidateYear = candidate.year,
                )
                if (confidence < ACCEPT_THRESHOLD) null else TrendingSelection(candidate, variant, confidence)
            }
            .toList()
        val bestConfidence = scored.maxOfOrNull { it.confidence } ?: return null
        return scored.asSequence()
            .filter { it.confidence >= bestConfidence - SCORE_TIE_WINDOW }
            .minWithOrNull(
                compareBy<TrendingSelection> { languageRank(it.variant.language, preferredBase) }
                    .thenByDescending { it.variant.quality.rank }
                    .thenBy { it.variant.item.sortOrder }
                    .thenBy { it.variant.item.id },
            )
    }

    private fun yearCompatible(mediaType: MediaType, providerYear: Int?, tmdbYear: Int?, exactTitle: Boolean): Boolean {
        if (providerYear == null || tmdbYear == null) return exactTitle
        val difference = abs(providerYear - tmdbYear)
        return when (mediaType) {
            MediaType.MOVIE -> difference == 0
            MediaType.SERIES -> difference <= 1
            else -> false
        }
    }

    private fun keysFor(candidate: TrendingCandidate): List<CandidateKey> =
        listOfNotNull(candidate.localizedTitle, candidate.originalTitle)
            .map { ProviderCatalogMetadataParser.parse(it, candidate.year) }
            .filter { it.titleSignature.isNotBlank() }
            .distinctBy { it.titleSignature }
            .map { parsed ->
                CandidateKey(
                    canonicalTitle = parsed.canonicalTitle,
                    signature = parsed.titleSignature,
                    ftsQuery = parsed.canonicalTitle.split(Regex("""\s+"""))
                        .map { token -> token.filter(Char::isLetterOrDigit) }
                        .filter { it.isNotBlank() }
                        .takeIf { it.isNotEmpty() }
                        ?.joinToString(" ") { "$it*" },
                )
            }

    private fun MetadataType.toMediaType(): MediaType? = when (this) {
        MetadataType.MOVIE -> MediaType.MOVIE
        MetadataType.TV -> MediaType.SERIES
        MetadataType.EPISODE -> null
    }

    private fun languageRank(language: String?, preferred: String): Int = when {
        language == preferred -> 0
        language == "EN" -> 1
        language == null -> 2
        else -> 3
    }

    private data class CandidateKey(val canonicalTitle: String, val signature: String, val ftsQuery: String?)

    const val MAX_PER_MEDIA_TYPE = 10
    const val MAX_TOTAL = 10
    private const val FTS_BUCKET_LIMIT = 40
    private const val SHORT_TITLE_LENGTH = 3
    private const val ACCEPT_THRESHOLD = 0.9
    private const val SCORE_TIE_WINDOW = 0.01
}
