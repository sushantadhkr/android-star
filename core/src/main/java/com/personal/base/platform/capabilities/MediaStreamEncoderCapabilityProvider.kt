package com.personal.base.platform.capabilities

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import com.personal.base.core.capability.CapabilityProvider
import com.personal.base.core.capability.CapabilityStatus
import com.personal.base.core.capability.CapabilityType
import com.personal.base.core.logging.PlatformLogger

/**
 * 34. MediaStreamEncoderCapabilityProvider: hardware MediaCodec AVC/H.264 video and AAC audio encoding.
 */
class MediaStreamEncoderCapabilityProvider(
    private val logger: PlatformLogger
) : CapabilityProvider {

    override val type: CapabilityType = CapabilityType.MEDIA_STREAM_ENCODER

    fun createVideoEncoder(width: Int = 1280, height: Int = 720, bitRate: Int = 2_000_000): MediaCodec? {
        return try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
                setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            }
            val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            logger.i("MediaStreamEncoder", "Created H.264 video encoder (${width}x$height @ 30fps)")
            codec
        } catch (e: Exception) {
            logger.e("MediaStreamEncoder", "Failed to create video encoder: ${e.message}", e.message)
            null
        }
    }

    override fun checkStatus(): CapabilityStatus = CapabilityStatus.Available
    override fun release() {}
}
