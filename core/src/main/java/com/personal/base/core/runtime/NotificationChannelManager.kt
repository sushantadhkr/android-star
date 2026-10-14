package com.personal.base.core.runtime

import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import com.personal.base.core.logging.PlatformLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * Who owns a notification.
 *
 * The platform and its Apps share one notification service but are never mixed in the user's view:
 * platform alerts describe the system itself, while App alerts belong to an individual App and are
 * controlled separately. A user who silences one App must not silence the platform, and vice versa.
 */
enum class NotificationGroup(
    val id: String,
    val displayName: String,
    val description: String
) {
    PLATFORM(
        id = "star_group_platform",
        displayName = "Platform",
        description = "Alerts raised by the platform itself"
    ),
    APPS(
        id = "star_group_apps",
        displayName = "Apps",
        description = "Alerts raised by individual Apps. Silence them per App."
    )
}

/**
 * A notification channel the platform manages, and which group it belongs to.
 */
data class ManagedNotificationChannel(
    val channelId: String,
    val channelName: String,
    val channelDescription: String,
    val group: NotificationGroup,
    val appId: String = NotificationChannelManager.PLATFORM_APP_ID,
    val appName: String = "Android Star",
    val isEnabled: Boolean = true
) {
    val isPlatformOwned: Boolean get() = group == NotificationGroup.PLATFORM
}

/**
 * Owns every notification channel on the device.
 *
 * There is a single notification service, shared by everyone. Separation happens at the *channel*
 * level: the platform owns its own channels under the Platform group, and each App gets its own
 * channel under the Apps group. An App can therefore be muted without touching the platform, which
 * is the same rule the App lifecycle already follows — one instance per App, shared capabilities.
 */
class NotificationChannelManager(
    private val context: Context,
    private val logger: PlatformLogger
) {

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private val prefs: SharedPreferences =
        context.getSharedPreferences("android_star_notification_channels", Context.MODE_PRIVATE)

    private val channels = ConcurrentHashMap<String, ManagedNotificationChannel>()
    private val _channelsFlow = MutableStateFlow<List<ManagedNotificationChannel>>(emptyList())
    val channelsFlow: StateFlow<List<ManagedNotificationChannel>> = _channelsFlow.asStateFlow()

    private val disabledChannels = ConcurrentHashMap.newKeySet<String>()
    private val disabledApps = ConcurrentHashMap.newKeySet<String>()

    init {
        prefs.getStringSet("disabled_channels", emptySet())?.forEach { disabledChannels.add(it) }
        prefs.getStringSet("disabled_apps", emptySet())?.forEach { disabledApps.add(it) }
        setupGroupsAndPlatformChannels()
    }

    /**
     * Platform-owned channels. These describe the platform itself, so they are fixed rather than
     * derived from any App.
     */
    private fun setupGroupsAndPlatformChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        NotificationGroup.entries.forEach { group ->
            notificationManager.createNotificationChannelGroup(
                NotificationChannelGroup(group.id, group.displayName)
            )
        }

        registerChannel(
            channelId = CHANNEL_FOREGROUND,
            channelName = "Service Status",
            description = "Shows that platform background services are running",
            group = NotificationGroup.PLATFORM,
            importance = NotificationManager.IMPORTANCE_LOW
        )

        registerChannel(
            channelId = CHANNEL_SECURITY,
            channelName = "Security & Lock",
            description = "Device lock state, failed unlock attempts and access warnings",
            group = NotificationGroup.PLATFORM,
            importance = NotificationManager.IMPORTANCE_HIGH
        )

        registerChannel(
            channelId = CHANNEL_AUTOMATION,
            channelName = "Alarms & Schedules",
            description = "Alarms, timers and scheduled background work",
            group = NotificationGroup.PLATFORM,
            importance = NotificationManager.IMPORTANCE_DEFAULT
        )
    }

    private fun registerChannel(
        channelId: String,
        channelName: String,
        description: String,
        group: NotificationGroup,
        importance: Int,
        appId: String = PLATFORM_APP_ID,
        appName: String = "Android Star"
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, channelName, importance).apply {
                this.description = description
                this.group = group.id
            }
            notificationManager.createNotificationChannel(channel)
        }

        channels[channelId] = ManagedNotificationChannel(
            channelId = channelId,
            channelName = channelName,
            channelDescription = description,
            group = group,
            appId = appId,
            appName = appName,
            isEnabled = !disabledChannels.contains(channelId) && !disabledApps.contains(appId)
        )
        publish()
    }

    /**
     * Creates or updates the notification channel belonging to an App.
     *
     * One channel per App, created on demand. Repeated calls update the display name without
     * disturbing the user's chosen importance, because Android preserves channel settings once a
     * channel exists.
     */
    fun ensureAppChannel(
        appId: String,
        appName: String,
        importance: Int = NotificationManager.IMPORTANCE_DEFAULT
    ): String {
        val channelId = appChannelId(appId)
        if (channels[channelId] != null) return channelId

        registerChannel(
            channelId = channelId,
            channelName = appName,
            description = "Notifications from $appName",
            group = NotificationGroup.APPS,
            importance = importance,
            appId = appId,
            appName = appName
        )
        logger.i("NotificationChannelManager", "Created channel '$channelId' for app '$appName'")
        return channelId
    }

    /**
     * Drops the channels of Apps that no longer exist.
     *
     * Called with the current App list so the Apps group never accumulates channels for Apps the
     * user has deleted.
     */
    fun syncApps(installed: List<Pair<String, String>>) {
        val live = installed.associate { it.first to it.second }

        channels.values
            .filter { !it.isPlatformOwned && it.appId !in live }
            .forEach { stale -> channels.remove(stale.channelId) }

        live.forEach { (appId, appName) ->
            val channelId = appChannelId(appId)
            val existing = channels[channelId]
            if (existing == null) {
                ensureAppChannel(appId, appName)
            } else if (existing.appName != appName) {
                // App renamed — keep the channel, refresh the label.
                registerChannel(
                    channelId = channelId,
                    channelName = appName,
                    description = "Notifications from $appName",
                    group = NotificationGroup.APPS,
                    importance = NotificationManager.IMPORTANCE_DEFAULT,
                    appId = appId,
                    appName = appName
                )
            }
        }
        publish()
    }

    /** Removes every channel belonging to Apps that are no longer installed. */
    fun isNotificationAllowed(channelId: String, appId: String = PLATFORM_APP_ID): Boolean {
        if (disabledApps.contains(appId)) return false
        if (disabledChannels.contains(channelId)) return false
        return true
    }

    /** Toggles a single channel. */
    fun setChannelEnabled(channelId: String, enabled: Boolean) {
        if (enabled) disabledChannels.remove(channelId) else disabledChannels.add(channelId)
        prefs.edit().putStringSet("disabled_channels", disabledChannels).apply()

        channels[channelId]?.let { channels[channelId] = it.copy(isEnabled = enabled) }
        publish()
        logger.i("NotificationChannelManager", "Channel '$channelId' enabled = $enabled")
    }

    /** Toggles every notification from one App, leaving the platform untouched. */
    fun setAppNotificationsEnabled(appId: String, enabled: Boolean) {
        if (appId == PLATFORM_APP_ID) return
        if (enabled) disabledApps.remove(appId) else disabledApps.add(appId)
        prefs.edit().putStringSet("disabled_apps", disabledApps).apply()

        channels.values.filter { it.appId == appId }.forEach { ch ->
            channels[ch.channelId] =
                ch.copy(isEnabled = enabled && !disabledChannels.contains(ch.channelId))
        }
        publish()
        logger.i("NotificationChannelManager", "App notifications for '$appId' enabled = $enabled")
    }

    fun isAppEnabled(appId: String): Boolean = !disabledApps.contains(appId)

    fun getChannelsByGroup(group: NotificationGroup): List<ManagedNotificationChannel> =
        channels.values.filter { it.group == group }

    private fun publish() {
        _channelsFlow.value = channels.values.sortedWith(
            compareBy<ManagedNotificationChannel> { it.group.ordinal }.thenBy { it.channelName }
        )
    }

    private fun appChannelId(appId: String) = "app_$appId"

    companion object {
        const val PLATFORM_APP_ID = "core_platform"

        const val CHANNEL_FOREGROUND = "star_foreground_service"
        const val CHANNEL_SECURITY = "star_security_alerts"
        const val CHANNEL_AUTOMATION = "star_automations"
    }
}