package tv.own.owntv.core.player

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.archiveDecodeStore: DataStore<Preferences> by preferencesDataStore(name = "owntv_archive_decode")

/**
 * Panels (`host:port`) whose catch-up archive has been caught needing a **software** video decoder.
 *
 * This is the one runtime quirk worth remembering across app starts. The others in
 * [tv.own.owntv.player.LiveStreamQuirks] are re-learned within seconds of the first play, but this
 * one costs a *silent failed archive open* to learn — audio plays, no picture, and the engine has to
 * time out before it retries in software. Paying that once per app run for a panel whose timeshift
 * server is permanently mid-GOP is the wrong trade; the fault is a property of the provider's
 * archive mux and does not come and go.
 *
 * Only the negative-cost direction is persisted: a host in here opens its archives in software from
 * the start. Nothing ever writes "this host is fine" — an absent host simply tries hardware first,
 * so a provider that fixes its server is back on hardware after [forget] (never called automatically)
 * or a clear-data, and a healthy provider is never downgraded by a one-off hiccup because a single
 * failure is what puts a host in here in the first place.
 *
 * Keyed by host, not by [enginePinKey]: there is no catalog row behind an archive URL, and the
 * lesson is panel-wide by construction.
 */
class ArchiveDecodeStore(private val context: Context) {

    private val key = stringSetPreferencesKey("software_archive_hosts")

    /** Every host learned in a previous run. Read once at startup, off the main thread. */
    suspend fun hosts(): Set<String> = context.archiveDecodeStore.data.first()[key] ?: emptySet()

    suspend fun remember(host: String) {
        context.archiveDecodeStore.edit { prefs ->
            prefs[key] = (prefs[key] ?: emptySet()) + host
        }
    }

}
