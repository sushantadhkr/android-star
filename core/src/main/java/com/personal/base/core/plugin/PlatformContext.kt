package com.personal.base.core.plugin

import com.personal.base.core.capability.CapabilityProvider
import com.personal.base.core.capability.CapabilityStatus
import com.personal.base.core.capability.CapabilityType
import com.personal.base.core.logging.PlatformLogger
import android.content.Context

/**
 * Standard location telemetry result returned to plugins safely.
 */
data class PlatformLocation(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float,
    val provider: String,
    val timestamp: Long
)

/**
 * Reusable, safe facade API passed to plugins.
 * Plugins interact with this interface rather than directly accessing Android platform internals.
 */
interface PlatformContext {
    val logger: PlatformLogger

    /** Checks if a system capability is available and ready. */
    fun hasCapability(type: CapabilityType): Boolean

    /** Checks status of a capability. */
    fun getCapabilityStatus(type: CapabilityType): CapabilityStatus

    /** Obtains the typed capability provider if available and permitted. */
    fun <T : CapabilityProvider> getCapability(type: CapabilityType): T?

    /** Obtains the typed capability provider by its class. */
    fun <T : CapabilityProvider> getCapabilityByClass(clazz: Class<T>): T?

    /** Posts a notification through safe platform channels. */
    fun postNotification(title: String, message: String, priorityHigh: Boolean = false)

    /**
     * The Android context, for platform services that are not capabilities.
     *
     * Deliberately not a general-purpose escape hatch: it is here so an App can use platform
     * facilities such as [com.personal.base.core.platform.ProgressNotifier] without the platform
     * having to wrap each one in a capability.
     */
    fun appContext(): Context

    /**
     * Shows or updates this App's home-screen widget.
     *
     * On the facade rather than the App class, because that is the one thing an App is given when
     * it starts — and a widget that could only be set from a composable would be no use to a
     * headless App doing a background job.
     */
    fun publishWidget(spec: com.personal.base.core.app.WidgetSpec?)

    /** Triggers haptic vibration feedback. */
    fun triggerHaptic(durationMs: Long = 100L)

    /** Toggles flashlight/torch if available. */
    fun setTorch(enabled: Boolean): Boolean

    /** Returns current battery percentage (0-100). */
    fun getBatteryPercentage(): Int

    /** Reads plain text from clipboard. */
    fun getClipboardContent(): String?

    /** Writes plain text to clipboard. */
    fun setClipboardContent(text: String, label: String = "Platform")

    /** Launches standard Android share sheet. */
    fun shareText(text: String, title: String = "Share via Base Platform")

    /** Obtains the last known or freshest device location. */
    suspend fun getFreshLocation(): PlatformLocation?
}
