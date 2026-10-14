package com.personal.base.platform.background

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.personal.base.PersonalBaseApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Lifecycle-aware background worker scheduled via WorkManager.
 * Runs deferrable tasks, plugin syncs, and periodic health checks with exponential backoff.
 */
class SyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext as? PersonalBaseApplication ?: return@withContext Result.failure()
        val logger = app.container.logger
        val pluginManager = app.container.pluginManager
        val capabilityRegistry = app.container.capabilityRegistry

        logger.i("SyncWorker", "Executing scheduled background sync work (run attempt $runAttemptCount)...")

        return@withContext try {
            // 1. Refresh capabilities status
            capabilityRegistry.refreshStatus()

            // 2. Run periodic sync for all active plugins
            pluginManager.runPeriodicSync()

            logger.i("SyncWorker", "Background sync completed successfully")
            Result.success()
        } catch (e: Exception) {
            logger.e("SyncWorker", "Background sync encountered error: ${e.message}")
            if (runAttemptCount < 3) {
                Result.retry()
            } else {
                Result.failure()
            }
        }
    }
}
