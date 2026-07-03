package tv.own.owntv.core.player

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.vodEngineStore: DataStore<Preferences> by preferencesDataStore(name = "owntv_vod_engine")

/** A movie/episode the user manually pinned to an engine via the player's gear toggle. */
enum class VodEnginePin { MPV, EXO }

/**
 * Movies/episodes the user manually switched to a specific engine with the player's gear toggle —
 * the VOD counterpart of [ForceMpvStore] (Live's "compatibility mode"). Self-learning: flip an item
 * once and it opens on that engine forever after, regardless of the global "Movies & Series player"
 * setting. Items never toggled keep following the setting.
 *
 * Keyed by stream URL — the only stable identity across playlist re-syncs (rows are REPLACE-upserted
 * on refresh, so Room ids don't survive; the URL does).
 */
class VodEngineStore(private val context: Context) {
    private val mpvKey = stringSetPreferencesKey("mpv_urls")
    private val exoKey = stringSetPreferencesKey("exo_urls")

    val mpvUrls: Flow<Set<String>> = context.vodEngineStore.data.map { it[mpvKey] ?: emptySet() }
    val exoUrls: Flow<Set<String>> = context.vodEngineStore.data.map { it[exoKey] ?: emptySet() }

    /** Pin [url] to [engine] (the gear toggle's target), replacing any previous pin for it. */
    suspend fun pin(url: String, engine: VodEnginePin) {
        context.vodEngineStore.edit { prefs ->
            val mpv = prefs[mpvKey] ?: emptySet()
            val exo = prefs[exoKey] ?: emptySet()
            prefs[mpvKey] = if (engine == VodEnginePin.MPV) mpv + url else mpv - url
            prefs[exoKey] = if (engine == VodEnginePin.EXO) exo + url else exo - url
        }
    }
}
