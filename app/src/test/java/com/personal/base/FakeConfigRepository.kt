package com.personal.base

import com.personal.base.core.config.ConfigRepository
import com.personal.base.core.config.PlatformConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class FakeConfigRepository(
    initialConfig: PlatformConfig = PlatformConfig()
) : ConfigRepository {

    private val _config = MutableStateFlow(initialConfig)
    override val config: StateFlow<PlatformConfig> = _config.asStateFlow()

    override fun updateConfig(modifier: (PlatformConfig) -> PlatformConfig) {
        _config.value = modifier(_config.value)
    }

    override fun isPluginEnabled(pluginId: String): Boolean {
        return _config.value.enabledPlugins.contains(pluginId)
    }

    override fun setPluginEnabled(pluginId: String, enabled: Boolean) {
        updateConfig { current ->
            val set = current.enabledPlugins.toMutableSet()
            if (enabled) set.add(pluginId) else set.remove(pluginId)
            current.copy(enabledPlugins = set)
        }
    }
}
