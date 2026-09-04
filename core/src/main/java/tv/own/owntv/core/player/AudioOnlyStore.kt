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

private val Context.audioOnlyStore: DataStore<Preferences> by preferencesDataStore(name = "owntv_audio_only")

/**
 * Channels the user last watched with the picture switched off, so a radio station opens without a
 * video decoder next time and a television channel does not. Self-learning in exactly the same way as
 * [ForceMpvStore]: the user turns the picture off once, and that one channel remembers.
 *
 * Keyed by [enginePinKey] — sourceId + media type + provider remoteId — because channel rows are
 * REPLACE-upserted on every playlist sync, so a Room id or a stream URL would be forgotten on the next
 * refresh (and a Stalker URL is a single-use token that is never the same twice).
 *
 * Honoured only while the "Remember per channel" setting is on; the store keeps its entries when that
 * is switched off so turning it back on restores what the user had taught it.
 */
class AudioOnlyStore(private val context: Context) {
    private val key = stringSetPreferencesKey("keys")

    val keys: Flow<Set<String>> = context.audioOnlyStore.data.map { it[key] ?: emptySet() }

    /** Was this item last watched without a picture? */
    suspend fun isAudioOnly(pinKey: String): Boolean = keys.first().contains(pinKey)

    /** Record — or clear — the sound-only choice for one item. */
    suspend fun set(pinKey: String, audioOnly: Boolean) {
        context.audioOnlyStore.edit { prefs ->
            val current = prefs[key] ?: emptySet()
            prefs[key] = if (audioOnly) current + pinKey else current - pinKey
        }
    }
}
