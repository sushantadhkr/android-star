package com.personal.base.core.security

import com.personal.base.core.logging.PlatformLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * How long the platform tolerates inactivity before locking.
 *
 * [IMMEDIATE] means the platform re-locks as soon as the user leaves.
 */
enum class LockTimeout(val label: String, val millis: Long) {
    IMMEDIATE("Immediately after leaving", 0L),
    THIRTY_SECONDS("After 30 seconds", 30_000L),
    ONE_MINUTE("After 1 minute", 60_000L),
    FIVE_MINUTES("After 5 minutes", 300_000L),
    FIFTEEN_MINUTES("After 15 minutes", 900_000L),
    ONE_HOUR("After 1 hour", 3_600_000L);

    companion object {
        fun fromNameOrDefault(name: String?): LockTimeout =
            entries.firstOrNull { it.name == name } ?: FIVE_MINUTES
    }
}

/**
 * Tracks user inactivity and decides when the platform must lock.
 *
 * This is policy, not feature: the platform never carries device-lock logic of its own. It is
 * driven by the core biometric capability, and the shell only renders the result.
 *
 * The clock is injected so the rule can be tested without waiting in real time.
 */
class IdleLockPolicy(
    private val logger: PlatformLogger,
    private val now: () -> Long = System::currentTimeMillis
) {

    private var lastActivityAt: Long = now()

    private val _timeout = MutableStateFlow(LockTimeout.FIVE_MINUTES)
    val timeout: StateFlow<LockTimeout> = _timeout.asStateFlow()

    /** Notes that the user is present. Any interaction resets the idle window. */
    fun recordActivity() {
        lastActivityAt = now()
    }

    fun setTimeout(value: LockTimeout) {
        _timeout.value = value
        logger.i("IdleLockPolicy", "Lock timeout set to ${value.label}")
    }

    /** Idle duration in milliseconds. */
    fun idleMillis(): Long = (now() - lastActivityAt).coerceAtLeast(0L)

    /**
     * Whether the inactivity policy is currently satisfied.
     *
     * Immediate locking is deliberately strict: it must not trip at the instant the policy is
     * created, otherwise the platform would lock on startup before the user had any chance to
     * interact. It therefore requires at least one millisecond to have elapsed.
     */
    fun shouldLock(): Boolean {
        val idle = idleMillis()
        val threshold = _timeout.value.millis
        return if (threshold <= 0L) idle > 0L else idle >= threshold
    }

    /** Forces the idle window closed, used when the user leaves the foreground. */
    fun markLeft() {
        lastActivityAt = now()
    }
}