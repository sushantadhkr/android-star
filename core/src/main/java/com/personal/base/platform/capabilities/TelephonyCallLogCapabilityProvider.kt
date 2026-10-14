package com.personal.base.platform.capabilities

import android.content.Context
import android.provider.CallLog
import com.personal.base.core.capability.CapabilityProvider
import com.personal.base.core.capability.CapabilityStatus
import com.personal.base.core.capability.CapabilityType
import com.personal.base.core.logging.PlatformLogger
import com.personal.base.platform.permissions.PlatformPermissionManager

class TelephonyCallLogCapabilityProvider(
    private val context: Context,
    private val permissionManager: PlatformPermissionManager,
    private val logger: PlatformLogger
) : CapabilityProvider {

    override val type: CapabilityType = CapabilityType.CALL_LOG

    override val status: CapabilityStatus
        get() {
            val missing = permissionManager.getMissingPermissions(type)
            return if (missing.isNotEmpty()) CapabilityStatus.PermissionRequired(missing)
            else CapabilityStatus.Available
        }

    override fun initialize() {
        logger.i("CallLogProvider", "CallLog capability provider initialized. Status=$status")
    }

    override fun release() {}

    fun getCallLogCount(): Int {
        if (!status.isUsable) return 0
        return try {
            val cursor = context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls._ID),
                null,
                null,
                null
            )
            val count = cursor?.count ?: 0
            cursor?.close()
            count
        } catch (e: Exception) {
            logger.w("CallLogProvider", "Failed to query call logs", e.message)
            0
        }
    }
}
