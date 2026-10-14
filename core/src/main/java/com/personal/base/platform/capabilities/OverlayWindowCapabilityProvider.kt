package com.personal.base.platform.capabilities

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.personal.base.core.capability.CapabilityProvider
import com.personal.base.core.capability.CapabilityStatus
import com.personal.base.core.capability.CapabilityType
import com.personal.base.core.logging.PlatformLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 33. OverlayWindowCapabilityProvider: draw-over-apps floating HUD, overlay widgets, and remote pointer.
 */
class OverlayWindowCapabilityProvider(
    private val context: Context,
    private val logger: PlatformLogger
) : CapabilityProvider {

    override val type: CapabilityType = CapabilityType.OVERLAY_WINDOW

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
    private var overlayView: View? = null

    private val _isOverlayShowing = MutableStateFlow(false)
    val isOverlayShowing: StateFlow<Boolean> = _isOverlayShowing.asStateFlow()

    val canDrawOverlays: Boolean
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else true

    fun promptOverlayPermissionIntent(): Intent? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !canDrawOverlays) {
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}")
            ).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
        } else null
    }

    fun showFloatingPointer(x: Int, y: Int) {
        if (!canDrawOverlays || windowManager == null) return
        try {
            val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            val params = WindowManager.LayoutParams(
                64, 64,
                layoutType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                this.x = x
                this.y = y
            }

            if (overlayView == null) {
                overlayView = View(context).apply {
                    setBackgroundColor(android.graphics.Color.RED)
                }
                windowManager.addView(overlayView, params)
                _isOverlayShowing.value = true
                logger.i("OverlayWindow", "Added floating overlay view.")
            } else {
                windowManager.updateViewLayout(overlayView, params)
            }
        } catch (e: Exception) {
            logger.e("OverlayWindow", "Failed to show overlay: ${e.message}", e.message)
        }
    }

    fun hideOverlay() {
        if (overlayView != null && windowManager != null) {
            try {
                windowManager.removeView(overlayView)
                overlayView = null
                _isOverlayShowing.value = false
                logger.i("OverlayWindow", "Removed floating overlay view.")
            } catch (_: Exception) {}
        }
    }

    override fun checkStatus(): CapabilityStatus {
        return if (canDrawOverlays) CapabilityStatus.Available
        else CapabilityStatus.PermissionRequired(listOf(android.Manifest.permission.SYSTEM_ALERT_WINDOW))
    }

    override fun release() {
        hideOverlay()
    }
}
