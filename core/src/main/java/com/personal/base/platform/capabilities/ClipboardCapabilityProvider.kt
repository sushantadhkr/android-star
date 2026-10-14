package com.personal.base.platform.capabilities

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.personal.base.core.capability.CapabilityProvider
import com.personal.base.core.capability.CapabilityStatus
import com.personal.base.core.capability.CapabilityType
import com.personal.base.core.logging.PlatformLogger

class ClipboardCapabilityProvider(
    private val context: Context,
    private val logger: PlatformLogger
) : CapabilityProvider {

    override val type: CapabilityType = CapabilityType.CLIPBOARD

    private var clipboardManager: ClipboardManager? = null

    override val status: CapabilityStatus
        get() = if (clipboardManager != null) CapabilityStatus.Available
                else CapabilityStatus.Unavailable("Clipboard service not found")

    override fun initialize() {
        clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        logger.i("ClipboardProvider", "Clipboard capability initialized")
    }

    override fun release() {}

    fun getText(): String? {
        val cm = clipboardManager ?: return null
        return try {
            if (cm.hasPrimaryClip()) {
                val item = cm.primaryClip?.getItemAt(0)
                item?.text?.toString()
            } else null
        } catch (e: Exception) {
            logger.w("ClipboardProvider", "Failed to read clipboard", e.message)
            null
        }
    }

    fun setText(text: String, label: String = "Platform") {
        val cm = clipboardManager ?: return
        try {
            val clip = ClipData.newPlainText(label, text)
            cm.setPrimaryClip(clip)
            logger.i("ClipboardProvider", "Copied text to clipboard: '${text.take(30)}...'")
        } catch (e: Exception) {
            logger.e("ClipboardProvider", "Failed to write clipboard", e.message)
        }
    }
}
