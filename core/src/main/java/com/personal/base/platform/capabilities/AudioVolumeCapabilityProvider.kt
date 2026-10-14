package com.personal.base.platform.capabilities

import android.content.Context
import android.media.AudioManager
import com.personal.base.core.capability.CapabilityProvider
import com.personal.base.core.capability.CapabilityStatus
import com.personal.base.core.capability.CapabilityType
import com.personal.base.core.logging.PlatformLogger

data class AudioVolumeStreams(
    val mediaVolumePercent: Int,
    val ringVolumePercent: Int,
    val voiceVolumePercent: Int,
    val alarmVolumePercent: Int,
    val ringerMode: String
)

/**
 * 6. AudioVolumeCapabilityProvider: volume streams and ringer mode control.
 */
class AudioVolumeCapabilityProvider(
    private val context: Context,
    private val logger: PlatformLogger
) : CapabilityProvider {

    override val type: CapabilityType = CapabilityType.AUDIO_VOLUME

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    fun getAudioVolumeStreams(): AudioVolumeStreams {
        val mediaMax = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val mediaCurrent = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)

        val ringMax = audioManager.getStreamMaxVolume(AudioManager.STREAM_RING).coerceAtLeast(1)
        val ringCurrent = audioManager.getStreamVolume(AudioManager.STREAM_RING)

        val voiceMax = audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL).coerceAtLeast(1)
        val voiceCurrent = audioManager.getStreamVolume(AudioManager.STREAM_VOICE_CALL)

        val alarmMax = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM).coerceAtLeast(1)
        val alarmCurrent = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)

        val modeStr = when (audioManager.ringerMode) {
            AudioManager.RINGER_MODE_NORMAL -> "NORMAL"
            AudioManager.RINGER_MODE_SILENT -> "SILENT"
            AudioManager.RINGER_MODE_VIBRATE -> "VIBRATE"
            else -> "UNKNOWN"
        }

        return AudioVolumeStreams(
            mediaVolumePercent = (mediaCurrent * 100) / mediaMax,
            ringVolumePercent = (ringCurrent * 100) / ringMax,
            voiceVolumePercent = (voiceCurrent * 100) / voiceMax,
            alarmVolumePercent = (alarmCurrent * 100) / alarmMax,
            ringerMode = modeStr
        )
    }

    fun setMediaVolumePercent(percent: Int) {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val target = (percent.coerceIn(0, 100) * max) / 100
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
        logger.i("AudioVolumeCapability", "Set media volume to $percent% ($target / $max)")
    }

    override fun checkStatus(): CapabilityStatus = CapabilityStatus.Available
    override fun release() {}
}
