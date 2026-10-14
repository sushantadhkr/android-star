package com.personal.base.platform.background

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.personal.base.MainActivity
import com.personal.base.PersonalBaseApplication
import com.personal.base.platform.capabilities.NotificationCapabilityProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground Service for active data sync or justified persistent tasks.
 * Follows Android 14+ / 16 foreground service restrictions (dataSync type).
 */
class PlatformForegroundService : Service() {

    private val serviceJob = Job()
    private val scope = CoroutineScope(Dispatchers.Default + serviceJob)

    companion object {
        const val ACTION_START = "com.personal.base.action.START_SERVICE"
        const val ACTION_STOP = "com.personal.base.action.STOP_SERVICE"
        const val NOTIFICATION_ID = 8801

        fun start(context: Context) {
            val intent = Intent(context, PlatformForegroundService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, PlatformForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as? PersonalBaseApplication
        val logger = app?.container?.logger

        when (intent?.action) {
            ACTION_STOP -> {
                logger?.i("ForegroundService", "Stopping service on explicit user action")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START, null -> {
                logger?.i("ForegroundService", "Starting foreground service with dataSync type")
                val notification = buildForegroundNotification()

                val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                } else {
                    0
                }

                try {
                    ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, serviceType)
                } catch (e: Exception) {
                    logger?.e("ForegroundService", "Failed to start foreground service", e.message)
                    stopSelf()
                    return START_NOT_STICKY
                }

                // Periodic health sync loop while service is active
                scope.launch {
                    var counter = 0
                    while (isActive) {
                        delay(30_000L) // 30 second cycle
                        counter++
                        logger?.d("ForegroundService", "Active foreground heartbeat tick #$counter")
                        app?.container?.pluginManager?.runPeriodicSync()
                    }
                }
            }
        }

        return START_STICKY
    }

    private fun buildForegroundNotification(): Notification {
        val stopIntent = Intent(this, PlatformForegroundService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val mainIntent = Intent(this, MainActivity::class.java)
        val mainPendingIntent = PendingIntent.getActivity(
            this,
            0,
            mainIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NotificationCapabilityProvider.CHANNEL_SYNC)
            .setContentTitle("Personal Base Platform Active")
            .setContentText("Platform core services running in background")
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentIntent(mainPendingIntent)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop Service", stopPendingIntent)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()
    }
}
