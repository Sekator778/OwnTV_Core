package tv.own.owntv.core.customize

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.customize.RenameRules.Action.ADD
import tv.own.owntv.core.customize.RenameRules.Action.REMOVE
import tv.own.owntv.core.customize.RenameRules.Placement.PREFIX
import tv.own.owntv.core.customize.RenameRules.Placement.SUFFIX
import tv.own.owntv.core.customize.RenameRules.Rule

class RenameRulesTest {

    private fun add(placement: RenameRules.Placement, value: String) = Rule(ADD, placement, value)
    private fun remove(placement: RenameRules.Placement, value: String) = Rule(REMOVE, placement, value)

    // --- ADD ---

    @Test
    fun addPrefix_prependsToken() {
        val r = RenameRules.apply("News", listOf(add(PREFIX, "24H ")))
        assertEquals("24H News", r.name)
        assertTrue(r.changed)
    }

    @Test
    fun addSuffix_appendsToken() {
        val r = RenameRules.apply("News", listOf(add(SUFFIX, " USA")))
        assertEquals("News USA", r.name)
        assertTrue(r.changed)
    }

    @Test
    fun addWithMultipleTokens_isSkipped() {
        val r = RenameRules.apply("News", listOf(add(PREFIX, "A;B")))
        assertEquals("News", r.name)
        assertFalse(r.changed)
    }

    @Test
    fun addWithEmptySemicolonSegments_usesTheOnlyRealValue() {
        val r = RenameRules.apply("News", listOf(add(PREFIX, "; ★ ;")))
        assertEquals("★ News", r.name)
        assertTrue(r.changed)
    }

    // --- REMOVE: boundaries ---

    @Test
    fun removeSuffix_eatsPrecedingSeparator() {
        val r = RenameRules.apply("News HD", listOf(remove(SUFFIX, "HD")))
        assertEquals("News", r.name)
        assertTrue(r.changed)
    }

    @Test
    fun removeSuffix_doesNotRemoveAMiddleMatch() {
        val r = RenameRules.apply("News - HD - 4K", listOf(remove(SUFFIX, "HD")))
        assertEquals("News - HD - 4K", r.name)
        assertFalse(r.changed)
    }

    @Test
    fun removePrefix_eatsFollowingSeparator() {
        val r = RenameRules.apply("4K Sports", listOf(remove(PREFIX, "4K")))
        assertEquals("Sports", r.name)
        assertTrue(r.changed)
    }

    @Test
    fun removeSuffix_neverRemovesMidNameOccurrence() {
        val r = RenameRules.apply("News HD Extra", listOf(remove(SUFFIX, "HD")))
        assertEquals("News HD Extra", r.name)
        assertFalse(r.changed)
    }

    @Test
    fun removeTokenThatIsTheWholeName_isRejected() {
        val r = RenameRules.apply("HD", listOf(remove(SUFFIX, "HD")))
        assertEquals("HD", r.name)
        assertFalse(r.changed)
    }

    @Test
    fun removeRule_removesOneBoundaryMatch() {
        val r = RenameRules.apply("News HD HD", listOf(remove(SUFFIX, "HD")))
        assertEquals("News HD", r.name)
    }

    @Test
    fun removeDoesNotTouchRealWordsContainingToken() {
        // "MAN" inside "BATMAN" / "SPIDER-MAN: HOMECOMING" must survive a suffix MAN rule.
        val r1 = RenameRules.apply("BATMAN", listOf(remove(SUFFIX, "MAN")))
        assertEquals("BATMAN", r1.name)
        assertFalse(r1.changed)
        val r2 = RenameRules.apply("SPIDER-MAN: HOMECOMING", listOf(remove(SUFFIX, "MAN")))
        assertEquals("SPIDER-MAN: HOMECOMING", r2.name)
    }

    // --- options ---

    @Test
    fun ignoreCase_matchesDifferentCase() {
        val r = RenameRules.apply("news hd", listOf(remove(SUFFIX, "HD")))
        assertEquals("news", r.name)
    }

    @Test
    fun ignoreCaseOff_leavesDifferentCaseAlone() {
        val opts = RenameRules.Options(ignoreCase = false)
        val r = RenameRules.apply("news hd", listOf(remove(SUFFIX, "HD")), opts)
        assertEquals("news hd", r.name)
        assertFalse(r.changed)
    }

    @Test
    fun trimLeftoversOff_removesTokenLiterally() {
        val opts = RenameRules.Options(trimLeftovers = false)
        val r = RenameRules.apply("News HD", listOf(remove(SUFFIX, "HD")), opts)
        assertEquals("News ", r.name)
        assertTrue(r.changed)
    }

    @Test
    fun trimLeftoversOff_keepsDashes() {
        val opts = RenameRules.Options(trimLeftovers = false)
        val r = RenameRules.apply("News - HD", listOf(remove(SUFFIX, "HD")), opts)
        assertEquals("News - ", r.name)
    }

    @Test
    fun trimLeftovers_neverTouchesDashInName() {
        val r = RenameRules.apply("TV-7", listOf(remove(SUFFIX, "HD")))
        assertEquals("TV-7", r.name)
        assertFalse(r.changed)
    }

    // --- multiple tokens & rule chains ---

    @Test
    fun removeSplitsSemicolonTokens() {
        val r = RenameRules.apply("News HD 720P", listOf(remove(SUFFIX, "HD; 720P")))
        assertEquals("News HD", r.name)
    }

    @Test
    fun rulesRunTopToBottom() {
        val rules = listOf(remove(SUFFIX, "HD"), add(PREFIX, "24H "))
        val r = RenameRules.apply("News HD", rules)
        assertEquals("24H News", r.name)
    }

    @Test
    fun laterAddSurvivesEarlierRemove() {
        val rules = listOf(remove(SUFFIX, "HD"), add(SUFFIX, " FHD"))
        val r = RenameRules.apply("News HD", rules)
        assertEquals("News FHD", r.name)
        assertTrue(r.changed)
    }

    @Test
    fun emptyRuleValue_isNoOp() {
        val r = RenameRules.apply("News", listOf(remove(SUFFIX, " ; ;")))
        assertEquals("News", r.name)
        assertFalse(r.changed)
    }

    // --- auto cleanup preset ---

    @Test
    fun autoCleanup_stripsCountryQualityTagsAndEmoji() {
        val rules = RenameRules.autoCleanupRules()
        // Engine preserves case and internal dashes — it only strips tags, it is not a normalizer.
        assertEquals("FUSS-TV 3", RenameRules.apply("DE | FUSS-TV 3 [HD]", rules).name)
        assertEquals("Sky Sport Bundesliga 1", RenameRules.apply("Sky Sport Bundesliga 1 FHD", rules).name)
        assertEquals("CNN", RenameRules.apply("(US) CNN 🇺🇸", rules).name)
        assertEquals("BBC One UK", RenameRules.apply("BBC One UK", rules).name)
        assertEquals("MTV", RenameRules.apply("MTV 4K 📺", rules).name)
    }

    @Test
    fun autoCleanup_leavesPlainNamesAlone() {
        val rules = RenameRules.autoCleanupRules()
        val r = RenameRules.apply("News Channel", rules)
        assertEquals("News Channel", r.name)
        assertFalse(r.changed)
    }

    @Test
    fun autoCleanupRules_areVisibleAndRemovableInEditor() {
        val rules = RenameRules.autoCleanupRules()
        assertTrue(rules.all { it.pattern != null })
        assertTrue(rules.all { it.autoLabel != null })
    }

    @Test
    fun autoCleanup_keepsNamesWithDash() {
        val rules = RenameRules.autoCleanupRules()
        assertEquals("TV-7", RenameRules.apply("TV-7", rules).name)
        assertEquals("Spider-Man", RenameRules.apply("Spider-Man", rules).name)
    }
}
