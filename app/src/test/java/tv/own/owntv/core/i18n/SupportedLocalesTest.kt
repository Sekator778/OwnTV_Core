package tv.own.owntv.core.i18n

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SupportedLocales regression tests (docs/internationalization.md 0b/4c).
 *
 * The generated catalogue is pure Kotlin data — no Android framework dependencies — so it is fully
 * testable on the JVM. These tests pin the catalogue's structural invariants so a stale or
 * hand-edited generation is caught here, not at picker-runtime.
 */
class SupportedLocalesTest {

    @Test
    fun `catalogue has exactly 22 entries`() {
        // 21 Tier 1 languages + en-rGB regional override (tier 0).
        assertEquals(22, SupportedLocales.all.size)
    }

    @Test
    fun `catalogue has exactly 21 Tier 1 languages`() {
        val tier1 = SupportedLocales.all.filter { it.tier == 1 }
        assertEquals(21, tier1.size)
    }

    @Test
    fun `en-rGB is tier 0 and packaged but not picker-visible`() {
        val gb = SupportedLocales.all.first { it.id == "en-GB" }
        assertEquals(0, gb.tier)
        assertTrue(gb.packaged)
        assertFalse(gb.pickerVisible)
    }

    @Test
    fun `only en and en-rGB are packaged in Phase 0`() {
        val packaged = SupportedLocales.all.filter { it.packaged }
        assertEquals(2, packaged.size)
        assertTrue(packaged.any { it.id == "en-US" })
        assertTrue(packaged.any { it.id == "en-GB" })
    }

    @Test
    fun `all non-English Tier 1 locales are unpackaged and hidden in Phase 0`() {
        val nonEnglishTier1 = SupportedLocales.all.filter {
            it.tier == 1 && !it.id.startsWith("en")
        }
        assertTrue(nonEnglishTier1.isNotEmpty())
        nonEnglishTier1.forEach {
            assertFalse("${it.id} should not be packaged in Phase 0", it.packaged)
            assertFalse("${it.id} should not be picker-visible in Phase 0", it.pickerVisible)
        }
    }

    @Test
    fun `ids are unique`() {
        val ids = SupportedLocales.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `language tags are unique`() {
        val tags = SupportedLocales.all.map { it.languageTag }
        assertEquals(tags.size, tags.toSet().size)
    }

    @Test
    fun `resource qualifiers are unique`() {
        val quals = SupportedLocales.all.map { it.resourceQualifier }
        assertEquals(quals.size, quals.toSet().size)
    }

    @Test
    fun `scriptForTag returns the catalogue script for known tags`() {
        assertEquals("Arab", SupportedLocales.scriptForTag("ar"))
        assertEquals("Latn", SupportedLocales.scriptForTag("de"))
        assertEquals("Hans", SupportedLocales.scriptForTag("zh-CN"))
        assertEquals("Hant", SupportedLocales.scriptForTag("zh-TW"))
        assertEquals("Cyrl", SupportedLocales.scriptForTag("ru"))
    }

    @Test
    fun `scriptForTag returns null for unknown tags`() {
        // Tags not in the catalogue return null — the caller (LocalizedContent) then falls back to
        // ICU likely-subtags. A non-null return for an unknown tag would be a bug.
        assertNull(SupportedLocales.scriptForTag("de-DE"))
        assertNull(SupportedLocales.scriptForTag("unknown"))
        assertNull(SupportedLocales.scriptForTag(""))
    }

    @Test
    fun `isRtl is true only for Arabic`() {
        assertTrue(SupportedLocales.isRtl("ar"))
        assertFalse(SupportedLocales.isRtl("de"))
        assertFalse(SupportedLocales.isRtl("zh-CN"))
        assertFalse(SupportedLocales.isRtl("unknown"))
    }

    @Test
    fun `pickerRows excludes non-packaged and non-visible locales`() {
        // Phase 0: only en-US is packaged + pickerVisible. en-rGB is packaged but not pickerVisible.
        val rows = SupportedLocales.pickerRows
        assertTrue(rows.isNotEmpty())
        rows.forEach {
            assertTrue("${it.id} must be packaged", it.packaged)
            assertTrue("${it.id} must be pickerVisible", it.pickerVisible)
        }
    }

    @Test
    fun `system default tag is the empty string`() {
        assertEquals("", SupportedLocales.SYSTEM_DEFAULT_TAG)
    }

    @Test
    fun `source language coverage is 100`() {
        val en = SupportedLocales.all.first { it.id == "en-US" }
        assertEquals(100, en.coverage)
    }

    @Test
    fun `non-source locales have empty-coverage sentinel in Phase 0`() {
        // No translatable source keys exist yet → coverage is -1 (the EMPTY_COVERAGE sentinel).
        val de = SupportedLocales.all.first { it.id == "de" }
        assertEquals(-1, de.coverage)
    }

    @Test
    fun `every entry has non-blank required fields`() {
        SupportedLocales.all.forEach { e ->
            assertTrue("id is blank", e.id.isNotBlank())
            assertTrue("languageTag is blank", e.languageTag.isNotBlank())
            assertTrue("resourceQualifier is blank", e.resourceQualifier.isNotBlank())
            assertTrue("weblateCode is blank", e.weblateCode.isNotBlank())
            assertTrue("englishName is blank", e.englishName.isNotBlank())
            assertTrue("endonym is blank", e.endonym.isNotBlank())
            assertTrue("script is blank", e.script.isNotBlank())
        }
    }
}
