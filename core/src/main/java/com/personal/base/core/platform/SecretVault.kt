package com.personal.base.core.platform

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.personal.base.core.logging.PlatformLogger
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Keystore-backed SecretVault storing sensitive platform keys, auth tokens, and peer secrets.
 */
class SecretVault(
    private val logger: PlatformLogger
) {
    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    init {
        ensureMasterKey()
    }

    private fun ensureMasterKey() {
        if (!keyStore.containsAlias(MASTER_KEY_ALIAS)) {
            val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            val spec = KeyGenParameterSpec.Builder(
                MASTER_KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
            keyGenerator.init(spec)
            keyGenerator.generateKey()
            logger.i("SecretVault", "Initialized hardware master secret key.")
        }
    }

    fun encryptSecret(plainText: String): Pair<ByteArray, ByteArray> {
        val secretKey = keyStore.getKey(MASTER_KEY_ALIAS, null) as SecretKey
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val iv = cipher.iv
        val encrypted = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        return Pair(iv, encrypted)
    }

    fun decryptSecret(iv: ByteArray, ciphertext: ByteArray): String {
        val secretKey = keyStore.getKey(MASTER_KEY_ALIAS, null) as SecretKey
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
        val decryptedBytes = cipher.doFinal(ciphertext)
        return String(decryptedBytes, Charsets.UTF_8)
    }

    companion object {
        private const val MASTER_KEY_ALIAS = "android_star_vault_master"
    }
}
