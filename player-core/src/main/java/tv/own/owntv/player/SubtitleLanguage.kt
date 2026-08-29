package tv.own.owntv.player

import java.util.Locale

/** Matches ISO-639-1/2 and regional tags (for example eng, en, and en-US). */
internal fun subtitleLanguageMatches(preferred: String, actual: String?): Boolean {
    if (preferred.isBlank() || actual.isNullOrBlank()) return false
    return languageIdentity(preferred) == languageIdentity(actual)
}

private fun languageIdentity(code: String): String {
    val normalized = code.trim().replace('_', '-').lowercase(Locale.ROOT)
    val locale = Locale.forLanguageTag(normalized)
    return runCatching { locale.isO3Language.lowercase(Locale.ROOT) }
        .getOrNull()
        .takeUnless { it.isNullOrBlank() }
        ?: normalized.substringBefore('-')
}
