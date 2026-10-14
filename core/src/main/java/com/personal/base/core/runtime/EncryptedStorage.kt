package com.personal.base.core.runtime

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.personal.base.core.logging.PlatformLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AndroidKeyStore-backed AES-256 GCM encrypted file and binary storage with authenticated encryption.
 */
class EncryptedStorage(
    private val context: Context,
    private val logger: PlatformLogger
) {
    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private val filesDir = File(context.filesDir, "encrypted_store").apply { mkdirs() }

    init {
        ensureKey()
    }

    private fun ensureKey() {
        if (!keyStore.containsAlias(KEY_ALIAS)) {
            val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            val spec = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
            keyGenerator.init(spec)
            keyGenerator.generateKey()
            logger.i("EncryptedStorage", "Generated hardware-backed AES-256 key.")
        }
    }

    suspend fun writeEncrypted(fileName: String, plainBytes: ByteArray) = withContext(Dispatchers.IO) {
        val secretKey = keyStore.getKey(KEY_ALIAS, null) as SecretKey
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plainBytes)

        val target = File(filesDir, fileName)
        // Format: 12-byte IV + ciphertext
        target.outputStream().use { os ->
            os.write(iv)
            os.write(ciphertext)
        }
    }

    suspend fun readEncrypted(fileName: String): ByteArray? = withContext(Dispatchers.IO) {
        val target = File(filesDir, fileName)
        if (!target.exists()) return@withContext null

        val bytes = target.readBytes()
        if (bytes.size < 12) return@withContext null

        val iv = bytes.copyOfRange(0, 12)
        val ciphertext = bytes.copyOfRange(12, bytes.size)

        val secretKey = keyStore.getKey(KEY_ALIAS, null) as SecretKey
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
        cipher.doFinal(ciphertext)
    }

    companion object {
        private const val KEY_ALIAS = "android_star_storage_aes_key"
    }
}
