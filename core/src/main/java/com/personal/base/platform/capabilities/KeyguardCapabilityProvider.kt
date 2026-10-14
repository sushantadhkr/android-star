package com.personal.base.platform.capabilities

import android.app.KeyguardManager
import android.content.Context
import com.personal.base.core.capability.CapabilityProvider
import com.personal.base.core.capability.CapabilityStatus
import com.personal.base.core.capability.CapabilityType
import com.personal.base.core.logging.PlatformLogger

/**
 * 30. KeyguardCapabilityProvider: lock state, unlock events, and device security keyguard.
 */
class KeyguardCapabilityProvider(
    private val context: Context,
    private val logger: PlatformLogger
) : CapabilityProvider {

    override val type: CapabilityType = CapabilityType.KEYGUARD

    private val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager

    val isKeyguardLocked: Boolean
        get() = keyguardManager?.isKeyguardLocked == true

    val isDeviceSecure: Boolean
        get() = keyguardManager?.isDeviceSecure == true

    override fun checkStatus(): CapabilityStatus = CapabilityStatus.Available
    override fun release() {}
}
