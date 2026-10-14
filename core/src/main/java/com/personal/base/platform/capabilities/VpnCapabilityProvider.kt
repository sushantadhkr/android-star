package com.personal.base.platform.capabilities

import android.content.Context
import android.content.Intent
import android.net.VpnService
import com.personal.base.core.capability.CapabilityProvider
import com.personal.base.core.capability.CapabilityStatus
import com.personal.base.core.capability.CapabilityType
import com.personal.base.core.logging.PlatformLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class PlatformVpnService : VpnService() {
    companion object {
        var isTunnelRunning = false
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        isTunnelRunning = true
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        isTunnelRunning = false
    }
}

/**
 * 25. VpnCapabilityProvider: VpnService tunnel for secure peer access and mesh routing.
 */
class VpnCapabilityProvider(
    private val context: Context,
    private val logger: PlatformLogger
) : CapabilityProvider {

    override val type: CapabilityType = CapabilityType.VPN

    private val _isVpnActive = MutableStateFlow(false)
    val isVpnActive: StateFlow<Boolean> = _isVpnActive.asStateFlow()

    fun prepareVpnIntent(): Intent? {
        return VpnService.prepare(context)
    }

    fun startVpnService() {
        try {
            val intent = Intent(context, PlatformVpnService::class.java)
            context.startService(intent)
            _isVpnActive.value = true
            logger.i("VpnCapability", "Platform VPN service started.")
        } catch (e: Exception) {
            logger.e("VpnCapability", "Failed to start VPN service: ${e.message}", e.message)
        }
    }

    fun stopVpnService() {
        try {
            val intent = Intent(context, PlatformVpnService::class.java)
            context.stopService(intent)
            _isVpnActive.value = false
            logger.i("VpnCapability", "Platform VPN service stopped.")
        } catch (_: Exception) {}
    }

    override fun checkStatus(): CapabilityStatus = CapabilityStatus.Available
    override fun release() {
        stopVpnService()
    }
}
