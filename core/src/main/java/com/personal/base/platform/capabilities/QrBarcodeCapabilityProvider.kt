package com.personal.base.platform.capabilities

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.MultiFormatWriter
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.personal.base.core.capability.CapabilityProvider
import com.personal.base.core.capability.CapabilityStatus
import com.personal.base.core.capability.CapabilityType
import com.personal.base.core.logging.PlatformLogger
import java.io.File
import java.io.FileOutputStream

/**
 * QR and barcode generation and decoding.
 *
 * Encoding and decoding are both real: a generated bitmap is a scannable QR symbol, and decoding
 * reads actual barcodes out of captured images rather than pretending to.
 */
class QrBarcodeCapabilityProvider(
    private val context: android.content.Context,
    private val logger: PlatformLogger
) : CapabilityProvider {

    override val type: CapabilityType = CapabilityType.QR_BARCODE

    /**
     * Encodes text as a scannable QR code.
     *
     * @param margin quiet-zone width in modules; Android scanners need at least 4 to read reliably.
     */
    fun generate(
        content: String,
        size: Int = 512,
        margin: Int = 4
    ): Bitmap? = runCatching {
        val hints = mapOf(
            EncodeHintType.MARGIN to margin,
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.ERROR_CORRECTION to com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.M
        )
        val matrix = MultiFormatWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints)
        bitmapFrom(matrix, size)
    }.getOrElse {
        logger.e("QrBarcode", "Encode failed: ${it.message}")
        null
    }

    /** Encodes several values as a Code 128 barcode. */
    fun generateCode128(
        content: String,
        width: Int = 512,
        height: Int = 192
    ): Bitmap? = runCatching {
        val hints = mapOf(EncodeHintType.CHARACTER_SET to "UTF-8")
        val matrix = MultiFormatWriter().encode(content, BarcodeFormat.CODE_128, width, height, hints)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        for (x in 0 until width) {
            for (y in 0 until height) {
                bitmap.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
            }
        }
        bitmap
    }.getOrElse {
        logger.e("QrBarcode", "Code 128 encode failed: ${it.message}")
        null
    }

    /**
     * Decodes the first barcode found in an image.
     *
     * This is how a scanned or captured code is read back — pass a photo taken by the camera
     * capability and get the payload out.
     */
    fun decode(bitmap: Bitmap): String? = runCatching {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val source = RGBLuminanceSource(width, height, pixels)
        val hints = mapOf(
            DecodeHintType.TRY_HARDER to true,
            DecodeHintType.POSSIBLE_FORMATS to listOf(
                BarcodeFormat.QR_CODE, BarcodeFormat.CODE_128, BarcodeFormat.DATA_MATRIX
            )
        )
        MultiFormatReader()
            .decode(BinaryBitmap(HybridBinarizer(source)), hints)
            .text
    }.getOrNull()

    /** Decodes an image file, such as one produced by the camera capability. */
    fun decodeFile(file: File): String? {
        val bitmap = runCatching {
            android.graphics.BitmapFactory.decodeFile(file.absolutePath)
        }.getOrNull() ?: return null
        return decode(bitmap)?.also { bitmap.recycle() }
    }

    /** Encodes and writes to a file, returning it. */
    fun generateToFile(
        content: String,
        prefix: String = "qr",
        size: Int = 512
    ): File? {
        val bitmap = generate(content, size) ?: return null
        val dir = File(context.filesDir, "exports").apply { if (!exists()) mkdirs() }
        val file = File(dir, "$prefix-${System.currentTimeMillis()}.png")
        return runCatching {
            FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            logger.i("QrBarcode", "Wrote QR to ${file.name}")
            file
        }.getOrNull()
    }

    private fun bitmapFrom(
        matrix: com.google.zxing.common.BitMatrix,
        size: Int
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        for (x in 0 until size) {
            for (y in 0 until size) {
                bitmap.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
            }
        }
        return bitmap
    }

    override fun checkStatus(): CapabilityStatus = CapabilityStatus.Available

    override fun release() {}
}
