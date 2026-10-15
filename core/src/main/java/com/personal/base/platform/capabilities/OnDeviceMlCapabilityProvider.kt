package com.personal.base.platform.capabilities

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.media.FaceDetector
import com.personal.base.core.capability.CapabilityProvider
import com.personal.base.core.capability.CapabilityStatus
import com.personal.base.core.capability.CapabilityType
import com.personal.base.core.logging.PlatformLogger

/** One face found in an image, in normalised coordinates so it survives a resize. */
data class DetectedFace(
    val centreX: Float,
    val centreY: Float,
    val width: Float,
    val height: Float,
    /** Distance between the eyes, as a fraction of image width. Larger means closer. */
    val eyeDistance: Float,
    /** Android's own confidence for this detection, between 1 and 100. */
    val confidence: Float
)

/**
 * On-device image analysis: finding faces.
 *
 * This used to claim to read text and returned a hardcoded string with a made-up confidence — the
 * exact fake success this platform does not ship, found by auditing every action against a real
 * photograph of a screenshot. It is gone.
 *
 * What is here instead is what Android can genuinely do with no dependency at all: FaceDetector,
 * which really runs inference on the device. It finds faces and nothing more.
 *
 * Text recognition is deliberately **not** offered. Android ships no general on-device OCR API, and
 * a real one means ML Kit — a dependency this platform has not taken. Offering an action that
 * returns invented words would be worse than not offering it, so the capability says so plainly.
 */
class OnDeviceMlCapabilityProvider(
    private val context: Context,
    private val logger: PlatformLogger
) : CapabilityProvider {

    override val type: CapabilityType = CapabilityType.ON_DEVICE_ML

    /** Widest edge handed to FaceDetector. Bigger images exhaust its fixed working buffer. */
    private val maxAnalysisEdge = 480

    /** Android's own ceiling for a single pass. */
    private val maxFaces = 8

    /**
     * Finds faces in a bitmap.
     *
     * Returns what was actually measured, including nothing: an image with no faces in it answers
     * an empty list rather than a guess. The detector needs RGB_565 and a bounded size, so the
     * image is converted and scaled first and the boxes are mapped back to the original.
     */
    fun findFaces(bitmap: Bitmap): List<DetectedFace> {
        val scaled = downscaleForAnalysis(bitmap)
        val found = arrayOfNulls<FaceDetector.Face>(maxFaces)
        val count = FaceDetector(scaled.width, scaled.height, maxFaces).findFaces(scaled, found)

        val faces = mutableListOf<DetectedFace>()
        for (index in 0 until count) {
            val face = found.getOrNull(index) ?: continue
            val midpoint = android.graphics.PointF()
            face.getMidPoint(midpoint)
            val eyeDistance = face.eyesDistance()
            faces.add(
                DetectedFace(
                    centreX = midpoint.x / scaled.width,
                    centreY = midpoint.y / scaled.height,
                    // Android measures no box, only the eye distance. A face is conventionally
                    // about twice as wide as the gap between its eyes, and a little taller than
                    // that, so the box is derived rather than measured. Stated here because a
                    // caller comparing these numbers against a real detector will want to know
                    // which fields are observations and which are geometry.
                    width = (eyeDistance * 2.0f) / scaled.width,
                    height = (eyeDistance * 2.4f) / scaled.height,
                    eyeDistance = eyeDistance / scaled.width,
                    confidence = face.confidence()
                )
            )
        }
        logger.i("OnDeviceMl", "Analysed ${scaled.width}x${scaled.height}, found ${faces.size} face(s)")
        return faces
    }

    /** Average brightness 0..1, measured from the image rather than assumed from its size. */
    fun averageBrightness(bitmap: Bitmap): Float {
        val small = Bitmap.createScaledBitmap(bitmap, 32, 32, true)
        var total = 0L
        for (y in 0 until small.height) {
            for (x in 0 until small.width) {
                total += Color.red(small.getPixel(x, y)) * 299 +
                    Color.green(small.getPixel(x, y)) * 587 +
                    Color.blue(small.getPixel(x, y)) * 114
            }
        }
        small.recycle()
        return (total / (32 * 32).toDouble() / 1000.0).toFloat()
    }

    /**
     * Converts to the RGB_565 the detector requires, scaled so its working buffer holds.
     *
     * The mapping back to original proportions happens in [findFaces] by dividing by the scaled
     * dimensions, so a caller always gets fractions of the image it supplied.
     */
    private fun downscaleForAnalysis(bitmap: Bitmap): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        val scale = if (longest > maxAnalysisEdge) maxAnalysisEdge.toFloat() / longest else 1f
        val targetWidth = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val targetHeight = (bitmap.height * scale).toInt().coerceAtLeast(1)

        val scaled = Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
        if (scaled.config == Bitmap.Config.RGB_565) return scaled

        val rgb565 = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.RGB_565)
        Canvas(rgb565).drawBitmap(
            scaled, 0f, 0f,
            Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = null }
        )
        if (scaled != bitmap) scaled.recycle()
        return rgb565
    }

    /**
     * Average brightness of the bitmap the detector actually receives.
     *
     * Exists to be tested. A conversion bug that handed FaceDetector a blank image would return
     * "no faces" for every picture and look exactly like a working detector on photographs with
     * nobody in them — indistinguishable to a caller, and undetectable without this.
     */
    fun analysisBrightness(bitmap: Bitmap): Float {
        val scaled = downscaleForAnalysis(bitmap)
        return try {
            averageBrightness(scaled)
        } finally {
            scaled.recycle()
        }
    }

    /** The size the detector is handed, so a caller can see how much was thrown away. */
    fun analysisSize(bitmap: Bitmap): Pair<Int, Int> {
        val scaled = downscaleForAnalysis(bitmap)
        return try {
            scaled.width to scaled.height
        } finally {
            scaled.recycle()
        }
    }

    /** Whether this capability can do anything at all here. It can: faces are detectable. */
    override fun checkStatus(): CapabilityStatus = CapabilityStatus.Partial(
        whatWorks = "Face detection on an image, entirely on the device",
        whatDoesNot = "Text recognition and image classification. Android ships no on-device OCR " +
            "API, and ML Kit is not a dependency of this platform, so no text is claimed."
    )

    override fun release() {}
}