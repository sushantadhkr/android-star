package com.personal.base.platform.capabilities

import android.content.Context
import android.inputmethodservice.InputMethodService
import android.provider.Settings
import android.view.inputmethod.InputConnection
import com.personal.base.core.capability.CapabilityProvider
import com.personal.base.core.capability.CapabilityStatus
import com.personal.base.core.capability.CapabilityType
import com.personal.base.core.logging.PlatformLogger

class PlatformInputMethodService : InputMethodService() {
    companion object {
        var activeInputConnection: InputConnection? = null
    }

    override fun onStartInputView(info: android.view.inputmethod.EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        activeInputConnection = currentInputConnection
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        activeInputConnection = null
    }
}

/**
 * 43. InputMethodCapabilityProvider: remote IME service for typing text from desktop into any active app.
 */
class InputMethodCapabilityProvider(
    private val context: Context,
    private val logger: PlatformLogger
) : CapabilityProvider {

    override val type: CapabilityType = CapabilityType.INPUT_METHOD

    fun typeText(text: String): Boolean {
        val conn = PlatformInputMethodService.activeInputConnection
        return if (conn != null) {
            conn.commitText(text, 1)
            logger.i("InputMethod", "Committed remote text: '$text'")
            true
        } else {
            logger.w("InputMethod", "No active IME input connection")
            false
        }
    }

    /**
     * Whether Android Star is the keyboard the user is currently typing with.
     *
     * The platform's own question to answer: typing into another app only works from the active
     * input method, and the setting is read here rather than by the caller reaching in for a
     * Context it should not have.
     */
    fun isActiveKeyboard(): Boolean =
        activeKeyboard()?.contains(context.packageName) == true

    /** The component name of whatever is the active keyboard, for showing in a status. */
    fun activeKeyboard(): String? =
        Settings.Secure.getString(context.contentResolver, "default_input_method")

    override fun checkStatus(): CapabilityStatus = CapabilityStatus.Available
    override fun release() {}
}
