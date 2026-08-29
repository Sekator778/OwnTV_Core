package tv.own.owntv.core.metadata

import kotlin.math.abs

/** Shared title/year confidence used by on-demand metadata and reverse Trending matching. */
object TitleMatchScorer {
    fun score(
        query: String,
        queryYear: Int?,
        localizedTitle: String,
        originalTitle: String?,
        candidateYear: Int?,
    ): Double {
        val normalizedQuery = query.lowercase().trim()
        var score = maxOf(
            titleSimilarity(normalizedQuery, localizedTitle),
            originalTitle?.let { titleSimilarity(normalizedQuery, it) } ?: 0.0,
        )
        if (queryYear != null && candidateYear != null) {
            score += when (abs(queryYear - candidateYear)) {
                0 -> 0.15
                1 -> 0.0
                else -> -0.35
            }
        }
        return score.coerceIn(0.0, 1.0)
    }

    private fun titleSimilarity(query: String, candidate: String): Double {
        val title = candidate.lowercase().trim()
        if (query.isBlank() || title.isBlank()) return 0.0
        return when {
            query == title -> 1.0
            title.contains(query) || query.contains(title) -> 0.75
            else -> tokenOverlap(query, title)
        }
    }

    private fun tokenOverlap(a: String, b: String): Double {
        val left = a.split(WORD_SPLIT).filter { it.isNotBlank() }.toSet()
        val right = b.split(WORD_SPLIT).filter { it.isNotBlank() }.toSet()
        if (left.isEmpty() || right.isEmpty()) return 0.0
        val intersection = left.intersect(right).size.toDouble()
        return intersection / (left.size + right.size - intersection)
    }

    private val WORD_SPLIT = Regex("""\s+""")
}
