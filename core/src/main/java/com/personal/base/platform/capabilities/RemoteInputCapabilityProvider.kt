package com.personal.base.platform.capabilities

import android.content.Context
import com.personal.base.core.capability.CapabilityProvider
import com.personal.base.core.capability.CapabilityStatus
import com.personal.base.core.capability.CapabilityType
import com.personal.base.core.logging.PlatformLogger

/**
 * Driving the screen from somewhere else: taps and swipes at coordinates.
 *
 * There is no text here on purpose. Typing into a field needs the focused node, and reading that
 * requires `canRetrieveWindowContent` — permission to see other apps' screens. Input injection does
 * not. So typing stays in the INPUT_METHOD capability, which only ever writes to whatever the user
 * is already typing into.
 */
class RemoteInputCapabilityProvider(
    private val context: Context,
    private val accessibilityProvider: AccessibilityCapabilityProvider,
    private val logger: PlatformLogger
) : CapabilityProvider {

    override val type: CapabilityType = CapabilityType.REMOTE_INPUT

    val isAvailable: Boolean get() = accessibilityProvider.isEnabledInSettings

    val grantAction: String get() = accessibilityProvider.grantAction

    fun tap(x: Float, y: Float, durationMs: Long): TapOutcome {
        logger.i("RemoteInput", "Tap at ($x, $y)")
        return accessibilityProvider.injectTap(x, y, durationMs)
    }

    fun swipe(fromX: Float, fromY: Float, toX: Float, toY: Float, durationMs: Long): TapOutcome {
        logger.i("RemoteInput", "Swipe ($fromX, $fromY) -> ($toX, $toY)")
        return accessibilityProvider.injectSwipe(fromX, fromY, toX, toY, durationMs)
    }

    override fun checkStatus(): CapabilityStatus = accessibilityProvider.checkStatus()
    override fun release() {}
}