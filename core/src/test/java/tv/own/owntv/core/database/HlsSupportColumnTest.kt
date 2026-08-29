package tv.own.owntv.core.database

import org.junit.Assert.assertEquals
import org.junit.Test
import tv.own.owntv.core.model.HlsSupport

/**
 * `sources.hlsSupported` went from a boolean to a three-state [HlsSupport] WITHOUT a schema change or a
 * migration. That only holds while two things stay true, and both are easy to break by accident:
 *
 *  1. the stored codes keep the meaning the old boolean column had, so rows written by every published
 *     release still read back correctly;
 *  2. anything unrecognised degrades to UNKNOWN rather than throwing, because the column is plain
 *     INTEGER and nothing at the database level stops a stray value.
 */
class HlsSupportColumnTest {

    @Test
    fun `stored codes keep the meaning the old boolean column had`() {
        // 1 was `true` in every release up to and including DB v28 — it must still mean "supported".
        assertEquals(HlsSupport.SUPPORTED, HlsSupport.fromCode(1))
        // 0 was `false`, which conflated "the panel said no" with "we never asked". UNKNOWN is the only
        // honest reading of an existing row, and the next sync resolves it to a definite answer.
        assertEquals(HlsSupport.UNKNOWN, HlsSupport.fromCode(0))
        // The new state has to take a code no previous release ever wrote.
        assertEquals(HlsSupport.UNSUPPORTED, HlsSupport.fromCode(2))
        assertEquals(0, HlsSupport.UNKNOWN.code)
        assertEquals(1, HlsSupport.SUPPORTED.code)
        assertEquals(2, HlsSupport.UNSUPPORTED.code)
    }

    @Test
    fun `an unrecognised code degrades to unknown instead of crashing`() {
        assertEquals(HlsSupport.UNKNOWN, HlsSupport.fromCode(7))
        assertEquals(HlsSupport.UNKNOWN, HlsSupport.fromCode(-1))
    }

    @Test
    fun `a default source has asked nobody anything yet`() {
        // A freshly added playlist has not synced, so it cannot claim a verdict either way — this is
        // what keeps the "Prefer HLS" row silent on the Add screen.
        assertEquals(
            HlsSupport.UNKNOWN,
            tv.own.owntv.core.database.entity.SourceEntity(
                name = "test", type = tv.own.owntv.core.model.SourceType.XTREAM, url = "http://panel",
            ).hlsSupported,
        )
    }

    @Test
    fun `only a panel that answered settles the question`() {
        assertEquals(HlsSupport.SUPPORTED, HlsSupport.of(supported = true))
        assertEquals(HlsSupport.UNSUPPORTED, HlsSupport.of(supported = false))
    }

    @Test
    fun `the converter round-trips every state through the INTEGER column`() {
        val converters = Converters()
        HlsSupport.entries.forEach {
            assertEquals(it, converters.intToHlsSupport(converters.hlsSupportToInt(it)))
        }
    }
}
