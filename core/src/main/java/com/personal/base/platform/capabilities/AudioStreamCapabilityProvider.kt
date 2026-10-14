package com.personal.base.platform.capabilities

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import com.personal.base.core.capability.CapabilityProvider
import com.personal.base.core.capability.CapabilityStatus
import com.personal.base.core.capability.CapabilityType
import com.personal.base.core.logging.PlatformLogger
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 35. AudioStreamCapabilityProvider: live raw PCM audio capture and playback streaming.
 */
class AudioStreamCapabilityProvider(
    private val context: Context,
    private val logger: PlatformLogger
) : CapabilityProvider {

    override val type: CapabilityType = CapabilityType.AUDIO_STREAM

    private val _pcmAudioFlow = MutableSharedFlow<ByteArray>(extraBufferCapacity = 32)
    val pcmAudioFlow: SharedFlow<ByteArray> = _pcmAudioFlow.asSharedFlow()

    private var isRecording = false

    fun startCapture(sampleRate: Int = 16000) {
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            logger.w("AudioStream", "RECORD_AUDIO permission not granted")
            return
        }

        val minBufSize = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        try {
            val record = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBufSize
            )
            record.startRecording()
            isRecording = true
            logger.i("AudioStream", "Started live audio PCM recording @ ${sampleRate}Hz")

            Thread {
                val buffer = ByteArray(minBufSize)
                while (isRecording) {
                    val read = record.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        _pcmAudioFlow.tryEmit(buffer.copyOf(read))
                    }
                }
                record.stop()
                record.release()
            }.start()
        } catch (e: Exception) {
            logger.e("AudioStream", "Error starting audio capture: ${e.message}", e.message)
        }
    }

    fun stopCapture() {
        isRecording = false
        logger.i("AudioStream", "Stopped live audio recording.")
    }

    override fun checkStatus(): CapabilityStatus {
        val granted = ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        return if (granted) CapabilityStatus.Available
        else CapabilityStatus.PermissionRequired(listOf(android.Manifest.permission.RECORD_AUDIO))
    }

    override fun release() {
        stopCapture()
    }
}
