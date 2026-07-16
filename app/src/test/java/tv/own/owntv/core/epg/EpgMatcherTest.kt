package tv.own.owntv.core.epg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EpgMatcherTest {

    @Test
    fun normalize_stripsQualityCountryAndSeparators() {
        assertEquals("fuss tv 3", EpgMatcher.normalizeForEpg("DE| FUSS-TV 3 [HD]"))
        assertEquals("sky sport bundesliga 1", EpgMatcher.normalizeForEpg("Sky Sport Bundesliga 1 FHD"))
        assertEquals("cnn", EpgMatcher.normalizeForEpg("(US) CNN ᴴᴰ"))
        assertEquals("bbc 1", EpgMatcher.normalizeForEpg("BBC.One.UK")) // number words → digits
    }

    @Test
    fun normalize_dropsSpelledOutCountryNamesAtEnds() {
        assertEquals("mtv", EpgMatcher.normalizeForEpg("MTV France"))
        assertEquals("mtv", EpgMatcher.normalizeForEpg("FR| MTV HD"))
        // Mid-name words are never dropped.
        assertEquals("france 24", EpgMatcher.normalizeForEpg("France 24"))
    }

    @Test
    fun score_tokenOverlapMatchesReorderedWords() {
        val a = EpgMatcher.normalizeForEpg("MTV France")
        val b = EpgMatcher.normalizeForEpg("FR| MTV")
        assertTrue(EpgMatcher.scoreNormalized(a, b) >= EpgMatcher.AUTO_THRESHOLD)
    }

    @Test
    fun score_differentChannelNumbersNeverReachReview() {
        val a = EpgMatcher.normalizeForEpg("Sky Sports 2")
        val b = EpgMatcher.normalizeForEpg("Sky Sports 3")
        assertTrue(EpgMatcher.scoreNormalized(a, b) < EpgMatcher.REVIEW_THRESHOLD)
    }

    @Test
    fun score_numberOnOneSideStaysBelowAutoApply() {
        val a = EpgMatcher.normalizeForEpg("MTV")
        val b = EpgMatcher.normalizeForEpg("MTV 2")
        assertTrue(EpgMatcher.scoreNormalized(a, b) < EpgMatcher.AUTO_THRESHOLD)
    }

    @Test
    fun rankForPicker_putsRelatedChannelsFirstKeepsRestInOrder() {
        val items = listOf("A Channel", "B Channel", "MTV Hits", "MTV France", "Zebra TV")
        val ranked = EpgMatcher.rankForPicker("FR| MTV", items, { it }, { it })
        assertEquals("MTV France", ranked[0]) // country stripped → exact match
        assertEquals("MTV Hits", ranked[1])
        assertEquals(listOf("A Channel", "B Channel", "Zebra TV"), ranked.drop(2))
    }

    @Test
    fun jaroWinkler_identicalAndDisjoint() {
        assertEquals(1.0, EpgMatcher.jaroWinkler("cnn", "cnn"), 0.0)
        assertEquals(0.0, EpgMatcher.jaroWinkler("cnn", ""), 0.0)
        assertTrue(EpgMatcher.jaroWinkler("abcdef", "xyz") < 0.6)
    }

    @Test
    fun bestMatch_picksTopCandidateByNameOrId() {
        val candidates = listOf(
            EpgMatcher.Candidate("fusstv3.de", "FUSS TV 3"),
            EpgMatcher.Candidate("cnn.us", "CNN International"),
            EpgMatcher.Candidate("skybundes1.de", "Sky Sport Bundesliga 1"),
        )
        val result = EpgMatcher.bestEpgMatch("DE| FUSS-TV 3 HD", candidates)
        assertEquals("fusstv3.de", result?.epgChannelId)
        assertTrue("score should be high", (result?.score ?: 0.0) >= EpgMatcher.AUTO_THRESHOLD)
    }

    @Test
    fun bestMatch_returnsNullWhenNothingClearsThreshold() {
        val candidates = listOf(EpgMatcher.Candidate("cnn.us", "CNN International"))
        assertNull(EpgMatcher.bestEpgMatch("Discovery Channel", candidates))
    }
}
