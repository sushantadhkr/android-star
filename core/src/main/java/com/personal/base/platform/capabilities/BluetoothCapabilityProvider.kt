package com.personal.base.platform.capabilities

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.personal.base.core.capability.CapabilityProvider
import com.personal.base.core.capability.CapabilityStatus
import com.personal.base.core.capability.CapabilityType
import com.personal.base.core.logging.PlatformLogger
import com.personal.base.platform.permissions.PlatformPermissionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable

@Serializable
data class BluetoothDeviceInfo(
    val name: String,
    val address: String,
    val isConnected: Boolean,
    val deviceCategory: String,
    val batteryPercentage: Int, // -1 if unknown / unsupported by device
    val healthStatus: String,
    val bondState: String
)

@Serializable
data class BluetoothSystemSummary(
    val isEnabled: Boolean,
    val totalPairedDevices: Int,
    val connectedDevicesCount: Int,
    val devices: List<BluetoothDeviceInfo> = emptyList()
)

/**
 * Low-level Base Platform Capability for Nearby / Bluetooth devices and their health.
 * All Platform Apps consume this provider rather than executing direct Android Bluetooth APIs.
 */
class BluetoothCapabilityProvider(
    private val context: Context,
    private val permissionManager: PlatformPermissionManager,
    private val logger: PlatformLogger
) : CapabilityProvider {

    override val type: CapabilityType = CapabilityType.NEARBY_DEVICES

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter

    private val _systemSummary = MutableStateFlow(BluetoothSystemSummary(isEnabled = false, 0, 0))
    val systemSummary: StateFlow<BluetoothSystemSummary> = _systemSummary.asStateFlow()

    private val batteryCache = mutableMapOf<String, Int>()

    override val status: CapabilityStatus
        get() = if (bluetoothAdapter != null) {
            CapabilityStatus.Available
        } else {
            CapabilityStatus.Unavailable("Bluetooth hardware not available on this device")
        }

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothDevice.ACTION_ACL_CONNECTED,
                BluetoothDevice.ACTION_ACL_DISCONNECTED,
                BluetoothDevice.ACTION_BOND_STATE_CHANGED,
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    refreshDevices()
                }
                "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED" -> {
                    val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                    val level = intent.getIntExtra("android.bluetooth.device.extra.BATTERY_LEVEL", -1)
                    if (device != null && level >= 0) {
                        batteryCache[device.address] = level
                        refreshDevices()
                    }
                }
            }
        }
    }

    override fun initialize() {
        try {
            val filter = IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
                addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
                addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
                addAction("android.bluetooth.device.action.BATTERY_LEVEL_CHANGED")
            }
            context.registerReceiver(bluetoothReceiver, filter)
            refreshDevices()
        } catch (e: Exception) {
            logger.w("BluetoothCapability", "Could not register bluetooth broadcast receiver: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    fun refreshDevices(): BluetoothSystemSummary {
        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            val summary = BluetoothSystemSummary(isEnabled = false, 0, 0, emptyList())
            _systemSummary.value = summary
            return summary
        }

        val hasPermission = permissionManager.arePermissionsGrantedFor(CapabilityType.NEARBY_DEVICES)
        if (!hasPermission) {
            logger.w("BluetoothCapability", "Nearby devices permission not granted")
            val summary = BluetoothSystemSummary(isEnabled = true, 0, 0, emptyList())
            _systemSummary.value = summary
            return summary
        }

        return try {
            val paired = adapter.bondedDevices ?: emptySet()
            val deviceList = paired.map { device ->
                val name = device.name ?: "Unknown Device"
                val address = device.address ?: "00:00:00:00:00:00"

                // Check profile connection status
                val isA2dpConnected = adapter.getProfileConnectionState(BluetoothProfile.A2DP) == BluetoothProfile.STATE_CONNECTED
                val isHeadsetConnected = adapter.getProfileConnectionState(BluetoothProfile.HEADSET) == BluetoothProfile.STATE_CONNECTED
                
                // Reflection for connection state check
                val isConnected = isA2dpConnected || isHeadsetConnected || isDeviceConnectedByReflection(device)

                // Battery query via cache or reflection
                val battery = batteryCache[address] ?: queryDeviceBatteryLevel(device)
                if (battery >= 0) {
                    batteryCache[address] = battery
                }

                val category = resolveDeviceCategory(device.bluetoothClass)
                val health = when {
                    !isConnected -> "Disconnected / Paired"
                    battery in 0..19 -> "Critical Battery ($battery%)"
                    battery in 20..49 -> "Moderate Battery ($battery%)"
                    battery >= 50 -> "Healthy ($battery%)"
                    else -> "Connected (Health Normal)"
                }

                BluetoothDeviceInfo(
                    name = name,
                    address = address,
                    isConnected = isConnected,
                    deviceCategory = category,
                    batteryPercentage = battery,
                    healthStatus = health,
                    bondState = if (device.bondState == BluetoothDevice.BOND_BONDED) "Bonded" else "Pairing"
                )
            }.sortedByDescending { it.isConnected }

            val summary = BluetoothSystemSummary(
                isEnabled = true,
                totalPairedDevices = deviceList.size,
                connectedDevicesCount = deviceList.count { it.isConnected },
                devices = deviceList
            )
            _systemSummary.value = summary
            summary
        } catch (e: Exception) {
            logger.e("BluetoothCapability", "Error querying bluetooth devices: ${e.message}", e.message)
            val fallback = BluetoothSystemSummary(isEnabled = true, 0, 0, emptyList())
            _systemSummary.value = fallback
            fallback
        }
    }

    private fun isDeviceConnectedByReflection(device: BluetoothDevice): Boolean {
        return try {
            val method = device.javaClass.getMethod("isConnected")
            (method.invoke(device) as? Boolean) ?: false
        } catch (_: Exception) {
            false
        }
    }

    private fun queryDeviceBatteryLevel(device: BluetoothDevice): Int {
        return try {
            val method = device.javaClass.getMethod("getBatteryLevel")
            val level = method.invoke(device) as? Int
            level ?: -1
        } catch (_: Exception) {
            -1
        }
    }

    private fun resolveDeviceCategory(btClass: BluetoothClass?): String {
        if (btClass == null) return "Bluetooth Device"
        return when (btClass.majorDeviceClass) {
            BluetoothClass.Device.Major.AUDIO_VIDEO -> "Audio / Headphones / Speaker"
            BluetoothClass.Device.Major.PHONE -> "Smartphone / Cellular"
            BluetoothClass.Device.Major.COMPUTER -> "Laptop / Desktop"
            BluetoothClass.Device.Major.WEARABLE -> "Smartwatch / Wearable"
            BluetoothClass.Device.Major.PERIPHERAL -> "Keyboard / Mouse / Input"
            BluetoothClass.Device.Major.HEALTH -> "Health Sensor / Monitor"
            else -> "Peripheral Device"
        }
    }

    override fun release() {
        try {
            context.unregisterReceiver(bluetoothReceiver)
        } catch (_: Exception) {}
    }
}
