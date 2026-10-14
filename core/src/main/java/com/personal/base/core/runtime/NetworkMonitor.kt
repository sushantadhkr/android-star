package com.personal.base.core.runtime

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.personal.base.core.logging.PlatformLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class NetworkState(
    val isConnected: Boolean = false,
    val isWifi: Boolean = false,
    val isCellular: Boolean = false,
    val isMetered: Boolean = false,
    val linkDownstreamBandwidthKbps: Int = 0
)

/**
 * Real-time network connectivity monitor with automatic network state transition dispatch.
 */
class NetworkMonitor(
    context: Context,
    private val logger: PlatformLogger
) {
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val _networkState = MutableStateFlow(getCurrentState())
    val networkState: StateFlow<NetworkState> = _networkState.asStateFlow()

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            updateState()
            logger.i("NetworkMonitor", "Network interface available.")
        }

        override fun onLost(network: Network) {
            updateState()
            logger.w("NetworkMonitor", "Network interface lost.")
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            updateState()
        }
    }

    fun startMonitoring() {
        try {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            connectivityManager.registerNetworkCallback(request, networkCallback)
            logger.i("NetworkMonitor", "Started system network callback monitoring.")
        } catch (e: Exception) {
            logger.e("NetworkMonitor", "Failed to register network callback: ${e.message}", e.message)
        }
    }

    fun stopMonitoring() {
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        } catch (_: Exception) {}
    }

    private fun updateState() {
        _networkState.value = getCurrentState()
    }

    private fun getCurrentState(): NetworkState {
        val activeNetwork = connectivityManager.activeNetwork ?: return NetworkState()
        val caps = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return NetworkState()

        return NetworkState(
            isConnected = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
            isWifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
            isCellular = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
            isMetered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
            linkDownstreamBandwidthKbps = caps.linkDownstreamBandwidthKbps
        )
    }
}
