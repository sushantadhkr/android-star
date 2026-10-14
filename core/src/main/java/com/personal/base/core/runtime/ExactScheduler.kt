package com.personal.base.core.runtime

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import com.personal.base.core.logging.PlatformLogger
import java.util.concurrent.TimeUnit

/**
 * Manages exact hardware alarms with AlarmManager and periodic background execution with WorkManager.
 */
class ExactScheduler(
    private val context: Context,
    private val logger: PlatformLogger
) {
    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    fun canScheduleExactAlarms(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager.canScheduleExactAlarms()
        } else true
    }

    fun scheduleExactAlarm(triggerAtMillis: Long, pendingIntent: PendingIntent) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pendingIntent
                )
            } else {
                alarmManager.setExact(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pendingIntent
                )
            }
            logger.i("ExactScheduler", "Scheduled exact alarm at $triggerAtMillis")
        } catch (e: Exception) {
            logger.e("ExactScheduler", "Failed to schedule exact alarm: ${e.message}", e.message)
        }
    }

    fun schedulePeriodicWork(intervalMinutes: Long = 15) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        // Resolved by name so the core library never depends on a class that lives in the shell.
        val workerClass = Class.forName(SYNC_WORKER_CLASS_NAME).asSubclass(ListenableWorker::class.java)
        val request = PeriodicWorkRequest.Builder(workerClass, intervalMinutes, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "PlatformPeriodicSync",
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
        logger.i("ExactScheduler", "Enqueued periodic WorkManager sync task ($intervalMinutes mins).")
    }

    companion object {
        /**
         * Resolved by name so the core library never depends on a class that lives in the shell.
         * WorkManager instantiates the worker reflectively at runtime.
         */
        const val SYNC_WORKER_CLASS_NAME = "com.personal.base.platform.background.SyncWorker"
    }
}
