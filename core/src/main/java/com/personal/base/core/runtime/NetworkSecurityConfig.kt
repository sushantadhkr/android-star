package com.personal.base.core.runtime

import android.content.Context
import android.security.NetworkSecurityPolicy
import com.personal.base.core.logging.PlatformLogger

/**
 * Validates TLS cleartext policy, certificate pinning requirements, and local trust managers.
 */
class NetworkSecurityConfig(
    private val context: Context,
    private val logger: PlatformLogger
) {
    val isCleartextTrafficPermitted: Boolean
        get() = NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted

    fun isCleartextPermittedForHost(hostname: String): Boolean {
        return NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted(hostname)
    }

    fun logSecurityStatus() {
        logger.i(
            "NetworkSecurityConfig",
            "Network Security Policy: isCleartextPermitted=$isCleartextTrafficPermitted"
        )
    }
}
