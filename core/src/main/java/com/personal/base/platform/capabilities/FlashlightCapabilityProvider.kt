package com.personal.base.platform.capabilities

import android.content.Context
import android.hardware.camera2.CameraManager
import com.personal.base.core.capability.CapabilityProvider
import com.personal.base.core.capability.CapabilityStatus
import com.personal.base.core.capability.CapabilityType
import com.personal.base.core.logging.PlatformLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 12. FlashlightCapabilityProvider: camera torch on/off and strobe control.
 */
class FlashlightCapabilityProvider(
    private val context: Context,
    private val logger: PlatformLogger
) : CapabilityProvider {

    override val type: CapabilityType = CapabilityType.FLASHLIGHT

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
    private var rearCameraId: String? = null

    private val _isTorchOn = MutableStateFlow(false)
    val isTorchOn: StateFlow<Boolean> = _isTorchOn.asStateFlow()

    init {
        try {
            rearCameraId = cameraManager?.cameraIdList?.firstOrNull()
        } catch (_: Exception) {}
    }

    fun setTorch(enabled: Boolean): Boolean {
        val camId = rearCameraId ?: return false
        return try {
            cameraManager?.setTorchMode(camId, enabled)
            _isTorchOn.value = enabled
            logger.i("FlashlightCapability", "Flashlight torch set to: $enabled")
            true
        } catch (e: Exception) {
            logger.e("FlashlightCapability", "Error toggling torch: ${e.message}", e.message)
            false
        }
    }

    fun toggleTorch(): Boolean = setTorch(!_isTorchOn.value)

    override fun checkStatus(): CapabilityStatus {
        return if (rearCameraId != null) CapabilityStatus.Available
        else CapabilityStatus.Unavailable("Camera flash not available")
    }

    override fun release() {
        if (_isTorchOn.value) setTorch(false)
    }
}
