package tv.own.owntv.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Observes network reachability so the UI can warn the user when they're offline (playback / sync
 * won't work). Emits the current state immediately and on every change.
 */
class ConnectivityObserver(private val context: Context) {

    private val cm: ConnectivityManager?
        get() = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    /**
     * A best-effort snapshot of whether the active network has validated internet.
     *
     * Both capabilities are required. `NET_CAPABILITY_INTERNET` only says the interface is *meant* to
     * carry internet — a TV with the Ethernet cable plugged into a dead router reports it forever.
     * `NET_CAPABILITY_VALIDATED` is the platform's own verdict that traffic actually reached the
     * outside, which is the thing the offline warning is about.
     */
    fun isOnlineNow(): Boolean {
        val manager = cm ?: return true
        val network = manager.activeNetwork ?: return false
        return manager.getNetworkCapabilities(network)?.hasInternet() == true
    }

    private fun NetworkCapabilities.hasInternet(): Boolean =
        hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)

    val isOnline: Flow<Boolean> = callbackFlow {
        val callback = object : ConnectivityManager.NetworkCallback() {
            // Every branch answers with the same two-capability test as isOnlineNow(): a callback that
            // reported plain reachability would immediately overwrite the poll's correct verdict.
            override fun onAvailable(network: Network) { trySend(isOnlineNow()) }
            override fun onLost(network: Network) { trySend(isOnlineNow()) }
            override fun onUnavailable() { trySend(false) }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                trySend(caps.hasInternet())
            }
        }
        trySend(isOnlineNow())
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val manager = cm
        manager?.registerNetworkCallback(request, callback)
        // Poll every 20s — on devices where a network interface stays "up" forever
        // (Ethernet without cable, some TV firmwares), callbacks never fire. This
        // ensures the offline banner still appears when internet is unreachable.
        val pollJob = launch { while (isActive) { delay(20_000); trySend(isOnlineNow()) } }
        awaitClose { pollJob.cancel(); runCatching { manager?.unregisterNetworkCallback(callback) } }
    }.distinctUntilChanged()
}
