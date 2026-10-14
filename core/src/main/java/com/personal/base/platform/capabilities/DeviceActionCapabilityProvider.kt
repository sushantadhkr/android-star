package com.personal.base.platform.capabilities

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.personal.base.core.capability.CapabilityProvider
import com.personal.base.core.capability.CapabilityStatus
import com.personal.base.core.capability.CapabilityType
import com.personal.base.core.logging.PlatformLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class DeviceTelemetry(
    val batteryPercentage: Int = -1,
    val isCharging: Boolean = false,
    val chargingSource: String = "Battery",
    val batteryHealth: String = "Good",
    val batteryTemperatureC: Float = 0f,
    val batteryVoltageMv: Int = 0,
    val batteryTechnology: String = "Li-ion",
    val isTorchOn: Boolean = false,
    val isIgnoringBatteryOptimizations: Boolean = false
)

class DeviceActionCapabilityProvider(
    private val context: Context,
    private val logger: PlatformLogger
) : CapabilityProvider {

    override val type: CapabilityType = CapabilityType.DEVICE_ACTIONS

    private var vibrator: Vibrator? = null
    private var cameraManager: CameraManager? = null
    private var primaryCameraId: String? = null
    private var isTorchActive = false

    private val _telemetry = MutableStateFlow(DeviceTelemetry())
    val telemetry: StateFlow<DeviceTelemetry> = _telemetry.asStateFlow()

    override val status: CapabilityStatus = CapabilityStatus.Available

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_BATTERY_CHANGED) {
                parseBatteryIntent(intent)
            }
        }
    }

    override fun initialize() {
        // Initialize Vibrator
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vm?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

        // Initialize CameraManager for torch
        cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
        try {
            primaryCameraId = cameraManager?.cameraIdList?.firstOrNull()
        } catch (e: Exception) {
            logger.w("DeviceActions", "Failed to list camera IDs for torch", e.message)
        }

        // Register real-time Battery Receiver
        try {
            val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val sticky = context.registerReceiver(batteryReceiver, filter)
            if (sticky != null) {
                parseBatteryIntent(sticky)
            }
        } catch (e: Exception) {
            logger.w("DeviceActions", "Failed to register battery receiver", e.message)
            refreshTelemetryFallback()
        }

        logger.i("DeviceActions", "Device actions initialized. Real-time battery telemetry synchronized.")
    }

    override fun release() {
        try {
            context.unregisterReceiver(batteryReceiver)
        } catch (_: Exception) {}

        if (isTorchActive) {
            setTorch(false)
        }
    }

    fun vibrate(durationMs: Long = 100L) {
        val v = vibrator ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(durationMs)
            }
            logger.d("DeviceActions", "Triggered haptic vibration for ${durationMs}ms")
        } catch (e: Exception) {
            logger.e("DeviceActions", "Vibration failed", e.message)
        }
    }

    fun setTorch(enable: Boolean): Boolean {
        val cm = cameraManager ?: return false
        val camId = primaryCameraId ?: return false

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                cm.setTorchMode(camId, enable)
                isTorchActive = enable
                _telemetry.value = _telemetry.value.copy(isTorchOn = enable)
                logger.i("DeviceActions", "Torch state updated: enabled=$enable")
                true
            } else false
        } catch (e: Exception) {
            logger.w("DeviceActions", "Failed to toggle torch mode", e.message)
            false
        }
    }

    fun getBatteryLevel(): Int = _telemetry.value.batteryPercentage

    fun refreshTelemetry() {
        try {
            val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            if (batteryIntent != null) {
                parseBatteryIntent(batteryIntent)
            }
        } catch (_: Exception) {}
    }

    private fun parseBatteryIntent(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val percentage = if (level >= 0 && scale > 0) (level * 100) / scale else -1

        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL

        val chargePlug = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)
        val source = when (chargePlug) {
            BatteryManager.BATTERY_PLUGGED_AC -> "AC Charger"
            BatteryManager.BATTERY_PLUGGED_USB -> "USB Port"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Wireless"
            else -> if (isCharging) "Charging" else "Unplugged"
        }

        val healthCode = intent.getIntExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_UNKNOWN)
        val health = when (healthCode) {
            BatteryManager.BATTERY_HEALTH_GOOD -> "Good"
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Overheat"
            BatteryManager.BATTERY_HEALTH_DEAD -> "Dead"
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Over Voltage"
            BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "Failure"
            BatteryManager.BATTERY_HEALTH_COLD -> "Cold"
            else -> "Normal"
        }

        val tempTenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)
        val tempCelsius = tempTenths / 10f
        val voltageMv = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0)
        val tech = intent.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY) ?: "Li-ion"

        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val ignoringOptimizations = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            pm?.isIgnoringBatteryOptimizations(context.packageName) == true
        } else true

        _telemetry.value = _telemetry.value.copy(
            batteryPercentage = percentage,
            isCharging = isCharging,
            chargingSource = source,
            batteryHealth = health,
            batteryTemperatureC = tempCelsius,
            batteryVoltageMv = voltageMv,
            batteryTechnology = tech,
            isIgnoringBatteryOptimizations = ignoringOptimizations
        )
    }

    private fun refreshTelemetryFallback() {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager

        val batteryLevel = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        val isCharging = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            bm?.isCharging == true
        } else false

        val ignoringOptimizations = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            pm?.isIgnoringBatteryOptimizations(context.packageName) == true
        } else true

        _telemetry.value = _telemetry.value.copy(
            batteryPercentage = batteryLevel,
            isCharging = isCharging,
            isIgnoringBatteryOptimizations = ignoringOptimizations
        )
    }
}
