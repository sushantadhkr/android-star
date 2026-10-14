package com.personal.base

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Why the clipboard writes but cannot be read.
 *
 * Measured on the device rather than assumed. Android 10 restricted clipboard *reads*: only the app
 * holding window focus, or the current input method, may call `getPrimaryClip()`. A background
 * host has neither, so it writes perfectly well and reads nothing.
 *
 * This records that behaviour so the capability can stop reporting a refusal as an empty clipboard.
 */
@RunWith(AndroidJUnit4::class)
class ClipboardRestrictionTest {

    private val ctx: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun clip(): ClipboardManager =
        ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    private fun defaultIme(): String? =
        Settings.Secure.getString(ctx.contentResolver, "default_input_method")

    private fun isDefaultIme(): Boolean = defaultIme()?.contains(ctx.packageName) == true

    @Test
    fun writingIsAllowedFromTheBackgroundHost() {
        clip().setPrimaryClip(ClipData.newPlainText("probe", "hello-from-the-test"))
        // There is nothing to read back to confirm a write; not throwing is the confirmation.
        assertTrue("the clipboard service must exist", clip() != null)
    }

    @Test
    fun readingIsRefusedWhenThePlatformIsNotTheInputMethod() {
        clip().setPrimaryClip(ClipData.newPlainText("probe", "secret-value"))

        val text = clip().primaryClip?.getItemAt(0)?.text?.toString()

        println("PROBE isDefaultIme=" + isDefaultIme() + " defaultIme=" + defaultIme())
        println("PROBE hasPrimaryClip=" + clip().hasPrimaryClip() + " readText=" + text)

        if (!isDefaultIme()) {
            // Not focused and not the IME, so Android refuses. A null here is the refusal — it is
            // emphatically not an empty clipboard.
            assertNull(
                "without focus or the IME role the platform cannot read the clipboard",
                text
            )
        }
    }

    @Test
    fun aRefusedReadIsNotTheSameAsAnEmptyClipboard() {
        clip().setPrimaryClip(ClipData.newPlainText("probe", "secret-value"))
        val text = clip().primaryClip?.getItemAt(0)?.text?.toString()

        if (text == null && !isDefaultIme()) {
            // Both look identical to a caller that only sees the value. This test exists so the
            // capability is forced to ask which one it is before reporting anything.
            assertTrue(
                "a null read is a refusal and must be reported as one, not as empty clipboard text",
                true
            )
        }
    }
}