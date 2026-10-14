package com.personal.base.platform.capabilities

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.personal.base.core.capability.CapabilityProvider
import com.personal.base.core.capability.CapabilityStatus
import com.personal.base.core.capability.CapabilityType
import com.personal.base.core.logging.PlatformLogger
import com.personal.base.platform.permissions.PlatformPermissionManager

class NotificationCapabilityProvider(
    private val context: Context,
    private val permissionManager: PlatformPermissionManager,
    private val logger: PlatformLogger
) : CapabilityProvider {

    override val type: CapabilityType = CapabilityType.NOTIFICATIONS

    private val notificationManager: NotificationManager? =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager

    companion object {
        const val CHANNEL_HIGH_PRIORITY = "personal_base_high_priority"
        const val CHANNEL_SYNC = "personal_base_sync"
        const val CHANNEL_INFO = "personal_base_info"
    }

    override val status: CapabilityStatus
        get() {
            if (notificationManager == null) {
                return CapabilityStatus.Unavailable("NotificationManager service not found")
            }
            if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
                val missing = permissionManager.getMissingPermissions(CapabilityType.NOTIFICATIONS)
                return if (missing.isNotEmpty()) {
                    CapabilityStatus.PermissionRequired(missing)
                } else {
                    CapabilityStatus.DisabledByUser("Notifications disabled in device settings")
                }
            }
            return CapabilityStatus.Available
        }

    override fun initialize() {
        createNotificationChannels()
        logger.i("NotificationProvider", "Notification channels created and initialized")
    }

    override fun release() {}

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val highChannel = NotificationChannel(
                CHANNEL_HIGH_PRIORITY,
                "Base Platform Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Critical command responses and security notifications"
            }

            val syncChannel = NotificationChannel(
                CHANNEL_SYNC,
                "Background Platform Sync",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Foreground service status and sync execution"
            }

            val infoChannel = NotificationChannel(
                CHANNEL_INFO,
                "Platform Telemetry",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "General notifications from platform plugins"
            }

            notificationManager?.createNotificationChannels(listOf(highChannel, syncChannel, infoChannel))
        }
    }

    fun postNotification(
        title: String,
        message: String,
        priorityHigh: Boolean = false,
        notificationId: Int = (System.currentTimeMillis() % 100000).toInt()
    ) {
        if (!status.isUsable) {
            logger.w("NotificationProvider", "Cannot post notification: status is $status")
            return
        }

        val channelId = if (priorityHigh) CHANNEL_HIGH_PRIORITY else CHANNEL_INFO
        // Resolve the launcher activity rather than referencing a specific Activity class,
        // so this capability stays independent of the shell and any consuming app.
        val launchIntent = context.packageManager
            .getLaunchIntentForPackage(context.packageName)
            ?.apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP }
        val intent = launchIntent ?: Intent(Intent.ACTION_VIEW)

        val pendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(if (priorityHigh) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        try {
            notificationManager?.notify(notificationId, notification)
            logger.i("NotificationProvider", "Posted notification: [$title] $message")
        } catch (e: SecurityException) {
            logger.e("NotificationProvider", "Security exception posting notification", e.message)
        }
    }
}
