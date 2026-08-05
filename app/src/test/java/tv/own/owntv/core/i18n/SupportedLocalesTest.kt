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
    fun `catalogue has exactly 25 entries`() {
        // 24 Tier 1 languages + en-rGB regional override (tier 0).
        assertEquals(25, SupportedLocales.all.size)
    }

    @Test
    fun `catalogue has exactly 24 Tier 1 languages`() {
        val tier1 = SupportedLocales.all.filter { it.tier == 1 }
        assertEquals(24, tier1.size)
    }

    @Test
    fun `en-rGB is tier 0 and packaged but not picker-visible`() {
        val gb = SupportedLocales.all.first { it.id == "en-GB" }
        assertEquals(0, gb.tier)
        assertTrue(gb.packaged)
        assertFalse(gb.pickerVisible)
    }

    @Test
    fun `all catalogue entries are packaged`() {
        val packaged = SupportedLocales.all.filter { it.packaged }
        assertEquals(SupportedLocales.all.size, packaged.size)
    }

    @Test
    fun `all Tier 1 locales are packaged and picker-visible`() {
        val tier1 = SupportedLocales.all.filter { it.tier == 1 }
        tier1.forEach {
            assertTrue("${it.id} should be packaged", it.packaged)
            assertTrue("${it.id} should be picker-visible", it.pickerVisible)
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
    fun `canonicalTag accepts only catalogue tags and canonicalizes case`() {
        assertEquals("de", SupportedLocales.canonicalTag(" DE "))
        assertEquals("hi", SupportedLocales.canonicalTag("HI"))
        assertEquals("bn", SupportedLocales.canonicalTag("BN"))
        assertEquals("pt-BR", SupportedLocales.canonicalTag("pt-br"))
        assertEquals("", SupportedLocales.canonicalTag("  "))
        assertNull(SupportedLocales.canonicalTag("und"))
        assertNull(SupportedLocales.canonicalTag("de-DE"))
        assertNull(SupportedLocales.canonicalTag("not-a-locale"))
    }

    @Test
    fun `scriptForTag returns the catalogue script for known tags`() {
        assertEquals("Arab", SupportedLocales.scriptForTag("ar"))
        assertEquals("Latn", SupportedLocales.scriptForTag("de"))
        assertEquals("Hans", SupportedLocales.scriptForTag("zh-CN"))
        assertEquals("Deva", SupportedLocales.scriptForTag("hi"))
        assertEquals("Beng", SupportedLocales.scriptForTag("bn"))
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
        val rows = SupportedLocales.pickerRows
        assertEquals(24, rows.size)
        assertFalse(rows.any { it.id == "en-GB" })
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
    fun `locale coverage stays within percentage bounds`() {
        SupportedLocales.all.forEach { locale ->
            assertTrue("${locale.id} coverage is out of range", locale.coverage in 0..100)
        }
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
