package com.personal.base.platform

import android.content.Context
import com.personal.base.core.capability.CapabilityProvider
import com.personal.base.core.capability.CapabilityStatus
import com.personal.base.core.capability.CapabilityType
import com.personal.base.core.capability.PlatformCapabilityRegistry
import com.personal.base.core.logging.PlatformLogger
import com.personal.base.core.plugin.PlatformContext
import com.personal.base.core.plugin.PlatformLocation
import com.personal.base.platform.capabilities.ClipboardCapabilityProvider
import com.personal.base.platform.capabilities.DeviceActionCapabilityProvider
import com.personal.base.platform.capabilities.LocationCapabilityProvider
import com.personal.base.platform.capabilities.NotificationCapabilityProvider
import com.personal.base.platform.capabilities.SharingCapabilityProvider

/**
 * Concrete implementation of the PlatformContext facade provided to all modular plugins.
 */
class PlatformContextImpl(
    private val capabilityRegistry: PlatformCapabilityRegistry,
    override val logger: PlatformLogger,
    private val context: Context
) : PlatformContext {

    override fun appContext(): Context = context

    override fun hasCapability(type: CapabilityType): Boolean {
        return capabilityRegistry.isAvailable(type)
    }

    override fun getCapabilityStatus(type: CapabilityType): CapabilityStatus {
        return capabilityRegistry.getStatus(type)
    }

    override fun <T : CapabilityProvider> getCapability(type: CapabilityType): T? {
        return capabilityRegistry.getProvider(type)
    }

    override fun <T : CapabilityProvider> getCapabilityByClass(clazz: Class<T>): T? {
        return capabilityRegistry.getProviderByClass(clazz)
    }

    override fun postNotification(title: String, message: String, priorityHigh: Boolean) {
        val provider: NotificationCapabilityProvider? = capabilityRegistry.getProvider(CapabilityType.NOTIFICATIONS)
        provider?.postNotification(title, message, priorityHigh)
    }

    override fun triggerHaptic(durationMs: Long) {
        val provider: DeviceActionCapabilityProvider? = capabilityRegistry.getProvider(CapabilityType.DEVICE_ACTIONS)
        provider?.vibrate(durationMs)
    }

    override fun setTorch(enabled: Boolean): Boolean {
        val provider: DeviceActionCapabilityProvider? = capabilityRegistry.getProvider(CapabilityType.DEVICE_ACTIONS)
        return provider?.setTorch(enabled) ?: false
    }

    override fun getBatteryPercentage(): Int {
        val provider: DeviceActionCapabilityProvider? = capabilityRegistry.getProvider(CapabilityType.DEVICE_ACTIONS)
        return provider?.getBatteryLevel() ?: -1
    }

    override fun getClipboardContent(): String? {
        val provider: ClipboardCapabilityProvider? = capabilityRegistry.getProvider(CapabilityType.CLIPBOARD)
        return provider?.getText()
    }

    override fun setClipboardContent(text: String, label: String) {
        val provider: ClipboardCapabilityProvider? = capabilityRegistry.getProvider(CapabilityType.CLIPBOARD)
        provider?.setText(text, label)
    }

    override fun shareText(text: String, title: String) {
        val provider: SharingCapabilityProvider? = capabilityRegistry.getProvider(CapabilityType.SHARING)
        provider?.shareText(text, title)
    }

    override suspend fun getFreshLocation(): PlatformLocation? {
        val provider: LocationCapabilityProvider? = capabilityRegistry.getProvider(CapabilityType.LOCATION)
        return provider?.getFreshLocation()
    }
}
