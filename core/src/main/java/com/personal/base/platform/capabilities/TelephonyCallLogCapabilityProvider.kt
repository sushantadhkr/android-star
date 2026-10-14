package com.personal.base.platform.capabilities

import android.content.Context
import android.provider.CallLog
import com.personal.base.core.capability.CapabilityProvider
import com.personal.base.core.capability.CapabilityStatus
import com.personal.base.core.capability.CapabilityType
import com.personal.base.core.logging.PlatformLogger
import com.personal.base.platform.permissions.PlatformPermissionManager

private const val READ_CALL_LOG = android.Manifest.permission.READ_CALL_LOG

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
        // Measured on a real Android 16 device: READ_CALL_LOG reports granted while the read still
        // throws SecurityException, because the special-access layer sits above the permission
        // check. So the permission answer is not the answer, and only trying tells the truth.
        permissionManager.registerProbe(READ_CALL_LOG) { canReadCallLog() }

        logger.i("CallLogProvider", "CallLog capability provider initialized. Status=$status")
    }

    override fun release() {}

    /** Whether the call log can be read, decided by reading one row of it. */
    private fun canReadCallLog(): Boolean = try {
        context.contentResolver
            .query(CallLog.Calls.CONTENT_URI, arrayOf(CallLog.Calls._ID), null, null, "date DESC LIMIT 1")
            ?.use { true } != null
    } catch (_: Exception) {
        false
    }

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
