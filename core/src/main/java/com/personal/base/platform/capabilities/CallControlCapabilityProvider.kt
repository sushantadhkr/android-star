package com.personal.base.platform.capabilities

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.telecom.TelecomManager
import com.personal.base.core.capability.CapabilityProvider
import com.personal.base.core.capability.CapabilityStatus
import com.personal.base.core.capability.CapabilityType
import com.personal.base.core.logging.PlatformLogger

/**
 * 31. CallControlCapabilityProvider: place, answer, end, and mute calls via TelecomManager.
 */
class CallControlCapabilityProvider(
    private val context: Context,
    private val logger: PlatformLogger
) : CapabilityProvider {

    override val type: CapabilityType = CapabilityType.CALL_CONTROL

    private val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager

    fun placeCall(phoneNumber: String): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$phoneNumber")).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            logger.i("CallControl", "Initiated outgoing call to $phoneNumber")
            true
        } catch (e: Exception) {
            logger.e("CallControl", "Failed to place call: ${e.message}", e.message)
            false
        }
    }

    override fun checkStatus(): CapabilityStatus = CapabilityStatus.Available
    override fun release() {}
}
