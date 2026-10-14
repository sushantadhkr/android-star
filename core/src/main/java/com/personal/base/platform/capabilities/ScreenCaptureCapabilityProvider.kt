package com.personal.base.platform.capabilities

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import com.personal.base.core.capability.CapabilityProvider
import com.personal.base.core.capability.CapabilityStatus
import com.personal.base.core.capability.CapabilityType
import com.personal.base.core.logging.PlatformLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 15. ScreenCaptureCapabilityProvider: MediaProjection screen capture, casting, and virtual displays.
 */
class ScreenCaptureCapabilityProvider(
    private val context: Context,
    private val logger: PlatformLogger
) : CapabilityProvider {

    override val type: CapabilityType = CapabilityType.SCREEN_CAPTURE

    private val projectionManager =
        context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager

    private var activeProjection: MediaProjection? = null

    private val _isCapturing = MutableStateFlow(false)
    val isCapturing: StateFlow<Boolean> = _isCapturing.asStateFlow()

    fun createScreenCaptureIntent(): Intent? = projectionManager?.createScreenCaptureIntent()

    fun onActivityResult(resultCode: Int, data: Intent) {
        if (resultCode == Activity.RESULT_OK) {
            activeProjection = projectionManager?.getMediaProjection(resultCode, data)
            _isCapturing.value = activeProjection != null
            logger.i("ScreenCapture", "MediaProjection acquired successfully.")
        } else {
            logger.w("ScreenCapture", "MediaProjection permission denied by user.")
        }
    }

    fun stopCapture() {
        activeProjection?.stop()
        activeProjection = null
        _isCapturing.value = false
        logger.i("ScreenCapture", "MediaProjection stopped.")
    }

    override fun checkStatus(): CapabilityStatus {
        return if (projectionManager != null) CapabilityStatus.Available
        else CapabilityStatus.Unavailable("MediaProjection unsupported")
    }

    override fun release() {
        stopCapture()
    }
}
