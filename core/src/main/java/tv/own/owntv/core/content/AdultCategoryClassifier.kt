package tv.own.owntv.core.content

import java.text.Normalizer
import java.util.Locale
import tv.own.owntv.core.customize.CustomizeKeys
import tv.own.owntv.core.database.dao.CategoryDao
import tv.own.owntv.core.database.dao.ProfileDao
import tv.own.owntv.core.database.entity.CategoryEntity

/**
 * Conservative parental-control classifier for provider category/folder names.
 *
 * IPTV protocols expose a category name, but no dependable adult flag. Keep this classifier
 * category-only: applying these words to programme or movie titles would create false positives
 * such as "Sex and the City". Provider categories classified here are system-hidden for kids
 * profiles; ordinary profile customizations cannot unhide them.
 */
object AdultCategoryClassifier {
    private val safeExceptions = listOf(
        Regex("\\badult\\s+swim\\b"),
    )

    private val adultMarkers = listOf(
        Regex("(?:^|\\s)(?:18\\+|\\+18|21\\+|\\+21)(?:$|\\s)"),
        Regex("\\b(?:adult|adults|xxx|porn|porno|pornography|erotic|erotica|erotik|sex|sexy|playboy|hustler|redlight)\\b"),
        Regex("\\b(?:adulte|adultes|adulto|adultos|adulti|volwassenen|vuxen|voksne|aikuisille)\\b"),
        Regex("\\b(?:dla\\s+doros.ych|pro\\s+dospele|pro\\s+dospely|fur\\s+erwachsene|para\\s+adultos)\\b"),
        Regex("(?:yetiskin|для\\s+взрослых|взрослые|للكبار|بالغين|成人|成年人|성인)"),
        Regex("\\brated\\s+r\\b"),
    )

    fun isAdult(categoryName: String?): Boolean {
        if (categoryName.isNullOrBlank()) return false
        val raw = compact(categoryName.lowercase(Locale.ROOT))
        val withoutMarks = Normalizer.normalize(categoryName, Normalizer.Form.NFKD)
            .replace(Regex("\\p{M}+"), "")
        val normalized = compact(withoutMarks.lowercase(Locale.ROOT))
        return listOf(raw, normalized).distinct().any { candidate ->
            val withoutSafePhrases = safeExceptions.fold(candidate) { value, exception ->
                exception.replace(value, " ")
            }
            adultMarkers.any { it.containsMatchIn(withoutSafePhrases) }
        }
    }

    /** Category ids hidden by either profile customization or the kids-profile system policy. */
    fun hiddenCategoryIds(
        categories: List<CategoryEntity>,
        customizedHiddenKeys: Set<String>,
        isKidsProfile: Boolean,
    ): Set<Long> = categories.asSequence()
        .filter { category ->
            CustomizeKeys.category(category) in customizedHiddenKeys ||
                (isKidsProfile && isAdult(category.name))
        }
        .mapTo(linkedSetOf()) { it.id }

    /** Final access check for playback, downloads and deep links that bypass visible lists. */
    suspend fun allows(
        profileId: Long,
        categoryId: Long?,
        profileDao: ProfileDao,
        categoryDao: CategoryDao,
    ): Boolean {
        if (profileId < 0L) return false
        val profile = profileDao.getById(profileId) ?: return false
        if (!profile.isKids) return true
        val categoryName = categoryId?.let { categoryDao.getById(it)?.name }
        return !isAdult(categoryName)
    }

    private fun compact(value: String): String = value
            .replace(Regex("[^\\p{L}\\p{N}+]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
}
