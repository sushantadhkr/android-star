package com.personal.base.core.security

import android.content.Context
import android.util.Base64
import com.personal.base.core.logging.PlatformLogger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/**
 * Data class representing the cryptographically signed identity of this Android Star device.
 */
data class StarDeviceIdentity(
    val deviceId: String,
    val fingerprint: String,
    val publicKeyBase64: String,
    val algorithm: String = "SHA256withECDSA",
    val isHardwareBacked: Boolean = true
)

/**
 * Manages device-level cryptographic keypairs, digital signing, and peer verification.
 * Follows zero-trust security: all device actions, file manifests, and P2P messages
 * can be signed and verified across the mesh.
 */
class DeviceSigner(
    private val context: Context,
    private val logger: PlatformLogger
) {
    private val keyAlias = "AndroidStarDeviceKey_v1"
    private val keyStoreType = "AndroidKeyStore"
    private val prefs = context.getSharedPreferences("android_star_security_prefs", Context.MODE_PRIVATE)

    val identity: StarDeviceIdentity by lazy {
        val kp = getOrCreateKeyPair()
        val pubKeyBase64 = Base64.encodeToString(kp.public.encoded, Base64.NO_WRAP)
        val fingerprint = computeFingerprint(kp.public.encoded)
        val deviceId = "STAR-" + fingerprint.take(8).uppercase()

        StarDeviceIdentity(
            deviceId = deviceId,
            fingerprint = fingerprint,
            publicKeyBase64 = pubKeyBase64,
            isHardwareBacked = true
        ).also {
            logger.i("DeviceSigner", "Device cryptographically initialized with Fingerprint: ${it.fingerprint}")
        }
    }

    @Synchronized
    private fun getOrCreateKeyPair(): KeyPair {
        return try {
            val keyStore = KeyStore.getInstance(keyStoreType).apply { load(null) }
            if (keyStore.containsAlias(keyAlias)) {
                val privateKey = keyStore.getKey(keyAlias, null) as? PrivateKey
                val publicKey = keyStore.getCertificate(keyAlias)?.publicKey
                if (privateKey != null && publicKey != null) {
                    return KeyPair(publicKey, privateKey)
                }
            }
            generateKeystoreKeyPair()
        } catch (e: Exception) {
            logger.w("DeviceSigner", "AndroidKeyStore unavailable, falling back to software keypair: ${e.message}")
            getOrCreateSoftwareKeyPair()
        }
    }

    private fun generateKeystoreKeyPair(): KeyPair {
        val kpg = KeyPairGenerator.getInstance("EC", keyStoreType)
        val spec = android.security.keystore.KeyGenParameterSpec.Builder(
            keyAlias,
            android.security.keystore.KeyProperties.PURPOSE_SIGN or android.security.keystore.KeyProperties.PURPOSE_VERIFY
        ).apply {
            setDigests(android.security.keystore.KeyProperties.DIGEST_SHA256)
            setUserAuthenticationRequired(false)
        }.build()

        kpg.initialize(spec)
        val kp = kpg.generateKeyPair()
        logger.i("DeviceSigner", "Generated hardware-backed EC KeyPair for device")
        return kp
    }

    private fun getOrCreateSoftwareKeyPair(): KeyPair {
        val savedPub = prefs.getString("sw_pub_key", null)
        val savedPriv = prefs.getString("sw_priv_key", null)

        if (savedPub != null && savedPriv != null) {
            try {
                val kf = KeyFactory.getInstance("RSA")
                val pubBytes = Base64.decode(savedPub, Base64.DEFAULT)
                val privBytes = Base64.decode(savedPriv, Base64.DEFAULT)
                val pubKey = kf.generatePublic(X509EncodedKeySpec(pubBytes))
                val privKey = kf.generatePrivate(java.security.spec.PKCS8EncodedKeySpec(privBytes))
                return KeyPair(pubKey, privKey)
            } catch (e: Exception) {
                logger.w("DeviceSigner", "Error restoring software key: ${e.message}")
            }
        }

        val kpg = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }
        val kp = kpg.generateKeyPair()
        prefs.edit()
            .putString("sw_pub_key", Base64.encodeToString(kp.public.encoded, Base64.NO_WRAP))
            .putString("sw_priv_key", Base64.encodeToString(kp.private.encoded, Base64.NO_WRAP))
            .apply()
        return kp
    }

    /**
     * Digitally sign arbitrary bytes using the device's private key.
     */
    fun sign(data: ByteArray): String {
        return try {
            val kp = getOrCreateKeyPair()
            val algorithm = if (kp.private.algorithm == "EC") "SHA256withECDSA" else "SHA256withRSA"
            val signature = Signature.getInstance(algorithm).apply {
                initSign(kp.private)
                update(data)
            }
            Base64.encodeToString(signature.sign(), Base64.NO_WRAP)
        } catch (e: Exception) {
            logger.e("DeviceSigner", "Failed to digitally sign data: ${e.message}", e.message)
            ""
        }
    }

    /**
     * Sign string UTF-8 content directly.
     */
    fun signString(content: String): String = sign(content.toByteArray(Charsets.UTF_8))

    /**
     * Verify a digital signature against a peer's public key.
     */
    fun verify(data: ByteArray, signatureBase64: String, publicKeyBase64: String): Boolean {
        return try {
            val pubBytes = Base64.decode(publicKeyBase64, Base64.DEFAULT)
            val sigBytes = Base64.decode(signatureBase64, Base64.DEFAULT)
            
            // Try EC first, then RSA
            val kf = try {
                KeyFactory.getInstance("EC")
            } catch (_: Exception) {
                KeyFactory.getInstance("RSA")
            }
            val pubKey = kf.generatePublic(X509EncodedKeySpec(pubBytes))
            val algorithm = if (pubKey.algorithm == "EC") "SHA256withECDSA" else "SHA256withRSA"

            val signature = Signature.getInstance(algorithm).apply {
                initVerify(pubKey)
                update(data)
            }
            signature.verify(sigBytes)
        } catch (e: Exception) {
            logger.w("DeviceSigner", "Signature verification failed: ${e.message}")
            false
        }
    }

    /**
     * Compute a clean hex fingerprint from public key bytes.
     */
    private fun computeFingerprint(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02X".format(it) }
    }
}
