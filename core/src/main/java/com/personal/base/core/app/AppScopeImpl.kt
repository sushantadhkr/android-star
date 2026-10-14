package com.personal.base.core.app

import com.personal.base.core.plugin.PlatformContext

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
    override val context: PlatformContext
) : AppScope {

    override val isEnabled: Boolean
        get() = manager.isEnabled(appId)

    /** The live instance, or null when the App is switched off. */
    val instance: PlatformApp?
        get() = manager.instanceOf(appId)

    override fun setEnabled(enabled: Boolean) {
        manager.setEnabled(appId, enabled)
    }
}