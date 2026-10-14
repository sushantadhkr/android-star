package com.personal.base.core.runtime

import android.content.Context
import com.personal.base.core.logging.PlatformLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Scheduled cleanup of stale logs, temporary cache files, and completed chunk transfers.
 */
class RetentionCleanup(
    private val context: Context,
    private val logger: PlatformLogger
) {
    suspend fun performCleanup(maxAgeMillis: Long = 7 * 24 * 60 * 60 * 1000L) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        var deletedFiles = 0
        var reclaimedBytes = 0L

        // Clean cache directory
        context.cacheDir.listFiles()?.forEach { file ->
            if (now - file.lastModified() > maxAgeMillis) {
                reclaimedBytes += file.length()
                if (file.deleteRecursively()) deletedFiles++
            }
        }

        logger.i("RetentionCleanup", "Pruned $deletedFiles stale cache items, reclaimed ${reclaimedBytes / 1024} KB.")
    }
}
