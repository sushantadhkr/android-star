package com.personal.base.platform.capabilities

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.graphics.Path
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import com.personal.base.core.capability.CapabilityProvider
import com.personal.base.core.capability.CapabilityStatus
import com.personal.base.core.capability.CapabilityType
import com.personal.base.core.logging.PlatformLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class PlatformAccessibilityService : AccessibilityService() {
    companion object {
        var activeInstance: PlatformAccessibilityService? = null

        /**
         * Whether the user has switched this service on, without needing the service to be running.
         *
         * `activeInstance` only answers while the app process is alive and bound, so it cannot be
         * what onboarding reads — a user could have enabled this and the answer would still be
         * "no". Android's own list of enabled services is the authority, so the UI and the API
         * ask the same question and cannot disagree.
         */
        fun isEnabledFor(context: Context): Boolean {
            val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE)
                as? AccessibilityManager ?: return false
            return runCatching {
                manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                    .any { it.resolveInfo?.serviceInfo?.packageName == context.packageName }
            }.getOrDefault(false)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        activeInstance = this
        // Logged because this is the moment input starts working, and it happens after the app
        // process starts — so it is the one thing worth seeing when a caller asks why a tap was
        // refused a second ago.
        android.util.Log.i("PlatformAccessibility", "Accessibility service connected; input is live")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        if (activeInstance == this) activeInstance = null
        android.util.Log.i("PlatformAccessibility", "Accessibility service disconnected; input is gone")
    }

    /**
     * What happened to the last gesture, kept so a caller can be told whether a tap actually ran.
     *
     * `dispatchGesture` returning true only means the request was *accepted*. Android cancels
     * gestures that another app is animating, and reports that through the callback. Keeping the
     * outcome is what lets the API distinguish "sent" from "performed" instead of claiming both.
     */
    @Volatile
    var lastGestureCompleted: Boolean? = null
        private set

    @Volatile
    var lastGestureError: String? = null
        private set

    /** Released by the gesture callback, so a caller can wait for the real outcome. */
    private var gestureLatch = java.util.concurrent.CountDownLatch(0)

    fun dispatchSwipe(fromX: Float, fromY: Float, toX: Float, toY: Float, durationMs: Long): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        val path = Path().apply {
            moveTo(fromX, fromY)
            lineTo(toX, toY)
        }
        return dispatchStroke(path, durationMs)
    }

    fun dispatchTap(x: Float, y: Float, durationMs: Long = 50L): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        val path = Path().apply { moveTo(x, y) }
        return dispatchStroke(path, durationMs)
    }

    private fun dispatchStroke(path: Path, durationMs: Long): Boolean {
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        // Reset before sending, so the fields describe this gesture and not the last one.
        lastGestureCompleted = null
        lastGestureError = null
        gestureLatch = java.util.concurrent.CountDownLatch(1)

        return dispatchGesture(gesture, recordingCallback(), null)
    }

    /**
     * Waits, briefly, for Android to say what became of the last gesture.
     *
     * The result callback fires on the main thread after the gesture has actually been attempted,
     * so reading `lastGestureCompleted` the instant `dispatchGesture` returns always yields null.
     * That would leave the field permanently uninformative and quietly undo the reason for
     * reporting both "submitted" and "completed". A short bounded wait costs almost nothing when
     * the gesture succeeds straight away, and caps the cost when Android never answers.
     */
    fun awaitGestureOutcome(timeoutMs: Long = 700L): Boolean? = try {
        if (gestureLatch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)) {
            lastGestureCompleted
        } else {
            null
        }
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        null
    }

    private fun recordingCallback() = object : AccessibilityService.GestureResultCallback() {
        override fun onCompleted(gestureDescription: GestureDescription?) {
            lastGestureCompleted = true
            gestureLatch.countDown()
        }

        override fun onCancelled(gestureDescription: GestureDescription?) {
            // Cancelled, not performed. Saying "the gesture ran" here would be the one lie an
            // input capability cannot afford: the user would see nothing happen.
            lastGestureCompleted = false
            lastGestureError = "Another app or the system took over before the gesture ran"
            gestureLatch.countDown()
        }
    }
}

/**
 * 14. AccessibilityCapabilityProvider: UI automation, gesture injection, and screen node inspection.
 */
/** What became of a tap: was it accepted, and did it actually run. */
data class TapOutcome(
    val submitted: Boolean,
    val completed: Boolean?,
    val error: String?
)

class AccessibilityCapabilityProvider(
    private val context: Context,
    private val logger: PlatformLogger
) : CapabilityProvider {

    override val type: CapabilityType = CapabilityType.ACCESSIBILITY

    private val accessibilityManager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager

    val isServiceEnabled: Boolean
        get() = PlatformAccessibilityService.activeInstance != null

    /**
     * Whether the user has enabled this app in Settings.
     *
     * Asks Android's enabled-service list rather than the live connection, so a service the user
     * has granted but that has not bound yet still reads as enabled. The alternative would make
     * the API say "not enabled" while the user looks at Settings showing it on.
     */
    val isEnabledInSettings: Boolean
        get() = PlatformAccessibilityService.isEnabledFor(context)

    /** The Settings screen that grants it, for callers that need to send the user there. */
    val grantAction: String
        get() = "android.settings.ACCESSIBILITY_SETTINGS"

    fun injectTap(x: Float, y: Float, durationMs: Long = 50L): TapOutcome =
        dispatch("tap", durationMs) { service, ms -> service.dispatchTap(x, y, ms) }

    fun injectSwipe(
        fromX: Float,
        fromY: Float,
        toX: Float,
        toY: Float,
        durationMs: Long
    ): TapOutcome = dispatch("swipe", durationMs) { service, ms ->
        service.dispatchSwipe(fromX, fromY, toX, toY, ms)
    }

    /**
     * Runs one gesture and reports what became of it.
     *
     * Nothing is dispatched when the user has not enabled the service. Failing quietly and
     * returning false would leave a caller believing a tap had landed on a phone where nothing
     * moved, which is worse than an outright refusal.
     */
    private fun dispatch(
        what: String,
        durationMs: Long,
        send: (PlatformAccessibilityService, Long) -> Boolean
    ): TapOutcome {
        val service = PlatformAccessibilityService.activeInstance
            ?: run {
                logger.w("Accessibility", "Service not enabled; refusing the $what")
                return TapOutcome(submitted = false, completed = null, error = "Not enabled in Settings")
            }

        val submitted = send(service, durationMs)
        // Asked of the service rather than read straight off it: the field is null until the
        // callback runs, and the whole purpose of this answer is to distinguish sent from done.
        val completed = if (submitted) service.awaitGestureOutcome() else null
        logger.i("Accessibility", "$what submitted=$submitted completed=$completed")
        return TapOutcome(submitted = submitted, completed = completed, error = service.lastGestureError)
    }

    override fun checkStatus(): CapabilityStatus {
        return if (isServiceEnabled) CapabilityStatus.Available
        else CapabilityStatus.Unavailable("Accessibility Service not activated in Android Settings")
    }

    override fun release() {}
}
