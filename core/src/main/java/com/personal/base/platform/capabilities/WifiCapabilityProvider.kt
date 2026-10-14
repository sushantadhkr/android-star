package com.personal.base.platform.capabilities

import android.content.Context
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import com.personal.base.core.capability.CapabilityProvider
import com.personal.base.core.capability.CapabilityStatus
import com.personal.base.core.capability.CapabilityType
import com.personal.base.core.logging.PlatformLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class WifiDirectPeer(
    val deviceName: String,
    val deviceAddress: String,
    val status: Int
)

/**
 * 1. WifiCapabilityProvider: scan, connect, Wi-Fi Direct (P2P), and hotspot telemetry.
 */
class WifiCapabilityProvider(
    private val context: Context,
    private val logger: PlatformLogger
) : CapabilityProvider {

    override val type: CapabilityType = CapabilityType.WIFI

    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private val p2pManager = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private var p2pChannel: WifiP2pManager.Channel? = null

    private val _p2pPeers = MutableStateFlow<List<WifiDirectPeer>>(emptyList())
    val p2pPeers: StateFlow<List<WifiDirectPeer>> = _p2pPeers.asStateFlow()

    init {
        try {
            if (p2pManager != null) {
                p2pChannel = p2pManager.initialize(context, context.mainLooper, null)
            }
        } catch (e: Exception) {
            logger.w("WifiCapability", "Wi-Fi Direct init failed: ${e.message}")
        }
    }

    val isWifiEnabled: Boolean
        get() = wifiManager?.isWifiEnabled == true

    fun discoverWifiDirectPeers() {
        if (p2pManager == null || p2pChannel == null) return
        try {
            p2pManager.discoverPeers(p2pChannel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    logger.i("WifiCapability", "Wi-Fi Direct discovery initiated.")
                }
                override fun onFailure(reasonCode: Int) {
                    logger.w("WifiCapability", "Wi-Fi Direct discovery failed code: $reasonCode")
                }
            })
        } catch (e: Exception) {
            logger.e("WifiCapability", "discoverWifiDirectPeers error: ${e.message}", e.message)
        }
    }

    override fun checkStatus(): CapabilityStatus {
        return if (wifiManager != null) CapabilityStatus.Available
        else CapabilityStatus.Unavailable("Wi-Fi hardware unavailable")
    }

    override fun release() {}
}
