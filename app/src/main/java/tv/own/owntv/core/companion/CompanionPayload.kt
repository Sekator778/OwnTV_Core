package tv.own.owntv.core.companion

import tv.own.owntv.core.model.SourceType
import tv.own.owntv.core.sync.SyncScopeChoice

/**
 * One add-source submission from the Remote companion web form. A single shape covers all three
 * source kinds; irrelevant fields stay blank for a given [type] (e.g. Xtream leaves [portalUrl]/[mac]
 * blank, Stalker leaves [server]/[user]/[pass] blank).
 *
 * The remote browser only *fills* this — it never starts the import. The TV pre-fills its Add Source form from
 * the payload and the user presses Start Import.
 */
data class CompanionPayload(
    val type: SourceType,
    val name: String = "",
    /** Xtream server URL, or the M3U playlist URL. For an uploaded playlist this is rewritten by
     *  [tv.own.owntv.core.companion.CompanionController] to the absolute path of the saved file. */
    val server: String = "",
    /** Name of a playlist file uploaded through the companion page, e.g. `drm-test.m3u`; blank when
     *  the user gave a URL instead. Used only to name the copy saved on the TV. */
    val playlistFileName: String = "",
    /** The uploaded playlist's text. Blank when the user gave a URL. Never persisted as-is — the
     *  controller writes it to the TV's own storage and points [server] at that file. */
    val playlistContent: String = "",
    val user: String = "",
    val pass: String = "",
    val portalUrl: String = "",
    val mac: String = "",
    val serialNumber: String = "",
    val deviceId: String = "",
    val deviceId2: String = "",
    val signature: String = "",
    val userAgent: String = "",
    val epgUrl: String = "",
    /** Name of a [tv.own.owntv.features.settings.data.PlaylistAutoRefresh] entry; defaults to OFF. */
    val autoRefresh: String = "OFF",
    val syncLive: SyncScopeChoice = SyncScopeChoice.Now,
    val syncMovies: SyncScopeChoice = SyncScopeChoice.Now,
    val syncSeries: SyncScopeChoice = SyncScopeChoice.Now,
    val isDefault: Boolean = false,
)
