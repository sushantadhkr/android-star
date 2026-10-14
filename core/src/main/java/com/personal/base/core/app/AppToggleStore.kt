package com.personal.base.core.app

import android.content.Context
import android.content.SharedPreferences

/**
 * Persisted on/off state for Apps.
 *
 * Extracted as an interface so lifecycle rules can be tested without Android.
 */
interface AppToggleStoreContract {
    fun isEnabled(appId: String): Boolean
    fun setEnabled(appId: String, enabled: Boolean)
}

/**
 * Persists which Apps the user has switched on.
 *
 * The toggle is a safety control the user alone owns, so its state must survive process death and
 * device reboot. State is cleared only when the user switches an App off.
 */
class AppToggleStore(context: Context) : AppToggleStoreContract {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Apps are enabled by default unless the user has explicitly switched them off. */
    override fun isEnabled(appId: String): Boolean = prefs.getInt(key(appId), ENABLED).let {
        // Store an int so "never seen" and "explicitly off" are distinguishable.
        it != DISABLED
    }

    override fun setEnabled(appId: String, enabled: Boolean) {
        prefs.edit().putInt(key(appId), if (enabled) ENABLED else DISABLED).apply()
    }

    /**
     * Removes all persisted toggle state. Used only when the platform itself is reset, never as a
     * side effect of an individual App being switched off.
     */
    fun clear() = prefs.edit().clear().apply()

    private fun key(appId: String) = "$KEY_PREFIX$appId"

    private companion object {
        const val PREFS_NAME = "personal_base_app_toggles"
        const val KEY_PREFIX = "app_enabled_"
        const val ENABLED = 1
        const val DISABLED = 0
    }
}