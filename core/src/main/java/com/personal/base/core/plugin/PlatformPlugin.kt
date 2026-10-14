package com.personal.base.core.plugin

import com.personal.base.core.capability.CapabilityType
import com.personal.base.core.command.CommandResult
import com.personal.base.core.command.RemoteCommand

/**
 * Base interface for all modular plugins.
 * Future features are implemented as plugins complying with this interface.
 */
interface PlatformPlugin {
    val id: String
    val name: String
    val version: String
    val description: String
    val requiredCapabilities: Set<CapabilityType>

    /**
     * Called when the plugin is initialized by [PluginManager].
     */
    fun onInitialize(context: PlatformContext)

    /**
     * Dispatches an incoming remote or local command to the plugin.
     */
    suspend fun handleCommand(command: RemoteCommand): CommandResult

    /**
     * Periodic background execution callback invoked by the platform background worker.
     */
    suspend fun onPeriodicSync() {}

    /**
     * Called when the plugin is disabled or the platform is shutting down.
     */
    fun onDestroy()
}
