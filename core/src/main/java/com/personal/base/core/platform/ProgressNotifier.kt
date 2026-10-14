package com.personal.base.core.platform

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import com.personal.base.core.logging.PlatformLogger

/**
 * Progress notifications, in one place.
 *
 * Anything that takes a while — a download, a backup, a sync, an App's own job — wants the same
 * thing: a bar that moves, a line when it is done, and buttons to stop or retry. Building that
 * separately in each place produces five slightly different progress notifications, and it puts
 * notification policy in every caller.
 *
 * So it lives here, once. [MediaDownloadCapabilityProvider] uses it, and Apps reach it through
 * their `AppScope`, so a new long-running feature gets the behaviour rather than reinventing it.
 *
 * Indeterminate whenever the total is unknown. A bar pinned at 0% while a gigabyte moves is worse
 * than admitting the size is not known.
 */
class ProgressNotifier(
    private val context: Context,
    private val logger: PlatformLogger,
    private val channelId: String = CHANNEL
) {

    private val manager: NotificationManager? =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager

    init {
        createChannel()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        runCatching {
            manager?.createNotificationChannel(
                NotificationChannel(channelId, "Progress and downloads", NotificationManager.IMPORTANCE_LOW)
                    .apply {
                        description = "Work the phone is doing, with controls to stop or retry it"
                        setShowBadge(false)
                    }
            )
        }
    }

    /** An ongoing bar. [percent] is ignored when [totalBytes] is unknown. */
    fun progress(
        id: String,
        title: String,
        detail: String,
        percent: Int = 0,
        totalBytes: Long = 0,
        buttons: List<NotificationButton> = emptyList()
    ) {
        val known = totalBytes > 0
        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(detail)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, percent.coerceIn(0, 100), !known)

        buttons.forEach { builder.addAction(buildAction(it, id)) }
        post(id, builder)
    }

    /** Finished. Left in the shade so the result is still readable a moment later. */
    fun complete(id: String, title: String, detail: String, buttons: List<NotificationButton> = emptyList()) {
        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(detail)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setOngoing(false)

        buttons.forEach { builder.addAction(buildAction(it, id)) }
        post(id, builder)
    }

    /** Failed, with the reason and a way to try again. */
    fun failed(id: String, title: String, reason: String, retry: NotificationButton? = null) {
        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(title)
            .setContentText(reason)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)

        retry?.let { builder.addAction(buildAction(it, id)) }
        post(id, builder)
    }

    fun cancel(id: String) {
        runCatching { manager?.cancel(id.hashCode()) }
    }

    /**
     * A button that delivers a broadcast back into the platform.
     *
     * The same mechanism the HTTP notification API uses, so a progress button obeys the capability
     * gate exactly like everything else — switching a capability off stops its buttons working,
     * which is the rule the whole API rests on.
     */
    private fun buildAction(button: NotificationButton, notificationId: String): NotificationCompat.Action {
        val intent = android.content.Intent(RECEIVER_CLASS).apply {
            action = NotificationActionContract.INTENT_ACTION
            putExtra(NotificationActionContract.EXTRA_BUTTON_ID, button.id)
            putExtra(NotificationActionContract.EXTRA_LABEL, button.label)
            putExtra(NotificationActionContract.EXTRA_NOTIFICATION_ID, notificationId.hashCode())
            putExtra(NotificationActionContract.EXTRA_DISMISS, button.dismissesNotification)
            button.invoke?.let { putExtra(NotificationActionContract.EXTRA_INVOKE, it) }
            if (button.params.isNotEmpty()) {
                putExtra(
                    NotificationActionContract.EXTRA_PARAMS,
                    kotlinx.serialization.json.buildJsonObject {
                        button.params.forEach { (k, v) ->
                            put(k, kotlinx.serialization.json.JsonPrimitive(v))
                        }
                    }.toString()
                )
            }
        }

        val pending = PendingIntent.getBroadcast(
            context,
            notificationId.hashCode() * 31 + button.id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Action.Builder(iconFor(button.icon), button.label, pending).build()
    }

    private fun iconFor(name: String?): Int = when (name?.lowercase()) {
        NotificationActionContract.ICON_DELETE -> android.R.drawable.ic_menu_close_clear_cancel
        NotificationActionContract.ICON_REFRESH -> android.R.drawable.ic_menu_rotate
        NotificationActionContract.ICON_CHECK -> android.R.drawable.ic_menu_send
        else -> android.R.drawable.ic_menu_more
    }

    private fun post(id: String, builder: NotificationCompat.Builder) {
        if (manager == null) {
            logger.w("ProgressNotifier", "No notification service; nothing shown")
            return
        }
        // Posting is a no-op without POST_NOTIFICATIONS on Android 13+. That is the platform's
        // choice, not a failure worth logging as one on every tick.
        runCatching { manager?.notify(id.hashCode(), builder.build()) }
    }

    companion object {
        const val CHANNEL = "personal_base_progress"

        /**
         * The receiver that handles button presses.
         *
         * Core cannot name a class in the shell, so the shell sets this once at startup.
         */
        @Volatile
        var RECEIVER_CLASS: String? = null
    }
}