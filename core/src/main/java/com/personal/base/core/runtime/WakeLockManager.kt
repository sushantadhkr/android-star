package com.personal.base.core.runtime

import android.content.Context
import android.os.PowerManager
import com.personal.base.core.logging.PlatformLogger

/**
 * Manages PowerManager WakeLocks with safety timeouts and handles battery optimization exemptions.
 */
class WakeLockManager(
    private val context: Context,
    private val logger: PlatformLogger
) {
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private var wakeLock: PowerManager.WakeLock? = null

    val isIgnoringBatteryOptimizations: Boolean
        get() = powerManager.isIgnoringBatteryOptimizations(context.packageName)

    @Synchronized
    fun acquireWakeLock(tag: String = "AndroidStar:PlatformWakeLock", timeoutMs: Long = 10 * 60 * 1000L) {
        try {
            if (wakeLock == null) {
                wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, tag).apply {
                    setReferenceCounted(false)
                }
            }
            if (wakeLock?.isHeld == false) {
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
}
