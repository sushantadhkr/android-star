package com.personal.base.platform.capabilities

import android.content.Context
import com.personal.base.core.capability.CapabilityProvider
import com.personal.base.core.capability.CapabilityStatus
import com.personal.base.core.capability.CapabilityType
import com.personal.base.core.logging.PlatformLogger

/**
 * 29. RemoteInputCapabilityProvider: keyboard, mouse, and gamepad input injection.
 */
class RemoteInputCapabilityProvider(
    private val context: Context,
    private val accessibilityProvider: AccessibilityCapabilityProvider,
    private val logger: PlatformLogger
) : CapabilityProvider {

    override val type: CapabilityType = CapabilityType.REMOTE_INPUT

    fun injectTap(x: Float, y: Float): Boolean {
        logger.i("RemoteInput", "Injecting mouse tap at ($x, $y)")
        return accessibilityProvider.injectTap(x, y)
    }

    override fun checkStatus(): CapabilityStatus = accessibilityProvider.checkStatus()
    override fun release() {}
}
