package com.personal.base.core.platform

import com.personal.base.core.logging.PlatformLogger
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class SessionToken(
    val token: String,
    val peerId: String,
    val createdAt: Long = System.currentTimeMillis(),
    val expiresAt: Long = System.currentTimeMillis() + 24 * 60 * 60 * 1000L
)

/**
 * Authentication manager for session token creation, validation, refresh, and revocation.
 */
class Authentication(
    private val logger: PlatformLogger
) {
    private val activeSessions = ConcurrentHashMap<String, SessionToken>()

    fun createSession(peerId: String): SessionToken {
        val tokenStr = UUID.randomUUID().toString().replace("-", "") + generateEntropy()
        val session = SessionToken(tokenStr, peerId)
        activeSessions[tokenStr] = session
        logger.i("Authentication", "Created session token for peer: $peerId")
        return session
    }

    fun validateSession(tokenStr: String): Boolean {
        val session = activeSessions[tokenStr] ?: return false
        if (System.currentTimeMillis() > session.expiresAt) {
            activeSessions.remove(tokenStr)
            logger.w("Authentication", "Session token expired for peer: ${session.peerId}")
            return false
        }
        return true
    }

    fun revokeSession(tokenStr: String) {
        val removed = activeSessions.remove(tokenStr)
        if (removed != null) {
            logger.i("Authentication", "Revoked session for peer: ${removed.peerId}")
        }
    }

    private fun generateEntropy(): String {
        val bytes = ByteArray(8)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
