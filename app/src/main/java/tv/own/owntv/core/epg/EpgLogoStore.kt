package tv.own.owntv.core.epg

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import tv.own.owntv.core.database.dao.EpgDao
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.features.settings.data.SettingsRepository

/**
 * "Use this guide's channel logos" (Settings → EPG Sources → add/edit a feed): channel logos taken from
 * an XMLTV feed's `<channel><icon src>` instead of the ones the playlist carries. Enabled **per EPG
 * source**, so one feed can supply logos while another only supplies programmes.
 *
 * Kept as a process-wide display override rather than being written into `channels.logoUrl` on purpose:
 * the provider logo stays untouched in the database, so turning a feed's toggle off restores it instantly
 * and a catalog re-sync (which REPLACE-upserts every channel row) can't wipe the user's choice.
 *
 * The map is Compose state, so flipping a toggle or finishing an EPG sync recomposes every logo on
 * screen. Nothing is loaded while no feed has it on — cold start stays free of EPG queries.
 */
object EpgLogoStore {

    /** Normalized EPG channel id → feed icon URL. Empty when no EPG source has logos enabled. */
    var icons by mutableStateOf<Map<String, String>>(emptyMap())
        private set

    /** Follow the enabled EPG sources; while any is enabled, follow their stored feed icons. */
    fun start(scope: CoroutineScope, settings: SettingsRepository, epgDao: EpgDao) {
        scope.launch {
            settings.epgUseLogos.collectLatest { sourceIds ->
                if (sourceIds.isEmpty()) {
                    icons = emptyMap()
                    return@collectLatest
                }
                epgDao.observeChannelIcons(sourceIds.toList()).collectLatest { rows ->
                    // Several enabled feeds can carry the same channel: first one wins, which is stable
                    // because the DAO returns rows in source order.
                    icons = rows.associate { it.epgChannelId to it.iconUrl }
                }
            }
        }
    }

    /** The feed icon for [epgChannelId], or null when there is none / no feed has logos enabled. */
    fun iconFor(epgChannelId: String?): String? {
        if (icons.isEmpty() || epgChannelId.isNullOrBlank()) return null
        return icons[epgChannelId.trim().lowercase()]
    }
}

/**
 * The logo to show for this channel: the EPG feed's icon when "Prefer EPG logos" is on and the feed has
 * one, otherwise the provider's own logo. Use this everywhere a channel logo is displayed or handed to
 * the player — `logoUrl` alone always means "what the playlist said".
 */
val ChannelEntity.displayLogoUrl: String?
    get() = EpgLogoStore.iconFor(epgChannelId) ?: logoUrl
