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
        assertEquals("bbc one", EpgMatcher.normalizeForEpg("BBC.One.UK"))
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
