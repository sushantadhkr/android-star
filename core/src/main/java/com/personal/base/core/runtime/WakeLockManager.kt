package com.personal.base.core.runtime

import android.content.Context
import android.os.PowerManager
import com.personal.base.core.logging.PlatformLogger

/**
 * Holds the CPU awake while the platform must stay reachable.
 *
 * A foreground service keeps the *process* alive. It does not keep the *CPU* running. Once the
 * screen is off and the device goes idle, Android suspends the CPU and the app's threads stop
 * being scheduled — a listening socket still exists, so the phone looks alive from the outside, but
 * nothing accepts or answers. That is what a foreground service without a wake lock actually gets
 * you, and it looks exactly like the server dying.
 *
 * Holding a wake lock costs battery, which is why it is tied to the user's explicit "always-on
 * host" switch rather than being acquired unconditionally. Turning the host off releases it.
 */
class WakeLockManager(
    private val context: Context,
    private val logger: PlatformLogger
) {
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private var wakeLock: PowerManager.WakeLock? = null

    /**
     * Whether Android is exempting this app from battery optimisation.
     *
     * Without it the system dozes the app far more aggressively than a wake lock alone can
     * compensate for, so it is worth knowing and worth asking the user for.
     */
    val isIgnoringBatteryOptimizations: Boolean
        get() = powerManager.isIgnoringBatteryOptimizations(context.packageName)

    val isHeld: Boolean
        @Synchronized get() = wakeLock?.isHeld == true

    /**
     * Keeps the CPU awake.
     *
     * @param timeoutMs null holds it until [releaseWakeLock]. A finite timeout is a safety net for
     *   one-shot work; the always-on host must not have one, or the device would go back to sleep
     *   with the host still switched on and the problem would simply return later.
     */
    @Synchronized
    fun acquireWakeLock(tag: String = DEFAULT_TAG, timeoutMs: Long? = null) {
        try {
            if (wakeLock?.isHeld == true) return

            if (wakeLock == null) {
                wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, tag).apply {
                    setReferenceCounted(false)
                }
            }

            if (timeoutMs == null) {
                wakeLock?.acquire()
                logger.i("WakeLockManager", "Holding a partial wake lock until released ($tag)")
            } else {
                wakeLock?.acquire(timeoutMs)
                logger.i("WakeLockManager", "Acquired partial wake lock for ${timeoutMs}ms ($tag)")
            }
        } catch (e: Exception) {
            logger.e("WakeLockManager", "Failed to acquire wake lock: ${e.message}", e.message)
        }
    }

    @Synchronized
    fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                logger.i("WakeLockManager", "Released partial wake lock.")
            }
        } catch (e: Exception) {
            logger.w("WakeLockManager", "Error releasing wake lock: ${e.message}")
        }
    }

    companion object {
        const val DEFAULT_TAG = "AndroidStar:PlatformWakeLock"
    }
}