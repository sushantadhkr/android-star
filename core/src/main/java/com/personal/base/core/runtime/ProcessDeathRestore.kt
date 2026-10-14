package com.personal.base.core.runtime

import android.os.Bundle
import com.personal.base.core.logging.PlatformLogger
import java.util.concurrent.ConcurrentHashMap

/**
 * Handles saving and restoring state across process recreation and low-memory kills.
 */
class ProcessDeathRestore(
    private val logger: PlatformLogger
) {
    private val savedBundle = ConcurrentHashMap<String, String>()

    fun saveString(key: String, value: String) {
        savedBundle[key] = value
    }

    fun getString(key: String): String? = savedBundle[key]

    fun writeToBundle(outState: Bundle) {
        savedBundle.forEach { (k, v) ->
            outState.putString("pdr_$k", v)
        }
        logger.i("ProcessDeathRestore", "Saved ${savedBundle.size} items to outState bundle.")
    }

    fun restoreFromBundle(savedInstanceState: Bundle?) {
        if (savedInstanceState == null) return
        savedInstanceState.keySet()
            .filter { it.startsWith("pdr_") }
            .forEach { key ->
                val actualKey = key.removePrefix("pdr_")
                savedInstanceState.getString(key)?.let { savedBundle[actualKey] = it }
            }
        logger.i("ProcessDeathRestore", "Restored ${savedBundle.size} items from saved bundle.")
    }
}
