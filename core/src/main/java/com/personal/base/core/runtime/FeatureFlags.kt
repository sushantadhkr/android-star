package com.personal.base.core.runtime

import com.personal.base.core.logging.PlatformLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * Feature flag registry supporting local defaults and dynamic remote overrides.
 */
class FeatureFlags(
    private val logger: PlatformLogger
) {
    private val flags = ConcurrentHashMap<String, Boolean>()
    private val _flagsFlow = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val flagsFlow: StateFlow<Map<String, Boolean>> = _flagsFlow.asStateFlow()

    init {
        // Platform defaults
        flags["enable_file_server"] = true
        flags["enable_p2p_mesh"] = true
        flags["enable_biometric_gate"] = true
        flags["enable_battery_saver_mode"] = false
        flags["enable_strict_mode"] = false
        _flagsFlow.value = flags.toMap()
    }

    fun isEnabled(flagKey: String, default: Boolean = false): Boolean {
        return flags[flagKey] ?: default
    }

    fun setFlag(flagKey: String, enabled: Boolean) {
        flags[flagKey] = enabled
        _flagsFlow.value = flags.toMap()
        logger.i("FeatureFlags", "Updated flag '$flagKey' = $enabled")
    }
}
