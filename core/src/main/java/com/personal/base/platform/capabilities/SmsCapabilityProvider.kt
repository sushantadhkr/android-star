package com.personal.base.platform.capabilities

import android.content.Context
import android.net.Uri
import com.personal.base.core.capability.CapabilityProvider
import com.personal.base.core.capability.CapabilityStatus
import com.personal.base.core.capability.CapabilityType
import com.personal.base.core.logging.PlatformLogger
import com.personal.base.platform.permissions.PlatformPermissionManager

class SmsCapabilityProvider(
    private val context: Context,
    private val permissionManager: PlatformPermissionManager,
    private val logger: PlatformLogger
) : CapabilityProvider {

    override val type: CapabilityType = CapabilityType.SMS

    override val status: CapabilityStatus
        get() {
            val missing = permissionManager.getMissingPermissions(type)
            return if (missing.isNotEmpty()) CapabilityStatus.PermissionRequired(missing)
            else CapabilityStatus.Available
        }

    override fun initialize() {
        logger.i("SmsProvider", "SMS capability provider initialized. Status=$status")
    }

    override fun release() {}

    fun getSmsCount(): Int {
        if (!status.isUsable) return 0
        return try {
            val uri = Uri.parse("content://sms/inbox")
            val cursor = context.contentResolver.query(uri, arrayOf("_id"), null, null, null)
            val count = cursor?.count ?: 0
            cursor?.close()
            count
        } catch (e: Exception) {
            logger.w("SmsProvider", "Failed to query SMS messages", e.message)
            0
        }
    }
}
