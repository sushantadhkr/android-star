package com.personal.base.core.platform

import com.personal.base.core.logging.PlatformLogger
import java.util.concurrent.ConcurrentHashMap

data class DevicePluginSettings(
    val peerId: String,
    val pluginId: String,
    val isEnabled: Boolean = true,
    val grantedCapabilities: Set<String> = emptySet()
)

/**
 * Per-device plugin toggles, configurations, and granular capability permission overrides.
 */
class PerDevicePluginConfig(
    private val logger: PlatformLogger
) {
    // Key: "$peerId:$pluginId" -> DevicePluginSettings
    private val configs = ConcurrentHashMap<String, DevicePluginSettings>()

    fun setConfig(settings: DevicePluginSettings) {
        val key = "${settings.peerId}:${settings.pluginId}"
        configs[key] = settings
        logger.i("PerDevicePluginConfig", "Saved device-plugin config for $key (enabled=${settings.isEnabled})")
    }

    fun getConfig(peerId: String, pluginId: String): DevicePluginSettings {
        val key = "$peerId:$pluginId"
        return configs[key] ?: DevicePluginSettings(peerId, pluginId, isEnabled = true)
    }
}
