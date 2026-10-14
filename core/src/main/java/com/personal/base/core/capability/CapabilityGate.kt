package com.personal.base.core.capability

import android.content.Context
import android.content.SharedPreferences

/**
 * Master and per-capability on/off control.
 *
 * Extracted as an interface so the catalogue's listing rules can be tested without Android.
 */
interface CapabilityGateContract {
    fun isGloballyEnabled(): Boolean
    fun setGloballyEnabled(enabled: Boolean)
    fun isEnabled(type: CapabilityType): Boolean
    fun setEnabled(type: CapabilityType, enabled: Boolean)
}

/**
 * Master and per-capability on/off control for the pinned Platform Capabilities API.
 *
 * This is a platform-level surface, not an App: it is pinned in place and the user may only switch
 * it off and on. When the master switch is off, every capability is unavailable regardless of its
 * individual setting.
 */
class CapabilityGate(context: Context) : CapabilityGateContract {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** The master switch. Off disables every capability at once. */
    override fun isGloballyEnabled(): Boolean = prefs.getBoolean(KEY_MASTER, true)

    override fun setGloballyEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_MASTER, enabled).apply()
    }

    /**
     * Whether an individual capability is switched on. Defaults to on: capabilities are available
     * unless the user has deliberately switched them off.
     */
    override fun isEnabled(type: CapabilityType): Boolean =
        prefs.getBoolean(key(type), true)

    override fun setEnabled(type: CapabilityType, enabled: Boolean) {
        prefs.edit().putBoolean(key(type), enabled).apply()
    }

    /**
     * Whether a capability may actually be used: the master switch is on, the individual switch is
     * on, the capability is registered, and its own status is usable.
     */
    fun isUsable(type: CapabilityType, registry: PlatformCapabilityRegistry): Boolean {
        if (!isGloballyEnabled()) return false
        if (!isEnabled(type)) return false
        return registry.getStatus(type).isUsable
    }

    /**
     * Whether the capability is currently reachable but requires attention — for example a
     * permission has not been granted. Such capabilities are listed, not hidden.
     */
    fun isListedButBlocked(type: CapabilityType, registry: PlatformCapabilityRegistry): Boolean =
        !registry.getStatus(type).isUsable

    private fun key(type: CapabilityType) = "$KEY_PREFIX${type.name}"

    private companion object {
        const val PREFS_NAME = "personal_base_capability_gate"
        const val KEY_MASTER = "master_enabled"
        const val KEY_PREFIX = "cap_"
    }
}