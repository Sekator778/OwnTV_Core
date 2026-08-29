package tv.own.owntv.core.epg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import tv.own.owntv.core.customize.CustomizeKeys
import tv.own.owntv.core.customize.SectionCustomizations
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.database.entity.EpgProgrammeEntity

/**
 * EPG time offset — a guide shared by the East and West feed of a network is hours out on one of
 * them, so the user shifts that channel's guide on its own.
 */
class EpgShiftTest {

    private val channel = ChannelEntity(
        sourceId = 7, categoryId = null, name = "CBS West", streamUrl = "http://x/1.ts", remoteId = "1234",
    )
    private val key = CustomizeKeys.channel(channel)

    private fun programme(startMs: Long, stopMs: Long) = EpgProgrammeEntity(
        sourceId = 7, epgChannelId = "cbs", startMs = startMs, stopMs = stopMs, title = "News",
    )

    @Test
    fun perChannelOverrideWinsOverTheGlobalOffset() {
        val cust = SectionCustomizations(epgShifts = mapOf(key to "-180"))
        assertEquals(-180, EpgShift.minutesFor(cust, channel, globalMinutes = 60))
        assertEquals(-180, EpgShift.overrideFor(cust, channel))
    }

    @Test
    fun withoutAnOverrideTheGlobalOffsetApplies() {
        val cust = SectionCustomizations()
        assertEquals(60, EpgShift.minutesFor(cust, channel, globalMinutes = 60))
        assertNull(EpgShift.overrideFor(cust, channel))
    }

    /** An explicit 0 pins one channel to the feed's own times while the global offset moves the rest. */
    @Test
    fun anExplicitZeroOverridesTheGlobalOffset() {
        val cust = SectionCustomizations(epgShifts = mapOf(key to "0"))
        assertEquals(0, EpgShift.minutesFor(cust, channel, globalMinutes = 180))
    }

    @Test
    fun programmesMoveByTheShiftAndQueriesMoveTheOtherWay() {
        val p = programme(1_000_000L, 1_003_600_000L)
        val shifted = EpgShift.apply(p, 180)
        assertEquals(1_000_000L + 180 * 60_000L, shifted.startMs)
        assertEquals(1_003_600_000L + 180 * 60_000L, shifted.stopMs)
        // A display instant maps back to the stored (feed) clock for the DB query.
        assertEquals(1_000_000L, EpgShift.toStored(1_000_000L + 180 * 60_000L, 180))
    }

    /** No shift must not allocate — every guide row goes through this on the normal path. */
    @Test
    fun zeroShiftReturnsTheSameInstances() {
        val list = listOf(programme(0, 60_000))
        assertSame(list, EpgShift.apply(list, 0))
        assertSame(list[0], EpgShift.apply(list[0], 0))
        assertEquals(500L, EpgShift.toStored(500L, 0))
    }

}
