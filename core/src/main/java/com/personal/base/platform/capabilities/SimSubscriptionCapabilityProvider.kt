package com.personal.base.platform.capabilities

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.personal.base.core.capability.CapabilityProvider
import com.personal.base.core.capability.CapabilityStatus
import com.personal.base.core.capability.CapabilityType
import com.personal.base.core.logging.PlatformLogger

data class SimSlotInfo(
    val slotIndex: Int,
    val carrierName: String,
    val countryIso: String,
    val isDataRoaming: Boolean
)

/**
 * 28. SimSubscriptionCapabilityProvider: dual-SIM info, carrier names, and cellular metrics.
 */
class SimSubscriptionCapabilityProvider(
    private val context: Context,
    private val logger: PlatformLogger
) : CapabilityProvider {

    override val type: CapabilityType = CapabilityType.SIM_SUBSCRIPTION

    private val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
    private val subscriptionManager = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager

    fun getSimSlots(): List<SimSlotInfo> {
        val list = mutableListOf<SimSlotInfo>()
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            return list
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                val subs = subscriptionManager?.activeSubscriptionInfoList ?: emptyList()
                subs.forEach { sub ->
                    list.add(
                        SimSlotInfo(
                            slotIndex = sub.simSlotIndex,
                            carrierName = sub.carrierName?.toString() ?: "Unknown",
                            countryIso = sub.countryIso ?: "",
                            isDataRoaming = sub.dataRoaming == SubscriptionManager.DATA_ROAMING_ENABLE
                        )
                    )
                }
            }
        } catch (e: Exception) {
            logger.w("SimSubscription", "Error querying SIM subscriptions: ${e.message}")
        }
        return list
    }

    override fun checkStatus(): CapabilityStatus {
        val granted = ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
        return if (granted) CapabilityStatus.Available
        else CapabilityStatus.PermissionRequired(listOf(android.Manifest.permission.READ_PHONE_STATE))
    }

    override fun release() {}
}
