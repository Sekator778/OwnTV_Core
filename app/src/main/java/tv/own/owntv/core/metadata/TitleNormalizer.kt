package tv.own.owntv.core.metadata

/**
 * Cleans messy IPTV VOD titles into a searchable query + an extracted year (plan §3). Providers ship
 * titles like `EN| The Movie Name (2021) [HD] (MULTI-SUB)` — prefixes, quality tags, country flags and
 * embedded years all confuse a TMDB search. This strips the noise; the shared matcher then searches TMDB.
 *
 * Pure and stateless so it's trivially unit-testable and reused by Trakt later (cloud-backup-plan §9.4).
 */
object TitleNormalizer {

    data class Normalized(val query: String, val year: Int?)

    // Leading provider/language/quality prefixes, e.g. "EN|", "4K|", "AR -", "VIP:". Applied repeatedly.
    private val LEADING_TAG = Regex("""^\s*([A-Z0-9]{1,6})\s*[|:\-–]\s*""")

    // Bracketed / parenthesised tags: [HD], (MULTI-SUB), {1080p}. Years in parens are handled separately.
    private val BRACKET_TAG = Regex("""[\[{(][^\[\]{}()]*[\]})]""")

    // Standalone quality / release markers anywhere in the title.
    private val QUALITY_MARKER = Regex(
        "(?i)\\b(4k|uhd|fhd|hd|sd|hevc|h\\.?265|h\\.?264|x265|x264|hdr10?\\+?|dolby|atmos|" +
            "multi[- ]?sub|multisub|dual[- ]?audio|remux|web[- ]?dl|webrip|bluray|bdrip|dvdrip|hdrip|" +
            "imax|extended|uncut|remastered|vip)\\b"
    )

    // A 4-digit year, optionally in parens/brackets: (2021), [1999], 2015.
    private val YEAR = Regex("""[\[(]?\b(19\d{2}|20\d{2})\b[\])]?""")

    // Trailing junk separators/flags left after stripping.
    private val EMOJI_FLAG = Regex("""[🇦-🇿]""")
    private val MULTI_SPACE = Regex("""\s{2,}""")
    private val EDGE_JUNK = Regex("""^[\s\-–—|:._]+|[\s\-–—|:._]+$""")

    fun normalize(raw: String): Normalized {
        if (raw.isBlank()) return Normalized("", null)
        var s = raw

        // 1. Extract a year (prefer the last 4-digit year — series often carry a leading channel number).
        val year = YEAR.findAll(s).mapNotNull { it.groupValues[1].toIntOrNull() }
            .lastOrNull()?.takeIf { it in 1900..2099 }

        // 2. Strip leading provider/language tags repeatedly ("EN| 4K| Movie" → "Movie").
        var prev: String
        do { prev = s; s = s.replace(LEADING_TAG, "") } while (s != prev)

        // 3. Remove bracketed tags, quality markers, flags, and any remaining year token.
        s = s.replace(BRACKET_TAG, " ")
            .replace(QUALITY_MARKER, " ")
            .replace(YEAR, " ")
            .replace(EMOJI_FLAG, " ")

        // 4. Collapse separators/whitespace and trim edge junk.
        s = s.replace('_', ' ').replace('.', ' ')
            .replace(MULTI_SPACE, " ")
            .replace(EDGE_JUNK, "")
            .trim()

        return Normalized(s, year)
    }
}
