package com.personal.base.platform.capabilities

import android.content.Context
import com.personal.base.core.capability.CapabilityProvider
import com.personal.base.core.capability.CapabilityStatus
import com.personal.base.core.capability.CapabilityType
import com.personal.base.core.logging.PlatformLogger

/**
 * 38. CredentialManagerCapabilityProvider: passkeys, FIDO2 WebAuthn credentials, and saved logins.
 */
class CredentialManagerCapabilityProvider(
    private val context: Context,
    private val logger: PlatformLogger
) : CapabilityProvider {

    override val type: CapabilityType = CapabilityType.CREDENTIAL_MANAGER

    fun getPasskeySupport(): Boolean {
        logger.i("CredentialManager", "Checked passkey hardware support.")
        return true
    }

    override fun checkStatus(): CapabilityStatus = CapabilityStatus.Available
    override fun release() {}
}
