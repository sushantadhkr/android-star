package com.personal.base.platform.capabilities

import android.content.Context
import android.hardware.display.DisplayManager
import android.provider.Settings
import android.view.Display
import com.personal.base.core.capability.CapabilityProvider
import com.personal.base.core.capability.CapabilityStatus
import com.personal.base.core.capability.CapabilityType
import com.personal.base.core.logging.PlatformLogger

data class DisplayInfoState(
    val brightnessPercent: Int,
    val isAutoBrightness: Boolean,
    val displayState: String,
    val refreshRateHz: Float
)

/**
 * 5. DisplayCapabilityProvider: brightness, orientation, screen on/off state, and display metrics.
 */
class DisplayCapabilityProvider(
    private val context: Context,
    private val logger: PlatformLogger
) : CapabilityProvider {

    override val type: CapabilityType = CapabilityType.DISPLAY

    private val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager

    fun getDisplayInfo(): DisplayInfoState {
        val brightness = try {
            val raw = Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
            (raw * 100) / 255
        } catch (_: Exception) { 50 }

        val auto = try {
            Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE) ==
                    Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
        } catch (_: Exception) { false }

        val defaultDisplay = displayManager?.getDisplay(Display.DEFAULT_DISPLAY)
        val stateStr = when (defaultDisplay?.state) {
            Display.STATE_ON -> "SCREEN_ON"
            Display.STATE_OFF -> "SCREEN_OFF"
            Display.STATE_DOZE -> "SCREEN_DOZE"
            else -> "UNKNOWN"
        }

        return DisplayInfoState(
            brightnessPercent = brightness,
            isAutoBrightness = auto,
            displayState = stateStr,
            refreshRateHz = defaultDisplay?.refreshRate ?: 60f
        )
    }

    override fun checkStatus(): CapabilityStatus = CapabilityStatus.Available
    override fun release() {}
}
