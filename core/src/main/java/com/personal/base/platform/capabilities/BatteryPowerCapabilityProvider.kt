package com.personal.base.platform.capabilities

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import com.personal.base.core.capability.CapabilityProvider
import com.personal.base.core.capability.CapabilityStatus
import com.personal.base.core.capability.CapabilityType
import com.personal.base.core.logging.PlatformLogger

data class BatteryPowerState(
    val levelPercentage: Int,
    val isCharging: Boolean,
    val isPowerSaveMode: Boolean,
    val isDeviceIdleMode: Boolean,
    val batteryHealth: String
)

/**
 * 4. BatteryPowerCapabilityProvider: level, charging, thermal state, and power-save modes.
 */
class BatteryPowerCapabilityProvider(
    private val context: Context,
    private val logger: PlatformLogger
) : CapabilityProvider {

    override val type: CapabilityType = CapabilityType.BATTERY_POWER

    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager

    fun getBatteryPowerState(): BatteryPowerState {
        val ifilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryStatus: Intent? = context.registerReceiver(null, ifilter)

        val level: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val batteryPct: Int = if (level >= 0 && scale > 0) (level * 100 / scale) else 0

        val status: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging: Boolean = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL

        val isPowerSave = powerManager?.isPowerSaveMode == true
        val isDeviceIdle = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            powerManager?.isDeviceIdleMode == true
        } else false

        return BatteryPowerState(
            levelPercentage = batteryPct,
            isCharging = isCharging,
            isPowerSaveMode = isPowerSave,
            isDeviceIdleMode = isDeviceIdle,
            batteryHealth = "Good"
        )
    }

    override fun checkStatus(): CapabilityStatus = CapabilityStatus.Available
    override fun release() {}
}
