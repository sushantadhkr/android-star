package com.personal.base.platform.capabilities

import android.content.Context
import android.os.Environment
import android.os.StatFs
import com.personal.base.core.capability.CapabilityProvider
import com.personal.base.core.capability.CapabilityStatus
import com.personal.base.core.capability.CapabilityType
import com.personal.base.core.logging.PlatformLogger
import com.personal.base.platform.permissions.PlatformPermissionManager

data class StorageTelemetry(
    val internalAvailableGb: Double = 0.0,
    val internalTotalGb: Double = 0.0,
    val isExternalStorageAvailable: Boolean = false,
    val externalAvailableGb: Double = 0.0,
    val externalTotalGb: Double = 0.0
)

class StorageCapabilityProvider(
    private val context: Context,
    private val permissionManager: PlatformPermissionManager,
    private val logger: PlatformLogger
) : CapabilityProvider {

    override val type: CapabilityType = CapabilityType.STORAGE

    override val status: CapabilityStatus
        get() = CapabilityStatus.Available

    override fun initialize() {
        logger.i("StorageProvider", "Storage capability provider initialized.")
    }

    override fun release() {}

    fun getStorageTelemetry(): StorageTelemetry {
        return try {
            val internalStat = StatFs(Environment.getDataDirectory().path)
            val intAvail = (internalStat.availableBlocksLong * internalStat.blockSizeLong).toDouble() / (1024 * 1024 * 1024)
            val intTotal = (internalStat.blockCountLong * internalStat.blockSizeLong).toDouble() / (1024 * 1024 * 1024)

            var extAvail = 0.0
            var extTotal = 0.0
            val hasExt = Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED

            if (hasExt) {
                val extStat = StatFs(Environment.getExternalStorageDirectory().path)
                extAvail = (extStat.availableBlocksLong * extStat.blockSizeLong).toDouble() / (1024 * 1024 * 1024)
                extTotal = (extStat.blockCountLong * extStat.blockSizeLong).toDouble() / (1024 * 1024 * 1024)
            }

            StorageTelemetry(
                internalAvailableGb = intAvail,
                internalTotalGb = intTotal,
                isExternalStorageAvailable = hasExt,
                externalAvailableGb = extAvail,
                externalTotalGb = extTotal
            )
        } catch (e: Exception) {
            logger.w("StorageProvider", "Failed to query storage stats", e.message)
            StorageTelemetry()
        }
    }

    fun getStorageOverview(): Map<String, String> {
        val t = getStorageTelemetry()
        return mapOf(
            "internal_free" to String.format(java.util.Locale.US, "%.2f GB", t.internalAvailableGb),
            "internal_total" to String.format(java.util.Locale.US, "%.2f GB", t.internalTotalGb),
            "cache_dir" to context.cacheDir.absolutePath
        )
    }
}
