package com.personal.base.platform.capabilities

import android.app.usage.NetworkStatsManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.TrafficStats
import com.personal.base.core.capability.CapabilityProvider
import com.personal.base.core.capability.CapabilityStatus
import com.personal.base.core.capability.CapabilityType
import com.personal.base.core.logging.PlatformLogger

data class NetworkUsageMetrics(
    val mobileRxBytes: Long,
    val mobileTxBytes: Long,
    val totalRxBytes: Long,
    val totalTxBytes: Long
)

/**
 * 27. NetworkUsageCapabilityProvider: mobile and Wi-Fi data usage telemetry.
 */
class NetworkUsageCapabilityProvider(
    private val context: Context,
    private val logger: PlatformLogger
) : CapabilityProvider {

    override val type: CapabilityType = CapabilityType.NETWORK_USAGE

    fun getTrafficMetrics(): NetworkUsageMetrics {
        return NetworkUsageMetrics(
            mobileRxBytes = TrafficStats.getMobileRxBytes(),
            mobileTxBytes = TrafficStats.getMobileTxBytes(),
            totalRxBytes = TrafficStats.getTotalRxBytes(),
            totalTxBytes = TrafficStats.getTotalTxBytes()
        )
    }

    override fun checkStatus(): CapabilityStatus = CapabilityStatus.Available
    override fun release() {}
}
