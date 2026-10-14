package com.personal.base.core.app

import com.personal.base.core.capability.CapabilityType
import com.personal.base.core.logging.PlatformLogger
import com.personal.base.core.platform.EmbeddedServer
import com.personal.base.core.plugin.PlatformContext
import com.personal.base.platform.capabilities.NotificationCapabilityProvider
import com.personal.base.core.runtime.NotificationChannelManager

/**
 * The scope handed to a contributed composable.
 *
 * It resolves the App's live instance on every access rather than capturing it, so a card always
 * reflects the current state even across a toggle. When the App is off, [instance] is null — the
 * card stays on the dashboard but shows as disabled.
 */
class AppScopeImpl(
    private val manager: AppManager,
    override val appId: String,
    override val context: PlatformContext,
    private val channelManager: NotificationChannelManager? = null,
    private val logger: PlatformLogger? = null
) : AppScope {

    override val isEnabled: Boolean
        get() = manager.isEnabled(appId)

    /** The live instance, or null when the App is switched off. */
    val instance: PlatformApp?
        get() = manager.instanceOf(appId)

    override fun setEnabled(enabled: Boolean) {
        manager.setEnabled(appId, enabled)
    }

    override fun postNotification(title: String, message: String, priorityHigh: Boolean) {
        // Create the App's channel on first use. Android silently drops a notification posted to a
        // channel that does not exist yet.
        val channelId = channelManager?.ensureAppChannel(appId, appId.replaceFirstChar { it.uppercase() })

        val provider = context.getCapabilityByClass(NotificationCapabilityProvider::class.java)
        if (provider == null) {
            logger?.w("AppScope", "App '$appId' could not post: notifications unavailable")
            return
        }

        if (channelId == null) {
            // No channel manager wired — fall back to a platform channel rather than dropping it.
            provider.postNotification(title, message, priorityHigh)
        } else {
            provider.postNotification(title, message, priorityHigh, channelId)
        }
    }
}
