package tv.own.owntv.core.subtitles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleTrackLabelTest {

    private fun opensub(language: String?, release: String?, taken: Set<String> = emptySet()) =
        SubtitleTrackLabel.build(
            prefix = SubtitleTrackLabel.PREFIX_OPENSUB,
            language = language,
            release = release,
            fallbackKey = "opensub:1",
            taken = taken,
        )

    @Test
    fun `language and release name are both shown`() {
        assertEquals("OS_Korean · WEB-DL.NF", opensub("Korean", "WEB-DL.NF"))
    }

    /** The reported bug: three Korean downloads used to collapse onto the first one. */
    @Test
    fun `three downloads of the same language get three distinct labels`() {
        val taken = LinkedHashSet<String>()
        val labels = listOf("WEB-DL.NF", "YIFY.1080p", "BluRay.REMUX").map {
            opensub("Korean", it, taken).also(taken::add)
        }
        assertEquals(listOf("OS_Korean · WEB-DL.NF", "OS_Korean · YIFY.1080p", "OS_Korean · BluRay.REMUX"), labels)
        assertEquals(3, labels.toSet().size)
    }

    /** Two results can genuinely carry the same release string — numbering still separates them. */
    @Test
    fun `identical language and release still produce distinct labels`() {
        val first = opensub("Korean", "WEB-DL.NF")
        val second = opensub("Korean", "WEB-DL.NF", taken = setOf(first))
        val third = opensub("Korean", "WEB-DL.NF", taken = setOf(first, second))
        assertEquals("OS_Korean · WEB-DL.NF 2", second)
        assertEquals("OS_Korean · WEB-DL.NF 3", third)
    }

    /** A muxed track named exactly like ours must not be matched by the external lookups. */
    @Test
    fun `collision with an embedded track label is numbered away`() {
        val embedded = setOf("Korean", "English (SDH)", "OS_Korean · WEB-DL.NF")
        assertEquals("OS_Korean · WEB-DL.NF 2", opensub("Korean", "WEB-DL.NF", taken = embedded))
    }

    @Test
    fun `an embedded track named plain Korean never collides with a prefixed label`() {
        assertNotEquals("Korean", opensub("Korean", null))
    }

    @Test
    fun `missing release name falls back to language then numbering`() {
        val first = opensub("Korean", null)
        assertEquals("OS_Korean", first)
        assertEquals("OS_Korean 2", opensub("Korean", null, taken = setOf(first)))
    }

    @Test
    fun `missing language uses the release name alone, not a doubled one`() {
        assertEquals("OS_WEB-DL.NF", opensub(null, "WEB-DL.NF"))
    }

    @Test
    fun `no language and no release still yields a non-empty unique label`() {
        val label = opensub(null, null)
        assertEquals("OS_opensub:1", label)
        assertTrue(label.isNotBlank())
    }

    @Test
    fun `a long release name keeps its distinguishing tail`() {
        val label = opensub("Korean", "The.Tourist.2010.1080p.BluRay.x264-YIFY")
        assertTrue(label, label.endsWith("BluRay.x264-YIFY"))
        assertTrue(label, label.startsWith("OS_Korean · …"))
    }

    @Test
    fun `two long release names differing only at the tail stay distinct`() {
        val a = opensub("Korean", "The.Tourist.2010.1080p.BluRay.x264-YIFY")
        val b = opensub("Korean", "The.Tourist.2010.1080p.BluRay.x264-SPARKS")
        assertNotEquals(a, b)
    }

    @Test
    fun `local imports carry their own prefix and file name`() {
        val label = SubtitleTrackLabel.build(
            prefix = SubtitleTrackLabel.PREFIX_LOCAL,
            language = "Korean",
            release = "my-file.srt",
            fallbackKey = "local:7",
            taken = emptySet(),
        )
        assertEquals("LOCAL_Korean · my-file.srt", label)
    }
}
