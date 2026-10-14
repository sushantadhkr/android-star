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

enum class NotificationCategory(
    val id: String,
    val displayName: String,
    val description: String,
    val defaultImportance: Int
) {
    SYSTEM_FOREGROUND(
        id = "cat_system_foreground",
        displayName = "System & Foreground Service",
        description = "Persistent platform service status and service watchdogs",
        defaultImportance = NotificationManager.IMPORTANCE_LOW
    ),
    COMMUNICATION(
        id = "cat_communication",
        displayName = "Peer & Command Communication",
        description = "Incoming peer messages, remote commands, and gateway events",
        defaultImportance = NotificationManager.IMPORTANCE_DEFAULT
    ),
    DATA_TRANSFER(
        id = "cat_data_transfer",
        displayName = "Data & Media Transfers",
        description = "File transfers, streaming sessions, and download progress",
        defaultImportance = NotificationManager.IMPORTANCE_LOW
    ),
    SECURITY(
        id = "cat_security",
        displayName = "Security & Biometric Gates",
        description = "Unauthorized access attempts, pairing confirmations, and lock alerts",
        defaultImportance = NotificationManager.IMPORTANCE_HIGH
    ),
    AUTOMATION(
        id = "cat_automation",
        displayName = "Automations & Alarms",
        description = "Scheduled triggers, geofence boundary events, and battery threshold alerts",
        defaultImportance = NotificationManager.IMPORTANCE_DEFAULT
    ),
    CUSTOM_APPS(
        id = "cat_custom_apps",
        displayName = "Modular Sub-Apps",
        description = "Dynamic notifications created by installed platform apps",
        defaultImportance = NotificationManager.IMPORTANCE_DEFAULT
    )
}

data class ManagedNotificationChannel(
    val channelId: String,
    val channelName: String,
    val channelDescription: String,
    val category: NotificationCategory,
    val appId: String = "core_platform",
    val appName: String = "Android Star",
    val isEnabled: Boolean = true
)

/**
 * Manages organized notification channels, groups, and per-app/per-category enablement toggles.
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
        // Load disabled state from prefs
        prefs.getStringSet("disabled_channels", emptySet())?.forEach { disabledChannels.add(it) }
        prefs.getStringSet("disabled_apps", emptySet())?.forEach { disabledApps.add(it) }

        setupStandardGroupsAndChannels()
    }

    private fun setupStandardGroupsAndChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Create notification channel groups for each category
            NotificationCategory.entries.forEach { cat ->
                val group = NotificationChannelGroup(cat.id, cat.displayName)
                notificationManager.createNotificationChannelGroup(group)
            }

            // Register standard base channels
            registerChannelInternal(
                channelId = "star_foreground_service",
                channelName = "Platform Service Status",
                description = "Shows live background operation status",
                category = NotificationCategory.SYSTEM_FOREGROUND,
                importance = NotificationManager.IMPORTANCE_LOW
            )

            registerChannelInternal(
                channelId = "star_security_alerts",
                channelName = "Security & Biometric Alerts",
                description = "Platform lock and biometric gate alerts",
                category = NotificationCategory.SECURITY,
                importance = NotificationManager.IMPORTANCE_HIGH
            )

            registerChannelInternal(
                channelId = "star_peer_communication",
                channelName = "Peer Commands & Chat",
                description = "Remote command triggers and peer messages",
                category = NotificationCategory.COMMUNICATION,
                importance = NotificationManager.IMPORTANCE_DEFAULT
            )

            registerChannelInternal(
                channelId = "star_data_transfers",
                channelName = "File Transfers & Streams",
                description = "File server and download progress indicators",
                category = NotificationCategory.DATA_TRANSFER,
                importance = NotificationManager.IMPORTANCE_LOW
            )

            registerChannelInternal(
                channelId = "star_automations",
                channelName = "Scheduled Automation Rules",
                description = "Automated triggers, geofence, and sensor actions",
                category = NotificationCategory.AUTOMATION,
                importance = NotificationManager.IMPORTANCE_DEFAULT
            )
        }
    }

    private fun registerChannelInternal(
        channelId: String,
        channelName: String,
        description: String,
        category: NotificationCategory,
        importance: Int,
        appId: String = "core_platform",
        appName: String = "Android Star"
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, channelName, importance).apply {
                this.description = description
                group = category.id
            }
            notificationManager.createNotificationChannel(channel)
        }

        val isEnabled = !disabledChannels.contains(channelId) && !disabledApps.contains(appId)
        val managed = ManagedNotificationChannel(
            channelId = channelId,
            channelName = channelName,
            channelDescription = description,
            category = category,
            appId = appId,
            appName = appName,
            isEnabled = isEnabled
        )
        channels[channelId] = managed
        _channelsFlow.value = channels.values.toList()
    }

    /**
     * Dynamically registers a notification channel for a specific sub-app module.
     */
    fun registerAppChannel(
        appId: String,
        appName: String,
        channelId: String,
        channelName: String,
        channelDescription: String,
        category: NotificationCategory = NotificationCategory.CUSTOM_APPS,
        importance: Int = NotificationManager.IMPORTANCE_DEFAULT
    ) {
        registerChannelInternal(
            channelId = channelId,
            channelName = channelName,
            description = channelDescription,
            category = category,
            importance = importance,
            appId = appId,
            appName = appName
        )
        logger.i("NotificationChannelManager", "Registered channel '$channelId' for app '$appName' [$category]")
    }

    /**
     * Checks if notifications for a specific channel and app are allowed by the user.
     */
    fun isNotificationAllowed(channelId: String, appId: String = "core_platform"): Boolean {
        if (disabledApps.contains(appId)) return false
        if (disabledChannels.contains(channelId)) return false
        return true
    }

    /**
     * Toggle a specific notification channel on or off.
     */
    fun setChannelEnabled(channelId: String, enabled: Boolean) {
        if (enabled) {
            disabledChannels.remove(channelId)
        } else {
            disabledChannels.add(channelId)
        }
        prefs.edit().putStringSet("disabled_channels", disabledChannels).apply()

        channels[channelId]?.let { ch ->
            channels[channelId] = ch.copy(isEnabled = enabled)
            _channelsFlow.value = channels.values.toList()
        }
        logger.i("NotificationChannelManager", "Channel '$channelId' enabled = $enabled")
    }

    /**
     * Toggle all notifications for a specific app on or off.
     */
    fun setAppNotificationsEnabled(appId: String, enabled: Boolean) {
        if (enabled) {
            disabledApps.remove(appId)
        } else {
            disabledApps.add(appId)
        }
        prefs.edit().putStringSet("disabled_apps", disabledApps).apply()

        // Update all channels for this app
        channels.values.filter { it.appId == appId }.forEach { ch ->
            channels[ch.channelId] = ch.copy(isEnabled = enabled && !disabledChannels.contains(ch.channelId))
        }
        _channelsFlow.value = channels.values.toList()
        logger.i("NotificationChannelManager", "App notifications for '$appId' enabled = $enabled")
    }

    fun isAppEnabled(appId: String): Boolean = !disabledApps.contains(appId)

    fun getChannelsByCategory(category: NotificationCategory): List<ManagedNotificationChannel> {
        return channels.values.filter { it.category == category }
    }
}
