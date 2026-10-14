package com.personal.base.platform.capabilities

import android.content.Context
import android.os.Build
import android.os.CombinedVibration
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.personal.base.core.capability.CapabilityProvider
import com.personal.base.core.capability.CapabilityStatus
import com.personal.base.core.capability.CapabilityType
import com.personal.base.core.logging.PlatformLogger

/**
 * 11. VibrationHapticsCapabilityProvider: rich haptic feedback waveforms and click patterns.
 */
class VibrationHapticsCapabilityProvider(
    private val context: Context,
    private val logger: PlatformLogger
) : CapabilityProvider {

    override val type: CapabilityType = CapabilityType.VIBRATION_HAPTICS

    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
        vm?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    fun vibrateClick() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            vibrator?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(40)
        }
    }

    fun vibrateHeavyClick() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            vibrator?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK))
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(80)
        }
    }

    fun vibratePattern(timings: LongArray, amplitudes: IntArray = intArrayOf()) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val effect = if (amplitudes.isNotEmpty() && amplitudes.size == timings.size) {
                VibrationEffect.createWaveform(timings, amplitudes, -1)
            } else {
                VibrationEffect.createWaveform(timings, -1)
            }
            vibrator?.vibrate(effect)
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(timings, -1)
        }
        logger.i("VibrationHaptics", "Triggered vibration waveform (${timings.size} segments)")
    }

    override fun checkStatus(): CapabilityStatus {
        return if (vibrator?.hasVibrator() == true) CapabilityStatus.Available
        else CapabilityStatus.Unavailable("No hardware vibrator")
    }

    override fun release() {
        vibrator?.cancel()
    }
}
