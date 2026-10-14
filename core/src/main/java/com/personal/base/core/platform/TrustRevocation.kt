package com.personal.base.core.platform

import com.personal.base.core.logging.PlatformLogger
import java.util.concurrent.ConcurrentHashMap

/**
 * Device blocklist and certificate/key revocation list (CRL).
 */
class TrustRevocation(
    private val logger: PlatformLogger
) {
    private val revokedPeers = ConcurrentHashMap.newKeySet<String>()

    fun revokePeer(peerId: String) {
        revokedPeers.add(peerId)
        logger.w("TrustRevocation", "REVOKED trust for peer: $peerId")
    }

    fun unrevokePeer(peerId: String) {
        revokedPeers.remove(peerId)
        logger.i("TrustRevocation", "Un-revoked peer: $peerId")
    }

    fun isRevoked(peerId: String): Boolean = revokedPeers.contains(peerId)
}
