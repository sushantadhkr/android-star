package com.personal.base.platform.capabilities

import android.content.Context
import android.content.Intent
import com.personal.base.core.capability.CapabilityProvider
import com.personal.base.core.capability.CapabilityStatus
import com.personal.base.core.capability.CapabilityType
import com.personal.base.core.logging.PlatformLogger

class SharingCapabilityProvider(
    private val context: Context,
    private val logger: PlatformLogger
) : CapabilityProvider {

    override val type: CapabilityType = CapabilityType.SHARING

    override val status: CapabilityStatus = CapabilityStatus.Available

    override fun initialize() {
        logger.i("SharingProvider", "Sharing capability initialized")
    }

    override fun release() {}

    fun shareText(text: String, title: String = "Share via Personal Base") {
        try {
            val sendIntent = Intent().apply {
                action = Intent.ACTION_SEND
                putExtra(Intent.EXTRA_TEXT, text)
                type = "text/plain"
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            val shareIntent = Intent.createChooser(sendIntent, title).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(shareIntent)
            logger.i("SharingProvider", "Launched share sheet with title: '$title'")
        } catch (e: Exception) {
            logger.e("SharingProvider", "Failed to launch share sheet", e.message)
        }
    }
}
