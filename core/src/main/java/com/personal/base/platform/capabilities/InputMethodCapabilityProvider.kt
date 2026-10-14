package com.personal.base.platform.capabilities

import android.inputmethodservice.InputMethodService
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

    override fun checkStatus(): CapabilityStatus = CapabilityStatus.Available
    override fun release() {}
}
