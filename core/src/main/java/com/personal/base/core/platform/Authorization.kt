package com.personal.base.core.platform

import com.personal.base.core.logging.PlatformLogger
import java.util.concurrent.ConcurrentHashMap

enum class AccessLevel {
    DENIED, READ_ONLY, FULL_CONTROL
}

/**
 * Fine-grained per-peer, per-capability authorization policy engine.
 */
class Authorization(
    private val logger: PlatformLogger
) {
    // Key: "$peerId:$capabilityName" -> AccessLevel
    private val policies = ConcurrentHashMap<String, AccessLevel>()

    fun setPolicy(peerId: String, capabilityName: String, level: AccessLevel) {
        val key = "$peerId:$capabilityName"
        policies[key] = level
        logger.i("Authorization", "Set policy for $key -> $level")
    }

    fun isAuthorized(peerId: String, capabilityName: String, requiredLevel: AccessLevel = AccessLevel.READ_ONLY): Boolean {
        val key = "$peerId:$capabilityName"
        val granted = policies[key] ?: AccessLevel.FULL_CONTROL // default trusted
        return granted >= requiredLevel
    }
}
