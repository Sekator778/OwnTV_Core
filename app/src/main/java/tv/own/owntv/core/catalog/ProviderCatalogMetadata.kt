package tv.own.owntv.core.catalog

import tv.own.owntv.core.database.entity.MovieEntity
import tv.own.owntv.core.database.entity.SeriesEntity
import tv.own.owntv.core.metadata.TitleNormalizer

/** Search/display metadata derived once from the provider's untouched raw title. */
data class ProviderCatalogMetadata(
    val canonicalTitle: String,
    val titleSignature: String,
    val parsedYear: Int?,
    val language: String?,
    val qualityRank: Int,
    val capabilities: String?,
)

object ProviderCatalogMetadataParser {
    fun parse(rawName: String, providerYear: Int?): ProviderCatalogMetadata {
        val normalized = TitleNormalizer.normalize(cleanForTitle(rawName))
        return ProviderCatalogMetadata(
            canonicalTitle = normalized.query,
            titleSignature = normalized.query.filter(Char::isLetterOrDigit).lowercase()
                .ifBlank { "__unmatchable_${rawName.hashCode().toUInt().toString(16)}" },
            parsedYear = providerYear ?: normalized.year,
            language = extractLanguage(rawName),
            qualityRank = extractQualityRank(rawName),
            capabilities = extractCapabilities(rawName).takeIf { it.isNotEmpty() }?.joinToString(" • "),
        )
    }

    private fun extractLanguage(raw: String): String? {
        EXPLICIT_LANGUAGE.find(raw)?.groupValues?.get(1)?.let { return it.uppercase() }
        LEADING_LANGUAGE.find(raw)?.groupValues?.get(1)?.let { return it.uppercase() }
        return TRAILING_LANGUAGE.find(raw)?.groupValues?.get(1)?.uppercase()
    }

    private fun cleanForTitle(raw: String): String = raw
        .replace(EXPLICIT_LANGUAGE, " ")
        .replace(LEADING_LANGUAGE, "")
        .replace(TRAILING_LANGUAGE, "")
        .replace(ALL_RESOLUTION_MARKERS, " ")
        .replace(HDR10_PLUS, " ")
        .replace(ALL_CAPABILITY_MARKERS, " ")
        .replace(ALL_AUDIO_MARKERS, " ")

    private fun extractQualityRank(raw: String): Int = when {
        EIGHT_K.containsMatchIn(raw) -> 5
        UHD.containsMatchIn(raw) -> 4
        FHD.containsMatchIn(raw) -> 3
        HD.containsMatchIn(raw) -> 2
        SD.containsMatchIn(raw) -> 1
        else -> 0
    }

    private fun extractCapabilities(raw: String): List<String> = buildList {
        when {
            DOLBY_VISION.containsMatchIn(raw) -> add("Dolby Vision")
            HDR10_PLUS.containsMatchIn(raw) -> add("HDR10+")
            HDR10.containsMatchIn(raw) -> add("HDR10")
            HDR.containsMatchIn(raw) -> add("HDR")
        }
        when {
            ATMOS.containsMatchIn(raw) -> add("Dolby Atmos")
            SURROUND_7_1.containsMatchIn(raw) -> add("7.1 Audio")
            SURROUND_5_1.containsMatchIn(raw) -> add("5.1 Audio")
            STEREO_2_0.containsMatchIn(raw) -> add("2.0 Audio")
        }
    }

    private const val LANGUAGE_CODES = "DE|EN|FR|ES|PT|AR|NL|PL|TR|RU|BG|ZH|HR|CS|DA|ET|FI|EL|HE|HI|HU|ID|JA|KO|LV|LT|MS|NO|FA|RO|SR|SK|SL|SV|TH|UK|VI"
    private val EXPLICIT_LANGUAGE = Regex("""(?i)[|\[({]\s*($LANGUAGE_CODES)\s*[|\])}]""")
    private val LEADING_LANGUAGE = Regex("""^\s*($LANGUAGE_CODES)(?=\s*[|:_-])""")
    private val TRAILING_LANGUAGE = Regex("""\s($LANGUAGE_CODES)\s*$""")
    private val EIGHT_K = Regex("""(?i)\b8K\b""")
    private val UHD = Regex("""(?i)\b(?:4K|UHD|2160P|3840P)\b""")
    private val FHD = Regex("""(?i)\b(?:FHD|1080P)\b""")
    private val HD = Regex("""(?i)\b(?:HD|720P)\b""")
    private val SD = Regex("""(?i)\bSD\b""")
    private val ALL_RESOLUTION_MARKERS = Regex("""(?i)\b(?:8K|4K|UHD|2160P|3840P|FHD|1080P|HD|720P|SD)\b""")
    private val DOLBY_VISION = Regex("""(?i)\b(?:DOLBY\s*VISION|DOVI|DV)\b""")
    private val HDR10_PLUS = Regex("""(?i)\bHDR10\+(?!\w)""")
    private val HDR10 = Regex("""(?i)\bHDR10\b""")
    private val HDR = Regex("""(?i)\bHDR\b""")
    private val ALL_CAPABILITY_MARKERS = Regex("""(?i)\b(?:DOLBY\s*VISION|DOVI|DV|HDR10\+?|HDR)\b""")
    private val ATMOS = Regex("""(?i)\b(?:DOLBY\s*)?ATMOS\b""")
    private val SURROUND_7_1 = Regex("""(?i)(?:\b7[._ ]1\b|\bDDP?\s*7[._ ]1\b)""")
    private val SURROUND_5_1 = Regex("""(?i)(?:\b5[._ ]1\b|\bDDP?\s*5[._ ]1\b|\bAC-?3\s*5[._ ]1\b)""")
    private val STEREO_2_0 = Regex("""(?i)\b2[._ ]0\b""")
    private val ALL_AUDIO_MARKERS = Regex("""(?i)\b(?:(?:DOLBY\s*)?ATMOS|DDP?\s*[57][._ ]1|AC-?3\s*5[._ ]1|[257][._ ]0|[57][._ ]1)\b""")
}

fun MovieEntity.withProviderCatalogMetadata(): MovieEntity {
    val parsed = ProviderCatalogMetadataParser.parse(name, year)
    return copy(
        canonicalTitle = parsed.canonicalTitle,
        titleSignature = parsed.titleSignature,
        parsedYear = parsed.parsedYear,
        providerLanguage = parsed.language,
        qualityRank = parsed.qualityRank,
        advertisedCapabilities = parsed.capabilities,
    )
}

fun SeriesEntity.withProviderCatalogMetadata(): SeriesEntity {
    val parsed = ProviderCatalogMetadataParser.parse(name, year)
    return copy(
        canonicalTitle = parsed.canonicalTitle,
        titleSignature = parsed.titleSignature,
        parsedYear = parsed.parsedYear,
        providerLanguage = parsed.language,
        qualityRank = parsed.qualityRank,
        advertisedCapabilities = parsed.capabilities,
    )
}
