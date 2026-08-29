package tv.own.owntv.core.metadata

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.SecureRandom

private val Context.ownTvClientIdStore: DataStore<Preferences> by
    preferencesDataStore(name = "owntv_client_id")

/**
 * An opaque, random per-install id sent to the default metadata Worker as `x-owntv-client`.
 *
 * It exists so one misbehaving install can be identified and capped without punishing an IP address —
 * a single household NAT, a carrier CGNAT range or a VPN exit can carry hundreds of genuine users, so
 * IP is useless as an identity. 128 bits from [SecureRandom], generated on first use and stored in
 * DataStore.
 *
 * Deliberately NOT derived from anything: no ANDROID_ID, no hardware id, no account, no locale, no
 * playlist data. It is a random number and nothing else, it identifies an *install* rather than a
 * device or a person, and clearing the app's data (or reinstalling) simply produces a new one. Sent
 * only on the default-Worker tier — a user's own TMDB key or a self-hosted Worker never sees it.
 */
class OwnTVClientId(private val context: Context) {

    private val key = stringPreferencesKey("client_id")

    // Generation must not race: two concurrent metadata calls on a fresh install would otherwise each
    // mint an id and the second would overwrite the first.
    private val mutex = Mutex()

    @Volatile
    private var cached: String? = null

    /** The stored id, minting one on first use. Cached in memory so it costs one DataStore read per process. */
    suspend fun get(): String {
        cached?.let { return it }
        return mutex.withLock {
            cached?.let { return@withLock it }
            val existing = context.ownTvClientIdStore.data.first()[key]
            val id = if (!existing.isNullOrBlank()) {
                existing
            } else {
                val fresh = generate()
                context.ownTvClientIdStore.edit { prefs ->
                    // Re-check inside the transaction: another process (the app is single-process today,
                    // but widgets/workers make that an assumption rather than a guarantee) may have won.
                    val raced = prefs[key]
                    if (raced.isNullOrBlank()) prefs[key] = fresh
                }
                context.ownTvClientIdStore.data.first()[key] ?: fresh
            }
            cached = id
            id
        }
    }

    private fun generate(): String {
        val bytes = ByteArray(16) // 128 bits
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
