package com.personal.base.core.config

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

@Serializable
data class PlatformConfig(
    val deviceId: String = UUID.randomUUID().toString(),
    val authSecretHmac: String = "base_secret_hmac_256",
    val backgroundSyncIntervalMinutes: Long = 15L,
    val isForegroundServiceEnabled: Boolean = true,
    val requireSignedCommands: Boolean = true,
    val allowLocalSimulation: Boolean = true,
    val strictPermissionEnforcement: Boolean = true,
    val enabledPlugins: Set<String> = setOf("system-diagnostics", "echo", "quick-actions", "security-audit")
)

/**
 * Interface-driven repository for platform configuration.
 */
interface ConfigRepository {
    val config: StateFlow<PlatformConfig>
    fun updateConfig(modifier: (PlatformConfig) -> PlatformConfig)
    fun isPluginEnabled(pluginId: String): Boolean
    fun setPluginEnabled(pluginId: String, enabled: Boolean)
}

/**
 * Android SharedPreferences persistent implementation of [ConfigRepository].
 */
class SharedPrefsConfigRepository(context: Context) : ConfigRepository {

    private val prefs: SharedPreferences = context.getSharedPreferences("personal_base_platform_prefs", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    private val _config = MutableStateFlow(loadConfig())
    override val config: StateFlow<PlatformConfig> = _config.asStateFlow()

    private fun loadConfig(): PlatformConfig {
        val raw = prefs.getString(KEY_CONFIG_JSON, null)
        return if (raw != null) {
            try {
                json.decodeFromString<PlatformConfig>(raw)
            } catch (e: Exception) {
                PlatformConfig()
            }
        } else {
            val defaultConfig = PlatformConfig()
            saveToPrefs(defaultConfig)
            defaultConfig
        }
    }

    private fun saveToPrefs(cfg: PlatformConfig) {
        val raw = json.encodeToString(cfg)
        prefs.edit().putString(KEY_CONFIG_JSON, raw).apply()
    }

    override fun updateConfig(modifier: (PlatformConfig) -> PlatformConfig) {
        val updated = modifier(_config.value)
        saveToPrefs(updated)
        _config.value = updated
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

    companion object {
        private const val KEY_CONFIG_JSON = "platform_config_json_v1"
    }
}
