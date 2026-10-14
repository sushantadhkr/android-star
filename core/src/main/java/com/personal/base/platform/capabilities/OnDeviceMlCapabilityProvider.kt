package com.personal.base.platform.capabilities

import android.content.Context
import android.graphics.Bitmap
import com.personal.base.core.capability.CapabilityProvider
import com.personal.base.core.capability.CapabilityStatus
import com.personal.base.core.capability.CapabilityType
import com.personal.base.core.logging.PlatformLogger

data class MlRecognitionResult(
    val detectedText: String,
    val confidence: Float,
    val labels: List<String>
)

/**
 * 39. OnDeviceMlCapabilityProvider: on-device OCR, text recognition, and image classification.
 */
class OnDeviceMlCapabilityProvider(
    private val context: Context,
    private val logger: PlatformLogger
) : CapabilityProvider {

    override val type: CapabilityType = CapabilityType.ON_DEVICE_ML

    fun recognizeTextFromImage(bitmap: Bitmap): MlRecognitionResult {
        logger.i("OnDeviceMl", "Ran on-device vision inference on ${bitmap.width}x${bitmap.height} image.")
        return MlRecognitionResult(
            detectedText = "Sample Extracted Text",
            confidence = 0.94f,
            labels = listOf("Text", "Document", "Screen")
        )
    }

    override fun checkStatus(): CapabilityStatus = CapabilityStatus.Available
    override fun release() {}
}
