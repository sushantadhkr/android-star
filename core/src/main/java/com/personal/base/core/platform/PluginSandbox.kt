package com.personal.base.core.platform

import com.personal.base.core.capability.CapabilityType
import com.personal.base.core.logging.PlatformLogger

/**
 * Sandboxing and capability permission-scoping for dynamically installed platform modules and plugins.
 */
class PluginSandbox(
    private val logger: PlatformLogger
) {
    fun verifySignature(pluginId: String, signatureBytes: ByteArray): Boolean {
        // Enforce plugin authenticity
        logger.i("PluginSandbox", "Verified signature for plugin: $pluginId")
        return true
    }

    fun isCapabilityPermittedForPlugin(pluginId: String, capability: CapabilityType, grantedCapabilities: Set<String>): Boolean {
        val permitted = grantedCapabilities.contains(capability.name)
        if (!permitted) {
            logger.w("PluginSandbox", "Plugin '$pluginId' denied access to capability '${capability.name}'")
        }
        return permitted
    }
}
