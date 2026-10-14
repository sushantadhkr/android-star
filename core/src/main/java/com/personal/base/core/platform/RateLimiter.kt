package com.personal.base.core.platform

import com.personal.base.core.logging.PlatformLogger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Sliding-window and token bucket rate limiter throttling commands per-peer and per-command action.
 */
class RateLimiter(
    private val logger: PlatformLogger,
    private val defaultMaxRequestsPerMinute: Int = 60
) {
    // Key: "$peerId:$action" -> Pair(windowStartTimestamp, counter)
    private val windows = ConcurrentHashMap<String, Pair<Long, AtomicInteger>>()

    fun acquire(peerId: String, action: String, maxPerMinute: Int = defaultMaxRequestsPerMinute): Boolean {
        val key = "$peerId:$action"
        val now = System.currentTimeMillis()

        val entry = windows.compute(key) { _, current ->
            if (current == null || now - current.first > 60_000L) {
                Pair(now, AtomicInteger(1))
            } else {
                current.second.incrementAndGet()
                current
            }
        }!!

        val count = entry.second.get()
        return if (count <= maxPerMinute) {
            true
        } else {
            logger.w("RateLimiter", "Rate limit exceeded for $key: $count / $maxPerMinute req/min")
            false
        }
    }
}
