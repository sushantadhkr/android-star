package com.personal.base.core.plugin

import com.personal.base.core.capability.CapabilityStatus
import com.personal.base.core.capability.PlatformCapabilityRegistry
import com.personal.base.core.command.CommandResult
import com.personal.base.core.command.CommandValidator
import com.personal.base.core.command.RemoteCommand
import com.personal.base.core.command.ValidationResult
import com.personal.base.core.config.ConfigRepository
import com.personal.base.core.logging.PlatformLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import kotlin.system.measureTimeMillis

data class PluginInfo(
    val id: String,
    val name: String,
    val version: String,
    val description: String,
    val isEnabled: Boolean,
    val requiredCapabilities: List<String>,
    val missingCapabilities: List<String>
)

/**
 * Manages the registration, enablement, lifecycle, and command routing for all platform plugins.
 */
class PluginManager(
    private val platformContext: PlatformContext,
    private val capabilityRegistry: PlatformCapabilityRegistry,
    private val configRepository: ConfigRepository,
    private val commandValidator: CommandValidator,
    private val logger: PlatformLogger
) {

    private val plugins = ConcurrentHashMap<String, PlatformPlugin>()
    private val _pluginsState = MutableStateFlow<List<PluginInfo>>(emptyList())
    val pluginsState: StateFlow<List<PluginInfo>> = _pluginsState.asStateFlow()

    /**
     * Registers and initializes a plugin if enabled.
     */
    fun registerPlugin(plugin: PlatformPlugin) {
        plugins[plugin.id] = plugin
        val isEnabled = configRepository.isPluginEnabled(plugin.id)
        if (isEnabled) {
            try {
                plugin.onInitialize(platformContext)
                logger.i("PluginManager", "Initialized plugin: ${plugin.name} [${plugin.id}]")
            } catch (e: Exception) {
                logger.e("PluginManager", "Failed to initialize plugin: ${plugin.id}", e.message)
            }
        }
        updateState()
    }

    /**
     * Unregisters a plugin.
     */
    fun unregisterPlugin(pluginId: String) {
        val plugin = plugins.remove(pluginId)
        plugin?.onDestroy()
        updateState()
    }

    /**
     * Toggles whether a plugin is enabled or disabled.
     */
    fun setPluginEnabled(pluginId: String, enabled: Boolean) {
        val plugin = plugins[pluginId] ?: return
        configRepository.setPluginEnabled(pluginId, enabled)
        if (enabled) {
            try {
                plugin.onInitialize(platformContext)
                logger.i("PluginManager", "Enabled and initialized plugin: $pluginId")
            } catch (e: Exception) {
                logger.e("PluginManager", "Error initializing plugin $pluginId on enable", e.message)
            }
        } else {
            try {
                plugin.onDestroy()
                logger.i("PluginManager", "Disabled plugin: $pluginId")
            } catch (e: Exception) {
                logger.e("PluginManager", "Error destroying plugin $pluginId", e.message)
            }
        }
        updateState()
    }

    /**
     * Dispatches an incoming remote or local command to the target plugin.
     */
    suspend fun dispatchCommand(command: RemoteCommand): CommandResult {
        logger.i("PluginManager", "Dispatching command: target=${command.targetPluginId}, action=${command.action}, id=${command.id}")

        // 1. Validate signature & TTL
        when (val validation = commandValidator.validate(command)) {
            is ValidationResult.Rejected -> {
                return CommandResult.failure(command.id, "Validation failed: ${validation.reason}", "VALIDATION_FAILED")
            }
            ValidationResult.Accepted -> {}
        }

        // 2. Locate target plugin
        val plugin = plugins[command.targetPluginId]
            ?: return CommandResult.failure(command.id, "Plugin '${command.targetPluginId}' not found", "PLUGIN_NOT_FOUND")

        // 3. Check enabled state
        if (!configRepository.isPluginEnabled(plugin.id)) {
            return CommandResult.failure(command.id, "Plugin '${plugin.id}' is disabled", "PLUGIN_DISABLED")
        }

        // 4. Check capability requirements & user role authorization
        for (cap in plugin.requiredCapabilities) {
            if (!commandValidator.checkCapabilityAccess(command.role, cap)) {
                return CommandResult.failure(
                    command.id,
                    "Role '${command.role}' unauthorized for capability '${cap.displayName}'",
                    "UNAUTHORIZED"
                )
            }
            val status = capabilityRegistry.getStatus(cap)
            if (status !is CapabilityStatus.Available) {
                return CommandResult.failure(
                    command.id,
                    "Required capability '${cap.displayName}' is currently unavailable: $status",
                    "CAPABILITY_UNAVAILABLE"
                )
            }
        }

        // 5. Execute command with latency measurement
        var result: CommandResult
        val durationMs = measureTimeMillis {
            result = try {
                plugin.handleCommand(command)
            } catch (e: Exception) {
                logger.e("PluginManager", "Exception during command execution in ${plugin.id}", e.message)
                CommandResult.failure(command.id, "Internal error in ${plugin.name}: ${e.localizedMessage}")
            }
        }

        return result.copy(executionTimeMs = durationMs)
    }

    /**
     * Executes periodic sync across all active plugins.
     */
    suspend fun runPeriodicSync() {
        for (plugin in plugins.values) {
            if (configRepository.isPluginEnabled(plugin.id)) {
                try {
                    plugin.onPeriodicSync()
                } catch (e: Exception) {
                    logger.w("PluginManager", "Error during periodic sync of plugin ${plugin.id}", e.message)
                }
            }
        }
    }

    fun getPlugin(pluginId: String): PlatformPlugin? = plugins[pluginId]

    fun getAllPlugins(): List<PlatformPlugin> = plugins.values.toList()

    private fun updateState() {
        val list = plugins.values.map { p ->
            val isEnabled = configRepository.isPluginEnabled(p.id)
            val missing = p.requiredCapabilities
                .filter { capabilityRegistry.getStatus(it) !is CapabilityStatus.Available }
                .map { it.displayName }

            PluginInfo(
                id = p.id,
                name = p.name,
                version = p.version,
                description = p.description,
                isEnabled = isEnabled,
                requiredCapabilities = p.requiredCapabilities.map { it.displayName },
                missingCapabilities = missing
            )
        }
        _pluginsState.value = list
    }
}
