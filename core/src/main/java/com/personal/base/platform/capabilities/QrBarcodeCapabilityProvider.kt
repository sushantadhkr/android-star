package com.personal.base.platform.capabilities

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import com.personal.base.core.capability.CapabilityProvider
import com.personal.base.core.capability.CapabilityStatus
import com.personal.base.core.capability.CapabilityType
import com.personal.base.core.logging.PlatformLogger

/**
 * 20. QrBarcodeCapabilityProvider: scan and generate QR / barcodes.
 */
class QrBarcodeCapabilityProvider(
    private val context: Context,
    private val logger: PlatformLogger
) : CapabilityProvider {

    override val type: CapabilityType = CapabilityType.QR_BARCODE

    fun generateDummyQrBitmap(content: String, size: Int = 256): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val hash = content.hashCode()
        for (x in 0 until size) {
            for (y in 0 until size) {
                val isDark = ((x / 16) + (y / 16) + (hash and 0xFF)) % 2 == 0
                bitmap.setPixel(x, y, if (isDark) Color.BLACK else Color.WHITE)
            }
        }
        logger.i("QrBarcodeCapability", "Generated QR representation for '$content' (${size}x${size})")
        return bitmap
    }

    override fun checkStatus(): CapabilityStatus = CapabilityStatus.Available
    override fun release() {}
}
