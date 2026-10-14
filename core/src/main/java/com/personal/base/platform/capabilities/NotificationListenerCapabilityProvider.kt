package com.personal.base.platform.capabilities

import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.personal.base.core.capability.CapabilityProvider
import com.personal.base.core.capability.CapabilityStatus
import com.personal.base.core.capability.CapabilityType
import com.personal.base.core.logging.PlatformLogger
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

data class MirroredNotification(
    val packageName: String,
    val title: String,
    val text: String,
    val postTime: Long = System.currentTimeMillis()
)

class PlatformNotificationListenerService : NotificationListenerService() {
    companion object {
        var activeInstance: PlatformNotificationListenerService? = null
        val incomingNotifications = MutableSharedFlow<MirroredNotification>(extraBufferCapacity = 64)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        activeInstance = this
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        val extras = sbn?.notification?.extras
        val title = extras?.getCharSequence("android.title")?.toString() ?: ""
        val text = extras?.getCharSequence("android.text")?.toString() ?: ""
        val pkg = sbn?.packageName ?: ""

        incomingNotifications.tryEmit(MirroredNotification(pkg, title, text, sbn?.postTime ?: System.currentTimeMillis()))
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (activeInstance == this) activeInstance = null
    }
}

/**
 * 32. NotificationListenerCapabilityProvider: mirror, inspect, and act on notifications from other apps.
 */
class NotificationListenerCapabilityProvider(
    private val context: Context,
    private val logger: PlatformLogger
) : CapabilityProvider {

    override val type: CapabilityType = CapabilityType.NOTIFICATION_LISTENER

    val incomingNotifications: SharedFlow<MirroredNotification> =
        PlatformNotificationListenerService.incomingNotifications.asSharedFlow()

    val isConnected: Boolean
        get() = PlatformNotificationListenerService.activeInstance != null

    override fun checkStatus(): CapabilityStatus {
        return if (isConnected) CapabilityStatus.Available
        else CapabilityStatus.Unavailable("Notification Listener permission not granted in system settings")
    }

    override fun release() {}
}
