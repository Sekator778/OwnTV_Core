package tv.own.owntv.core.player

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.forceMpvStore: DataStore<Preferences> by preferencesDataStore(name = "owntv_force_mpv")

/**
 * Channels the user has pinned to the **mpv** engine ("compatibility mode") because ExoPlayer can't play
 * them cleanly — UHD-HEVC macroblocking on some VPUs, or any stream that only mpv decodes. Self-learning:
 * the user flips it once and the channel opens straight on mpv forever after.
 *
 * Keyed by [streamUrl][tv.own.owntv.core.database.entity.ChannelEntity.streamUrl] — the only stable channel
 * identity across playlist re-syncs. Channel rows are REPLACE-upserted on every sync, so a column on the
 * channel (or its Room id) would be wiped on refresh; the stream URL is not.
 */
class ForceMpvStore(private val context: Context) {
    private val key = stringSetPreferencesKey("urls")

    val urls: Flow<Set<String>> = context.forceMpvStore.data.map { it[key] ?: emptySet() }

    suspend fun set(url: String, on: Boolean) {
        context.forceMpvStore.edit { prefs ->
            val cur = prefs[key] ?: emptySet()
            prefs[key] = if (on) cur + url else cur - url
        }
    }

    // --- Backup / restore (optional section; keyed by stream URL, no id remapping needed) ---

    /** Current pinned URLs, for backup export. */
    suspend fun exportUrls(): Set<String> =
        context.forceMpvStore.data.first()[key] ?: emptySet()

    /** Merge [urls] into the current pins on restore (union — never drops existing pins). */
    suspend fun importUrls(urls: Collection<String>) {
        val clean = urls.filterNotNull().map { it.trim() }.filter { it.isNotEmpty() }
        if (clean.isEmpty()) return
        context.forceMpvStore.edit { prefs -> prefs[key] = (prefs[key] ?: emptySet()) + clean }
    }
}
