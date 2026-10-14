package com.personal.base.core.platform

import com.personal.base.core.logging.PlatformLogger
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * Validates unique nonce values and enforces timestamp sliding windows to prevent replay attacks.
 */
class ReplayProtection(
    private val logger: PlatformLogger,
    private val maxTimeDriftMs: Long = 120_000L // 2 mins
) {
    // Nonce -> Expiration timestamp
    private val seenNonces = ConcurrentHashMap<String, Long>()

    fun validateAndRecordNonce(nonce: String, timestamp: Long): Boolean {
        val now = System.currentTimeMillis()
        if (abs(now - timestamp) > maxTimeDriftMs) {
            logger.w("ReplayProtection", "Rejected command with expired timestamp drift: ${abs(now - timestamp)}ms")
            return false
        }

        pruneOldNonces(now)
        val alreadySeen = seenNonces.putIfAbsent(nonce, now + maxTimeDriftMs) != null
        if (alreadySeen) {
            logger.w("ReplayProtection", "Replay attack detected! Reused nonce: $nonce")
            return false
        }
        return true
    }

    private fun pruneOldNonces(now: Long) {
        val it = seenNonces.entries.iterator()
        while (it.hasNext()) {
            if (now > it.next().value) {
                it.remove()
            }
        }
    }
}
