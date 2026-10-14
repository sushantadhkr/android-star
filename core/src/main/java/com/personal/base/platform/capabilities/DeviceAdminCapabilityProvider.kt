package com.personal.base.platform.capabilities

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.personal.base.core.capability.CapabilityProvider
import com.personal.base.core.capability.CapabilityStatus
import com.personal.base.core.capability.CapabilityType
import com.personal.base.core.logging.PlatformLogger

class PlatformDeviceAdminReceiver : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) {}
    override fun onDisabled(context: Context, intent: Intent) {}
}

/**
 * 23. DeviceAdminCapabilityProvider: lock screen, password policies, and remote administration.
 */
class DeviceAdminCapabilityProvider(
    private val context: Context,
    private val logger: PlatformLogger
) : CapabilityProvider {

    override val type: CapabilityType = CapabilityType.DEVICE_ADMIN

    private val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
    private val adminComponent = ComponentName(context, PlatformDeviceAdminReceiver::class.java)

    val isAdminActive: Boolean
        get() = dpm?.isAdminActive(adminComponent) == true

    fun lockScreenNow(): Boolean {
        return if (isAdminActive) {
            dpm?.lockNow()
            logger.i("DeviceAdmin", "Invoked dpm.lockNow()")
            true
        } else {
            logger.w("DeviceAdmin", "Device admin not active")
            false
        }
    }

    override fun checkStatus(): CapabilityStatus {
        return if (isAdminActive) CapabilityStatus.Available
        else CapabilityStatus.Unavailable("Device administrator not activated")
    }

    override fun release() {}
}
