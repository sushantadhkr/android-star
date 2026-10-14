package com.personal.base.platform.capabilities

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
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
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        activeInstance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        if (activeInstance == this) activeInstance = null
    }

    fun dispatchTap(x: Float, y: Float): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 50)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gesture, null, null)
    }
}

/**
 * 14. AccessibilityCapabilityProvider: UI automation, gesture injection, and screen node inspection.
 */
class AccessibilityCapabilityProvider(
    private val context: Context,
    private val logger: PlatformLogger
) : CapabilityProvider {

    override val type: CapabilityType = CapabilityType.ACCESSIBILITY

    private val accessibilityManager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager

    val isServiceEnabled: Boolean
        get() = PlatformAccessibilityService.activeInstance != null

    fun injectTap(x: Float, y: Float): Boolean {
        val service = PlatformAccessibilityService.activeInstance
        return if (service != null) {
            val res = service.dispatchTap(x, y)
            logger.i("AccessibilityCapability", "Injected tap at ($x, $y): $res")
            res
        } else {
            logger.w("AccessibilityCapability", "AccessibilityService not enabled by user")
            false
        }
    }

    override fun checkStatus(): CapabilityStatus {
        return if (isServiceEnabled) CapabilityStatus.Available
        else CapabilityStatus.Unavailable("Accessibility Service not activated in Android Settings")
    }

    override fun release() {}
}
