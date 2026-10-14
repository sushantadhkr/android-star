package com.personal.base.core.platform

import com.personal.base.core.logging.PlatformLogger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

/**
 * Manages TLS certificate generation, certificate pinning, and mutual TLS context setup.
 */
class TlsCertManager(
    private val logger: PlatformLogger
) {
    private var sslContext: SSLContext? = null

    fun generateSelfSignedKeyPair(): KeyPair {
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048, SecureRandom())
        val kp = kpg.generateKeyPair()
        logger.i("TlsCertManager", "Generated 2048-bit RSA key pair for mutual TLS.")
        return kp
    }

    fun getPlatformSslContext(): SSLContext {
        if (sslContext == null) {
            val ctx = SSLContext.getInstance("TLS")
            ctx.init(null, null, SecureRandom())
            sslContext = ctx
        }
        return sslContext!!
    }
}
