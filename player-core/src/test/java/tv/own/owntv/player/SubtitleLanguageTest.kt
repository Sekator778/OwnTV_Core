package tv.own.owntv.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleLanguageTest {
    @Test
    fun `english ISO codes and regional tags match`() {
        assertTrue(subtitleLanguageMatches("eng", "en"))
        assertTrue(subtitleLanguageMatches("eng", "en-US"))
        assertTrue(subtitleLanguageMatches("en", "eng"))
    }

    @Test
    fun `different or missing languages do not match`() {
        assertFalse(subtitleLanguageMatches("eng", "spa"))
        assertFalse(subtitleLanguageMatches("eng", null))
        assertFalse(subtitleLanguageMatches("", "eng"))
    }
}
