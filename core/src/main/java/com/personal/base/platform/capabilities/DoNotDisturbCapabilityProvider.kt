package com.personal.base.platform.capabilities

import android.app.NotificationManager
import android.content.Context
import android.os.Build
import com.personal.base.core.capability.CapabilityProvider
import com.personal.base.core.capability.CapabilityStatus
import com.personal.base.core.capability.CapabilityType
import com.personal.base.core.logging.PlatformLogger

/**
 * 17. DoNotDisturbCapabilityProvider: interruption filter control (All, Priority, None, Alarms).
 */
class DoNotDisturbCapabilityProvider(
    private val context: Context,
    private val logger: PlatformLogger
) : CapabilityProvider {

    override val type: CapabilityType = CapabilityType.DO_NOT_DISTURB

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    val isNotificationPolicyAccessGranted: Boolean
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            notificationManager.isNotificationPolicyAccessGranted
        } else true

    fun setInterruptionFilter(filter: Int): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!notificationManager.isNotificationPolicyAccessGranted) {
                logger.w("DNDCapability", "Cannot set DND filter: ACCESS_NOTIFICATION_POLICY not granted")
                return false
            }
            notificationManager.setInterruptionFilter(filter)
            logger.i("DNDCapability", "Set DND interruption filter to: $filter")
            return true
        }
        return false
    }

    override fun checkStatus(): CapabilityStatus {
        return if (isNotificationPolicyAccessGranted) CapabilityStatus.Available
        else CapabilityStatus.PermissionRequired(listOf(android.Manifest.permission.ACCESS_NOTIFICATION_POLICY))
    }

    override fun release() {}
}
