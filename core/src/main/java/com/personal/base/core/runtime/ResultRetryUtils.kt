package com.personal.base.core.runtime

import kotlinx.coroutines.delay
import kotlin.math.min
import kotlin.random.Random

/**
 * Standardized result retry helpers with exponential backoff and randomized jitter.
 */
object ResultRetryUtils {

    suspend fun <T> retryWithBackoff(
        maxAttempts: Int = 3,
        initialDelayMs: Long = 500L,
        maxDelayMs: Long = 5000L,
        factor: Double = 2.0,
        block: suspend (attempt: Int) -> T
    ): Result<T> {
        var currentDelay = initialDelayMs
        var lastThrowable: Throwable? = null

        for (attempt in 1..maxAttempts) {
            try {
                return Result.success(block(attempt))
            } catch (t: Throwable) {
                lastThrowable = t
                if (attempt == maxAttempts) break
                val jitter = Random.nextLong(0, (currentDelay * 0.2).toLong().coerceAtLeast(1L))
                delay(currentDelay + jitter)
                currentDelay = min((currentDelay * factor).toLong(), maxDelayMs)
            }
        }
        return Result.failure(lastThrowable ?: Exception("Operation failed after $maxAttempts attempts"))
    }
}
