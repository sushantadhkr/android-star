package com.personal.base.core.runtime

import android.content.Context
import android.util.LruCache
import com.personal.base.core.logging.PlatformLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Two-tier cache consisting of high-speed memory LruCache and disk cache with TTL expiry.
 */
class CacheLayer(
    context: Context,
    private val logger: PlatformLogger,
    maxMemoryEntries: Int = 200
) {
    private val memoryCache = LruCache<String, Pair<String, Long>>(maxMemoryEntries)
    private val cacheDir = File(context.cacheDir, "platform_cache").apply { mkdirs() }

    fun putMemory(key: String, value: String, ttlMs: Long = 5 * 60 * 1000L) {
        val expiry = System.currentTimeMillis() + ttlMs
        memoryCache.put(key, Pair(value, expiry))
    }

    fun getMemory(key: String): String? {
        val entry = memoryCache.get(key) ?: return null
        if (System.currentTimeMillis() > entry.second) {
            memoryCache.remove(key)
            return null
        }
        return entry.first
    }

    suspend fun putDisk(key: String, data: ByteArray) = withContext(Dispatchers.IO) {
        try {
            val file = File(cacheDir, key.replace(Regex("[^a-zA-Z0-9._-]"), "_"))
            file.writeBytes(data)
        } catch (e: Exception) {
            logger.w("CacheLayer", "Error writing to disk cache: ${e.message}")
        }
    }

    suspend fun getDisk(key: String): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val file = File(cacheDir, key.replace(Regex("[^a-zA-Z0-9._-]"), "_"))
            if (file.exists()) file.readBytes() else null
        } catch (e: Exception) {
            null
        }
    }

    fun clearAll() {
        memoryCache.evictAll()
        cacheDir.deleteRecursively()
        cacheDir.mkdirs()
    }
}
