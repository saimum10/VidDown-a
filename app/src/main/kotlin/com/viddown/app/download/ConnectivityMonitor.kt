package com.viddown.app.download

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.viddown.download.NetworkState
import com.viddown.download.classifyNetwork
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Follows the device's default network for the whole life of the process (one callback, never unregistered).
 * Needs only ACCESS_NETWORK_STATE. "Wi-Fi" means a Wi-Fi or Ethernet transport; everything else that is online counts as mobile.
 */
class ConnectivityMonitor(context: Context) {
    private val manager = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private val _state = MutableStateFlow(current())
    val state: StateFlow<NetworkState> = _state

    init {
        try {
            manager.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    _state.value = classify(manager.getNetworkCapabilities(network))
                }

                override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                    _state.value = classify(networkCapabilities)
                }

                override fun onLost(network: Network) {
                    // If another network takes over, onAvailable follows right away.
                    _state.value = NetworkState.OFFLINE
                }
            })
        } catch (e: RuntimeException) {
            // Too many callbacks registered, or the permission was removed: assume online so downloads still try (and
            // report a clear network error) instead of waiting forever.
            _state.value = if (_state.value == NetworkState.OFFLINE) NetworkState.MOBILE_OR_OTHER else _state.value
        }
    }

    private fun current(): NetworkState {
        val network = try {
            manager.activeNetwork
        } catch (e: RuntimeException) {
            null
        } ?: return NetworkState.OFFLINE
        return classify(manager.getNetworkCapabilities(network))
    }

    private fun classify(caps: NetworkCapabilities?): NetworkState {
        if (caps == null) return NetworkState.OFFLINE
        return classifyNetwork(
            hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
            wifiOrEthernet = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET),
        )
    }
}
