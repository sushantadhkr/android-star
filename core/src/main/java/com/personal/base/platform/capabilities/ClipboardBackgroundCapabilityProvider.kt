package com.personal.base.platform.capabilities

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.personal.base.core.capability.CapabilityProvider
import com.personal.base.core.capability.CapabilityStatus
import com.personal.base.core.capability.CapabilityType
import com.personal.base.core.logging.PlatformLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 44. ClipboardBackgroundCapabilityProvider: foreground-assisted clipboard read workaround for Android 10+.
 */
class ClipboardBackgroundCapabilityProvider(
    private val context: Context,
    private val logger: PlatformLogger
) : CapabilityProvider {

    override val type: CapabilityType = CapabilityType.CLIPBOARD_BACKGROUND

    private val clipboardManager =
        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    private val _cachedClipboardText = MutableStateFlow<String?>(null)
    val cachedClipboardText: StateFlow<String?> = _cachedClipboardText.asStateFlow()

    init {
        try {
            clipboardManager.addPrimaryClipChangedListener {
                val clip = clipboardManager.primaryClip
                if (clip != null && clip.itemCount > 0) {
                    val text = clip.getItemAt(0).text?.toString()
                    _cachedClipboardText.value = text
                    logger.i("ClipboardBackground", "Cached updated clipboard content.")
                }
            }
        } catch (e: Exception) {
            logger.w("ClipboardBackground", "Could not attach clip change listener: ${e.message}")
        }
    }

    fun setClipboard(text: String, label: String = "Android Star") {
        val clip = ClipData.newPlainText(label, text)
        clipboardManager.setPrimaryClip(clip)
        _cachedClipboardText.value = text
        logger.i("ClipboardBackground", "Set primary clip text.")
    }

    override fun checkStatus(): CapabilityStatus = CapabilityStatus.Available
    override fun release() {}
}
