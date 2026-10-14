package com.personal.base.core.security

import com.personal.base.core.logging.PlatformLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * How long the platform tolerates being away before locking.
 *
 * The clock starts when the app leaves the foreground, not from the last tap — so "immediately"
 * means the moment you leave, never while you are still using it.
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
 * Decides when the platform must lock, based on how long the app has been *away*.
 *
 * The distinction that matters is foreground versus background. Locking is only ever considered
 * once the app has left the foreground; while the user is actively in the app, the platform stays
 * open regardless of the timeout. Without that split, an "immediate" policy would re-lock on the
 * next frame after the user unlocked, which is the bug this class exists to prevent.
 *
 * The clock is injected so the rule can be tested without waiting in real time.
 */
class IdleLockPolicy(
    private val logger: PlatformLogger,
    private val now: () -> Long = System::currentTimeMillis
) {

    private val _timeout = MutableStateFlow(LockTimeout.FIVE_MINUTES)
    val timeout: StateFlow<LockTimeout> = _timeout.asStateFlow()

    private val _isForeground = MutableStateFlow(true)
    val isForeground: StateFlow<Boolean> = _isForeground.asStateFlow()

    /** When the app left the foreground, or when it was last used while in the foreground. */
    private var awaySince: Long = now()

    fun setTimeout(value: LockTimeout) {
        _timeout.value = value
        logger.i("IdleLockPolicy", "Lock timeout set to ${value.label}")
    }

    /** The app came to the foreground. The away-window closes. */
    fun enterForeground() {
        _isForeground.value = true
        awaySince = now()
    }

    /** The app left the foreground. The away-window opens and the clock starts. */
    fun enterBackground() {
        _isForeground.value = false
        awaySince = now()
    }

    /** How long the app has been away. */
    fun awayMillis(): Long = (now() - awaySince).coerceAtLeast(0L)

    /**
     * Whether the lock policy is satisfied.
     *
     * Never satisfied while the app is in the foreground — the user is right there. Otherwise the
     * elapsed time away must reach the configured timeout. Immediate locking needs no grace period
     * at all, so it fires as soon as the app has been away.
     */
    fun shouldLock(): Boolean {
        if (_isForeground.value) return false
        val away = awayMillis()
        val threshold = _timeout.value.millis
        return if (threshold <= 0L) away >= 0L else away >= threshold
    }
}