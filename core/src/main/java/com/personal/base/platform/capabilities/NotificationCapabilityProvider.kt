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

    /**
     * Posts a notification.
     *
     * [channelId] selects the destination. Platform callers leave it unset and land on a platform
     * channel. An App passes its own channel so its notifications stay under the Apps group, where
     * the user can silence that App without affecting the platform.
     *
     * [link] makes the notification actionable: tapping it opens that URL in whatever handles it,
     * instead of launching this app. Omit it and tapping opens the app as before.
     *
     * The channel must already exist or Android drops the notification, so callers posting to an
     * App channel must create it first.
     *
     * @return the id used, or null when the notification could not be posted.
     */
    fun postNotification(
        title: String,
        message: String,
        priorityHigh: Boolean = false,
        channelId: String = if (priorityHigh) CHANNEL_HIGH_PRIORITY else CHANNEL_INFO,
        notificationId: Int = (System.currentTimeMillis() % 100000).toInt(),
        link: String? = null
    ): Int? {
        if (!status.isUsable) {
            logger.w("NotificationProvider", "Cannot post notification: status is $status")
            return null
        }

        val intent = resolveTapIntent(link)

        val pendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(if (priorityHigh) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)

        // Only http/https are allowed through, so a notification can never be used to launch an
        // arbitrary intent on the device.
        if (link != null) builder.setSubText("Tap to open")

        val notification = builder.build()

        try {
            notificationManager?.notify(notificationId, notification)
            logger.i("NotificationProvider", "Posted notification: [$title] $message${if (link != null) " -> $link" else ""}")
            return notificationId
        } catch (e: SecurityException) {
            logger.e("NotificationProvider", "Security exception posting notification", e.message)
            return null
        }
    }

    /**
     * What tapping the notification does.
     *
     * With a link, it opens that URL via the normal system handler, so a browser or another app
     * deals with it. Only http and https are honoured — anything else falls back to launching this
     * app, so a link can never be used to fire an arbitrary intent at the device.
     *
     * Without a link, the app's own launcher activity opens, resolved reflectively so this
     * capability stays independent of the shell.
     */
    private fun resolveTapIntent(link: String?): Intent {
        val openable = link?.takeIf { runCatching { android.net.Uri.parse(it).scheme }.getOrNull() in
                setOf("http", "https") }

        if (openable != null) {
            return Intent(Intent.ACTION_VIEW, android.net.Uri.parse(openable))
                .apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        }

        if (link != null) {
            logger.w("NotificationProvider", "Ignoring non-http(s) link: $link")
        }

        val launchIntent = context.packageManager
            .getLaunchIntentForPackage(context.packageName)
            ?.apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP }
        return launchIntent ?: Intent(Intent.ACTION_VIEW)
    }
}
