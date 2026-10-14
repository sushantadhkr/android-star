package com.personal.base.core.security

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The platform's lock gate.
 *
 * This is a pinned Platform surface: it is not an App, cannot be moved or hidden, and is not
 * governed by any App's toggle. It answers one question — must the platform show the lock screen?
 *
 * Locking itself is performed by the core biometric capability; this class only holds the policy
 * and the resulting state.
 */
class DeviceLockGate(
    context: Context,
    private val policy: IdleLockPolicy,
    private val logger: com.personal.base.core.logging.PlatformLogger
) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _mustUnlock = MutableStateFlow(false)
    val mustUnlock: StateFlow<Boolean> = _mustUnlock.asStateFlow()

    /** The active inactivity policy, so Settings can render the current selection. */
    val policyTimeout: StateFlow<LockTimeout> = policy.timeout

    init {
        policy.setTimeout(LockTimeout.fromNameOrDefault(prefs.getString(KEY_TIMEOUT, null)))
    }

    /** Any interaction by an authenticated user keeps the gate open. */
    fun onUserActivity() {
        policy.recordActivity()
    }

    /** Persisted inactivity policy. */
    fun setTimeout(timeout: LockTimeout) {
        policy.setTimeout(timeout)
        prefs.edit().putString(KEY_TIMEOUT, timeout.name).apply()
    }

    /**
     * Evaluates the policy. Returns true when the caller must present the lock screen.
     */
    fun evaluate(): Boolean {
        if (!policy.shouldLock()) return false
        if (_mustUnlock.value) return true
        _mustUnlock.value = true
        logger.i("DeviceLockGate", "Locking after ${policy.idleMillis()}ms idle (${policy.timeout.value.label})")
        return true
    }

    /** Called after successful biometric or device-credential authentication. */
    fun onUnlocked() {
        _mustUnlock.value = false
        policy.recordActivity()
    }

    /** Locks now, regardless of the inactivity policy. */
    fun lockNow() {
        _mustUnlock.value = true
    }

    private companion object {
        const val PREFS_NAME = "personal_base_device_lock"
        const val KEY_TIMEOUT = "lock_timeout"
    }
}