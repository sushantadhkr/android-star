package com.personal.base.platform.capabilities

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import com.personal.base.core.capability.CapabilityProvider
import com.personal.base.core.capability.CapabilityStatus
import com.personal.base.core.capability.CapabilityType
import com.personal.base.core.logging.PlatformLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.Inet4Address
import java.net.NetworkInterface

data class NetworkTelemetry(
    val isConnected: Boolean = false,
    val isWifi: Boolean = false,
    val isCellular: Boolean = false,
    val isMetered: Boolean = false,
    val downstreamBandwidthKbps: Int = 0,
    val wifiIpAddress: String = "Offline",
    val wifiMacAddress: String = "Unavailable",
    val wifiSsid: String = "Disconnected",
    val wifiLinkSpeedMbps: Int = -1,
    val wifiFrequencyMhz: Int = -1
)

class ConnectivityCapabilityProvider(
    private val context: Context,
    private val logger: PlatformLogger
) : CapabilityProvider {

    override val type: CapabilityType = CapabilityType.CONNECTIVITY

    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    private val _networkState = MutableStateFlow(NetworkTelemetry())
    val networkState: StateFlow<NetworkTelemetry> = _networkState.asStateFlow()

    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override val status: CapabilityStatus
        get() = if (connectivityManager != null) CapabilityStatus.Available
                else CapabilityStatus.Unavailable("ConnectivityManager unavailable")

    override fun initialize() {
        val cm = connectivityManager ?: return
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                updateNetworkSnapshot()
            }
            override fun onLost(network: Network) {
                updateNetworkSnapshot()
            }
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                updateNetworkSnapshot()
            }
        }

        try {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            cm.registerNetworkCallback(request, networkCallback!!)
            updateNetworkSnapshot()
            logger.i("ConnectivityProvider", "Registered network state listener")
        } catch (e: Exception) {
            logger.w("ConnectivityProvider", "Failed to register network callback", e.message)
            updateNetworkSnapshot()
        }
    }

    override fun release() {
        networkCallback?.let {
            try {
                connectivityManager?.unregisterNetworkCallback(it)
            } catch (_: Exception) {}
        }
    }

    fun refreshTelemetry() = updateNetworkSnapshot()

    fun updateNetworkSnapshot() {
        val cm = connectivityManager ?: return
        val activeNetwork = cm.activeNetwork
        val caps = cm.getNetworkCapabilities(activeNetwork)

        if (caps == null) {
            _networkState.value = NetworkTelemetry()
            return
        }

        val hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        val isWifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        val isCellular = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
        val isNotMetered = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        val downstream = caps.linkDownstreamBandwidthKbps

        // Query real Wi-Fi telemetry
        val ip = getActiveIpAddress()
        val mac = getActiveMacAddress()
        val (ssid, linkSpeed, freq) = getWifiConnectionDetails()

        _networkState.value = NetworkTelemetry(
            isConnected = hasInternet,
            isWifi = isWifi,
            isCellular = isCellular,
            isMetered = !isNotMetered,
            downstreamBandwidthKbps = downstream,
            wifiIpAddress = ip,
            wifiMacAddress = mac,
            wifiSsid = ssid,
            wifiLinkSpeedMbps = linkSpeed,
            wifiFrequencyMhz = freq
        )
    }

    private fun getActiveIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val intf = interfaces.nextElement()
                if (intf.isLoopback || !intf.isUp) continue
                val addrs = intf.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        return addr.hostAddress ?: ""
                    }
                }
            }
        } catch (_: Exception) {}

        try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val ipInt = wm?.connectionInfo?.ipAddress ?: 0
            if (ipInt != 0) {
                return String.format(
                    "%d.%d.%d.%d",
                    ipInt and 0xff,
                    ipInt shr 8 and 0xff,
                    ipInt shr 16 and 0xff,
                    ipInt shr 24 and 0xff
                )
            }
        } catch (_: Exception) {}

        return "127.0.0.1"
    }

    private fun getActiveMacAddress(): String {
        try {
            val intf = NetworkInterface.getByName("wlan0")
                ?: NetworkInterface.getNetworkInterfaces().toList().firstOrNull { it.name.startsWith("wlan") }
            val macBytes = intf?.hardwareAddress
            if (macBytes != null && macBytes.isNotEmpty()) {
                return macBytes.joinToString(":") { "%02X".format(it) }
            }
        } catch (_: Exception) {}

        try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val bssid = wm?.connectionInfo?.bssid
            if (!bssid.isNullOrEmpty() && bssid != "02:00:00:00:00:00") {
                return bssid
            }
        } catch (_: Exception) {}

        return "02:00:00:00:00:00"
    }

    private fun getWifiConnectionDetails(): Triple<String, Int, Int> {
        return try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val info = wm?.connectionInfo
            val ssid = info?.ssid?.removeSurrounding("\"")?.takeIf { it != "<unknown ssid>" } ?: "Active Wi-Fi"
            val speed = info?.linkSpeed ?: -1
            val freq = info?.frequency ?: -1
            Triple(ssid, speed, freq)
        } catch (_: Exception) {
            Triple("Unknown", -1, -1)
        }
    }
}
