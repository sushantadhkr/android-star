package com.personal.base.platform.capabilities

import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat
import com.personal.base.core.capability.CapabilityProvider
import com.personal.base.core.capability.CapabilityStatus
import com.personal.base.core.capability.CapabilityType
import com.personal.base.core.logging.PlatformLogger
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** One notification from another app, as this platform can see it. */
data class MirroredNotification(
    val key: String,
    val packageName: String,
    val title: String,
    val text: String,
    val postTime: Long,
    val isOngoing: Boolean
)

class PlatformNotificationListenerService : NotificationListenerService() {
    companion object {
        var activeInstance: PlatformNotificationListenerService? = null
        val incomingNotifications = MutableSharedFlow<MirroredNotification>(extraBufferCapacity = 64)

        /**
         * Whether the user has granted notification access.
         *
         * Read from the platform rather than from `activeInstance`, so onboarding can tell the
         * truth before the service has ever bound. Same reason as the accessibility service: two
         * questions, one answer.
         */
        fun isGrantedFor(context: Context): Boolean =
            NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        activeInstance = this
        android.util.Log.i("PlatformNotifications", "Notification access granted; listening")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return
        val extras = sbn.notification?.extras
        val mirrored = MirroredNotification(
            key = sbn.key ?: "",
            packageName = sbn.packageName ?: "",
            title = extras?.getCharSequence("android.title")?.toString() ?: "",
            text = extras?.getCharSequence("android.text")?.toString() ?: "",
            postTime = sbn.postTime,
            isOngoing = sbn.isOngoing
        )
        incomingNotifications.tryEmit(mirrored)
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        if (activeInstance == this) activeInstance = null
        android.util.Log.i("PlatformNotifications", "Notification access lost; no longer listening")
    }

    override fun onDestroy() {
        super.onDestroy()
        if (activeInstance == this) activeInstance = null
    }
}

/**
 * Reading notifications other apps post.
 *
 * This is the most privacy-sensitive capability on the platform: it sees messages from every app on
 * the phone. It is therefore off until a human grants it in Settings, it is never requested from a
 * dialog, and `list` reports only what is genuinely visible right now rather than a cache that
 * might be minutes stale.
 */
class NotificationListenerCapabilityProvider(
    private val context: Context,
    private val logger: PlatformLogger
) : CapabilityProvider {

    override val type: CapabilityType = CapabilityType.NOTIFICATION_LISTENER

    val incomingNotifications: SharedFlow<MirroredNotification> =
        PlatformNotificationListenerService.incomingNotifications.asSharedFlow()

    /** Whether the user has granted access, which is also whether the service can answer at all. */
    val isGranted: Boolean get() = PlatformNotificationListenerService.isGrantedFor(context)

    val isConnected: Boolean get() = PlatformNotificationListenerService.activeInstance != null

    val grantAction: String
        get() = "android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"

    /**
     * The notifications on screen right now.
     *
     * Read live from the system rather than accumulated locally, so an answer is never a stale
     * mirror. Returns null when access is not granted, which the caller must treat as a refusal
     * rather than as "there are no notifications".
     */
    fun activeNotifications(): List<MirroredNotification>? {
        val service = PlatformNotificationListenerService.activeInstance ?: return null
        val posted = runCatching { service.activeNotifications }.getOrNull() ?: return null
        return posted.mapNotNull { sbn ->
            val extras = sbn.notification?.extras
            MirroredNotification(
                key = sbn.key ?: "",
                packageName = sbn.packageName ?: "",
                title = extras?.getCharSequence("android.title")?.toString() ?: "",
                text = extras?.getCharSequence("android.text")?.toString() ?: "",
                postTime = sbn.postTime,
                isOngoing = sbn.isOngoing
            )
        }.sortedByDescending { it.postTime }
    }

    /**
     * Dismisses one notification.
     *
     * `cancelNotification` has no return value, so "did it work" cannot come from the call. It is
     * answered by looking afterwards: a dismissal that did not happen would show the notification
     * still there, and reporting success for that would be a lie a caller could easily catch.
     */
    fun dismiss(key: String): Boolean {
        val service = PlatformNotificationListenerService.activeInstance ?: return false
        val wasPresent = activeNotifications()?.any { it.key == key } ?: false
        if (!wasPresent) return false
        runCatching { service.cancelNotification(key) }

        // The system removes it asynchronously, so give it a moment before declaring failure.
        var gone = false
        for (attempt in 0 until 10) {
            if (activeNotifications()?.none { n -> n.key == key } == true) {
                gone = true
                break
            }
            Thread.sleep(50)
        }
        if (gone) logger.i("NotificationListener", "Dismissed $key")
        return gone
    }

    override fun checkStatus(): CapabilityStatus = when {
        !isGranted -> CapabilityStatus.PermissionRequired(listOf("android.permission.BIND_NOTIFICATION_LISTENER_SERVICE"))
        !isConnected -> CapabilityStatus.Partial(
            whatWorks = "Access is granted",
            whatDoesNot = "The service has not finished connecting"
        )
        else -> CapabilityStatus.Available
    }

    override fun release() {}
}