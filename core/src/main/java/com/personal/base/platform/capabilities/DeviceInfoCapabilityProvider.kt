package com.personal.base.platform.capabilities

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import com.personal.base.core.capability.CapabilityProvider
import com.personal.base.core.capability.CapabilityStatus
import com.personal.base.core.capability.CapabilityType
import com.personal.base.core.logging.PlatformLogger

data class ComprehensiveDeviceInfo(
    val model: String,
    val manufacturer: String,
    val device: String,
    val androidVersion: String,
    val sdkInt: Int,
    val cpuCores: Int,
    val totalRamMb: Long,
    val availRamMb: Long
)

/**
 * 21. DeviceInfoCapabilityProvider: model, OS version, CPU, RAM, and thermal state.
 */
class DeviceInfoCapabilityProvider(
    private val context: Context,
    private val logger: PlatformLogger
) : CapabilityProvider {

    override val type: CapabilityType = CapabilityType.DEVICE_INFO

    private val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

    fun getDeviceInfo(): ComprehensiveDeviceInfo {
        val mem = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(mem)

        return ComprehensiveDeviceInfo(
            model = Build.MODEL,
            manufacturer = Build.MANUFACTURER,
            device = Build.DEVICE,
            androidVersion = Build.VERSION.RELEASE,
            sdkInt = Build.VERSION.SDK_INT,
            cpuCores = Runtime.getRuntime().availableProcessors(),
            totalRamMb = mem.totalMem / (1024 * 1024),
            availRamMb = mem.availMem / (1024 * 1024)
        )
    }

    override fun checkStatus(): CapabilityStatus = CapabilityStatus.Available
    override fun release() {}
}
