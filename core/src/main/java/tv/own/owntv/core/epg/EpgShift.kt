package tv.own.owntv.core.epg

import tv.own.owntv.core.customize.CustomizeKeys
import tv.own.owntv.core.customize.SectionCustomizations
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.database.entity.EpgProgrammeEntity

/**
 * Moves a channel's guide in time.
 *
 * Providers routinely publish ONE XMLTV feed for a network and hang both its East and West streams
 * off it — so the West channel's guide runs hours ahead of what's actually on screen. The user picks
 * a shift (globally in Settings, or per channel in the long-press menu) and it is applied here, at
 * the point programmes are read: every consumer downstream (guide grid, now/next, "On now", the
 * catch-up picker and the archive URLs built from those programmes) then sees the corrected wall
 * clock, with no stored timestamp ever rewritten. A resync or a guide refresh can't undo it.
 *
 * Sign convention: a positive shift moves a programme LATER. So a guide running 3 h ahead of the
 * channel is corrected with −180.
 */
object EpgShift {

    /** Minutes to move [ch]'s guide by: the per-channel override if set, else [globalMinutes]. */
    fun minutesFor(cust: SectionCustomizations, ch: ChannelEntity, globalMinutes: Int): Int =
        forKey(cust, CustomizeKeys.channel(ch), globalMinutes)

    /** [minutesFor] for a pre-computed customization key. */
    fun forKey(cust: SectionCustomizations, itemKey: String, globalMinutes: Int): Int =
        cust.epgShifts[itemKey]?.toIntOrNull() ?: globalMinutes

    /** The per-channel override alone (null = follow the global offset). */
    fun overrideFor(cust: SectionCustomizations, ch: ChannelEntity): Int? =
        cust.epgShifts[CustomizeKeys.channel(ch)]?.toIntOrNull()

    /**
     * The stored-time instant matching display time [displayMs] — what a query window must be
     * expressed in, since the rows in the database still carry the feed's own (unshifted) times.
     */
    fun toStored(displayMs: Long, minutes: Int): Long =
        if (minutes == 0) displayMs else displayMs - minutes * 60_000L

    /** [p] with its times moved into display time. Returns the same instance when there's no shift. */
    fun apply(p: EpgProgrammeEntity, minutes: Int): EpgProgrammeEntity {
        if (minutes == 0) return p
        val delta = minutes * 60_000L
        return p.copy(startMs = p.startMs + delta, stopMs = p.stopMs + delta)
    }

    /** [apply] over a list. Returns the same list instance when there's no shift (the common case). */
    fun apply(list: List<EpgProgrammeEntity>, minutes: Int): List<EpgProgrammeEntity> =
        if (minutes == 0) list else list.map { apply(it, minutes) }

}
