package com.personal.base.core.runtime

import android.content.Context
import android.content.SharedPreferences
import com.personal.base.core.logging.PlatformLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * High-performance typed preferences and configuration storage with live reactive flows.
 */
class DataStoreSettings(
    context: Context,
    private val logger: PlatformLogger
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("android_star_datastore_settings", Context.MODE_PRIVATE)

    private val _settingsMap = MutableStateFlow<Map<String, Any>>(loadAll())
    val settingsMap: StateFlow<Map<String, Any>> = _settingsMap.asStateFlow()

    private fun loadAll(): Map<String, Any> {
        return prefs.all.mapValues { it.value ?: "" }
    }

    fun getString(key: String, default: String = ""): String = prefs.getString(key, default) ?: default

    fun setString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
        _settingsMap.value = loadAll()
    }

    fun getBoolean(key: String, default: Boolean = false): Boolean = prefs.getBoolean(key, default)

    fun setBoolean(key: String, value: Boolean) {
        prefs.edit().putBoolean(key, value).apply()
        _settingsMap.value = loadAll()
    }

    fun getInt(key: String, default: Int = 0): Int = prefs.getInt(key, default)

    fun setInt(key: String, value: Int) {
        prefs.edit().putInt(key, value).apply()
        _settingsMap.value = loadAll()
    }

    fun getLong(key: String, default: Long = 0L): Long = prefs.getLong(key, default)

    fun setLong(key: String, value: Long) {
        prefs.edit().putLong(key, value).apply()
        _settingsMap.value = loadAll()
    }

    fun clear() {
        prefs.edit().clear().apply()
        _settingsMap.value = emptyMap()
    }
}
