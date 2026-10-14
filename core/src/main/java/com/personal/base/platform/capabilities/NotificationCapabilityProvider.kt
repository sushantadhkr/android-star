package com.personal.base.platform.capabilities

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import com.personal.base.core.capability.CapabilityProvider
import com.personal.base.core.capability.CapabilityStatus
import com.personal.base.core.capability.CapabilityType
import com.personal.base.core.logging.PlatformLogger
import com.personal.base.core.platform.NotificationActionContract
import com.personal.base.core.platform.NotificationButton
import com.personal.base.core.platform.NotificationInput
import com.personal.base.core.platform.PostedNotification
import com.personal.base.platform.permissions.PlatformPermissionManager

/**
 * @param actionReceiver the fully qualified class name of the receiver that handles notification
 *   buttons. Core cannot name a class that lives in the shell, so the shell supplies it. When it is
 *   null, buttons cannot be delivered and any that were asked for are reported as dropped rather
 *   than silently rendered as dead ends.
 */
class NotificationCapabilityProvider(
    private val context: Context,
    private val permissionManager: PlatformPermissionManager,
    private val logger: PlatformLogger,
    private val actionReceiver: String? = null
) : CapabilityProvider {

    override val type: CapabilityType = CapabilityType.NOTIFICATIONS

    private val notificationManager: NotificationManager? =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager

    companion object {
        const val CHANNEL_HIGH_PRIORITY = "personal_base_high_priority"
        const val CHANNEL_SYNC = "personal_base_sync"
        const val CHANNEL_INFO = "personal_base_info"

        /** Android renders at most three actions; the rest are dropped by the system. */
        const val MAX_ACTIONS = 3

        /**
         * Icons the platform can draw on a button.
         *
         * Framework drawables rather than the shell's, so an App can put a button on a notification
         * without shipping an asset for it.
         */
        private fun iconFor(name: String?): Int = when (name?.lowercase()) {
            NotificationActionContract.ICON_CHECK -> android.R.drawable.ic_menu_send
            NotificationActionContract.ICON_ALARM -> android.R.drawable.ic_menu_recent_history
            NotificationActionContract.ICON_REPLY -> android.R.drawable.ic_menu_edit
            NotificationActionContract.ICON_DELETE -> android.R.drawable.ic_menu_delete
            NotificationActionContract.ICON_REFRESH -> android.R.drawable.ic_menu_rotate
            NotificationActionContract.ICON_OPEN -> android.R.drawable.ic_menu_view
            else -> android.R.drawable.ic_menu_more
        }
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
     * [buttons] add inline actions. Android shows at most [MAX_ACTIONS]; any beyond that, or any at
     * all when no receiver is wired, come back in [PostedNotification.buttonsDropped] so the caller
     * can see what actually appeared rather than assume.
     *
     * The channel must already exist or Android drops the notification, so callers posting to an
     * App channel must create it first.
     *
     * @return what was posted, or null when the notification could not be posted at all.
     */
    fun postNotification(
        title: String,
        message: String,
        priorityHigh: Boolean = false,
        channelId: String = if (priorityHigh) CHANNEL_HIGH_PRIORITY else CHANNEL_INFO,
        notificationId: Int = (System.currentTimeMillis() % 100000).toInt(),
        link: String? = null,
        buttons: List<NotificationButton> = emptyList()
    ): PostedNotification? {
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

        // A link is only honoured over http/https, so a notification can never be used to fire an
        // arbitrary intent at the device.
        if (link != null) builder.setSubText("Tap to open")

        val dropped = mutableListOf<String>()
        val receiver = actionReceiver
        val attachable: List<NotificationButton>

        if (receiver == null) {
            // A button with nowhere to go would look real and do nothing, so it is refused and the
            // caller is told, rather than rendered as a dead end.
            if (buttons.isNotEmpty()) {
                logger.w(
                    "NotificationProvider",
                    "No action receiver wired; dropped ${buttons.size} button(s) on notification $notificationId"
                )
                dropped += buttons.map { it.id }
            }
            attachable = emptyList()
        } else {
            dropped += buttons.drop(MAX_ACTIONS).map { it.id }
            if (dropped.isNotEmpty()) {
                logger.w(
                    "NotificationProvider",
                    "Android shows at most $MAX_ACTIONS actions; dropped ${dropped.joinToString()}"
                )
            }
            attachable = buttons.take(MAX_ACTIONS)
            for ((index, button) in attachable.withIndex()) {
                builder.addAction(buildAction(receiver, button, title, notificationId, index))
            }
        }

        val notification = builder.build()

        return try {
            notificationManager?.notify(notificationId, notification)
            logger.i(
                "NotificationProvider",
                "Posted notification: [$title] $message${if (link != null) " -> $link" else ""}" +
                    "${if (attachable.isNotEmpty()) " with ${attachable.size} action(s)" else ""}"
            )
            PostedNotification(notificationId, attachable.size, dropped)
        } catch (e: SecurityException) {
            logger.e("NotificationProvider", "Security exception posting notification", e.message)
            null
        }
    }

    /**
     * Turns a button into a real notification action.
     *
     * The press is delivered as a broadcast to [receiver] rather than by launching anything, so
     * Android wakes the process and the action runs whether or not the app was running.
     *
     * Each button gets its own request code. Android matches a [PendingIntent] on action and target
     * and treats extras as overwritable, so buttons sharing a code would all fire whichever intent
     * was written last.
     */
    private fun buildAction(
        receiver: String,
        button: NotificationButton,
        notificationTitle: String,
        notificationId: Int,
        index: Int
    ): NotificationCompat.Action {
        button.icon?.takeIf { it.lowercase() !in NotificationActionContract.ICONS }?.let {
            logger.w("NotificationProvider", "Unknown button icon '$it'; using a neutral one")
        }

        val intent = Intent(receiver).apply {
            action = NotificationActionContract.INTENT_ACTION
            putExtra(NotificationActionContract.EXTRA_BUTTON_ID, button.id)
            putExtra(NotificationActionContract.EXTRA_LABEL, button.label)
            putExtra(NotificationActionContract.EXTRA_NOTIFICATION_ID, notificationId)
            putExtra(NotificationActionContract.EXTRA_NOTIFICATION_TITLE, notificationTitle)
            putExtra(NotificationActionContract.EXTRA_DISMISS, button.dismissesNotification)
            button.invoke?.let { putExtra(NotificationActionContract.EXTRA_INVOKE, it) }
            button.input?.let { putExtra(NotificationActionContract.EXTRA_INPUT_KEY, it.key) }
            if (button.params.isNotEmpty()) {
                putExtra(
                    NotificationActionContract.EXTRA_PARAMS,
                    kotlinx.serialization.json.buildJsonObject {
                        button.params.forEach { (k, v) -> put(k, kotlinx.serialization.json.JsonPrimitive(v)) }
                    }.toString()
                )
            }
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            notificationId * 10 + index,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val action = NotificationCompat.Action.Builder(
            iconFor(button.icon),
            button.label,
            pendingIntent
        )

        button.input?.let { action.addRemoteInput(buildRemoteInput(it)) }

        return action.build()
    }

    private fun buildRemoteInput(input: NotificationInput): RemoteInput =
        RemoteInput.Builder(input.key)
            .setLabel(input.hint)
            .build()

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
