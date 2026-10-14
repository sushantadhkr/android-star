package com.personal.base.platform.capabilities

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.provider.Settings
import com.personal.base.core.capability.CapabilityProvider
import com.personal.base.core.capability.CapabilityStatus
import com.personal.base.core.capability.CapabilityType
import com.personal.base.core.logging.PlatformLogger

/**
 * What came back from a clipboard read.
 *
 * These are separate cases on purpose. Android 10 restricted clipboard reads to the app holding
 * window focus and the current input method; a background host is neither, so `getPrimaryClip()`
 * simply returns nothing. That looks identical to an empty clipboard from the outside, and telling
 * a caller "the clipboard is empty" when it is actually "you may not look" is precisely the kind
 * of quiet lie this platform is meant to avoid.
 */
sealed interface ClipboardRead {
    /** The clipboard holds text, and it is here. */
    data class Text(val value: String) : ClipboardRead

    /** The clipboard is readable and genuinely empty. */
    data object Empty : ClipboardRead

    /** Android declined. Not the same as empty, and never reported as empty. */
    data object Refused : ClipboardRead
}

class ClipboardCapabilityProvider(
    private val context: Context,
    private val logger: PlatformLogger
) : CapabilityProvider {

    override val type: CapabilityType = CapabilityType.CLIPBOARD

    private var clipboardManager: ClipboardManager? = null

    override val status: CapabilityStatus
        get() {
            if (clipboardManager == null) return CapabilityStatus.Unavailable("Clipboard service not found")
            // Writing is always allowed; reading is not, and saying "available" would imply both.
            return if (canReadClipboard()) {
                CapabilityStatus.Available
            } else {
                CapabilityStatus.Partial(
                    whatWorks = "Writing to the clipboard",
                    whatDoesNot = "Reading it. Android only allows the focused app or the current " +
                        "keyboard to read the clipboard, and the platform is neither."
                )
            }
        }

    override fun initialize() {
        clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        logger.i("ClipboardProvider", "Clipboard capability initialized (read access: ${canReadClipboard()})")
    }

    override fun release() {}

    /**
     * Whether a read can possibly succeed.
     *
     * Only two roles may read the clipboard: the app with window focus, and the current input
     * method. This is a background host with no window, so the input method is the only door — and
     * the platform declares one in its manifest, which is what this checks for.
     *
     * Measured on an Android 16 device: with the platform not set as the keyboard,
     * `hasPrimaryClip()` returns false and `getPrimaryClip()` returns null no matter what was
     * copied.
     */
    fun canReadClipboard(): Boolean = defaultInputMethod()?.contains(context.packageName) == true

    private fun defaultInputMethod(): String? =
        runCatching {
            Settings.Secure.getString(context.contentResolver, "default_input_method")
        }.getOrNull()

    fun read(): ClipboardRead {
        val cm = clipboardManager ?: return ClipboardRead.Empty

        if (!canReadClipboard()) {
            logger.i(
                "ClipboardProvider",
                "Clipboard read declined: Android only allows the focused app or the current " +
                    "keyboard to read it, and this is a background host"
            )
            return ClipboardRead.Refused
        }

        return try {
            if (!cm.hasPrimaryClip()) {
                ClipboardRead.Empty
            } else {
                val text = cm.primaryClip?.getItemAt(0)?.text?.toString()
                if (text.isNullOrEmpty()) ClipboardRead.Empty else ClipboardRead.Text(text)
            }
        } catch (e: Exception) {
            logger.w("ClipboardProvider", "Failed to read clipboard: ${e.message}")
            ClipboardRead.Refused
        }
    }

    /** Kept for callers that only care about the value. */
    fun getText(): String? = (read() as? ClipboardRead.Text)?.value

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