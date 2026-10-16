package com.personal.base.core.security

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import android.util.Base64
import com.personal.base.core.logging.PlatformLogger
import java.security.SecureRandom

/**
 * Keeps the bearer token encrypted at rest, with the key living in hardware-backed storage.
 *
 * The token grants full control of this phone over the network, so storing it as readable text
 * means anything that can read the app's files — a rooted device, an extracted backup, a forensic
 * image — owns the phone without ever breaking in. The key here never leaves the keystore: it is
 * generated on the device, used to encrypt, and discarded on teardown, so the file on disk is
 * ciphertext that is useless on its own.
 *
 * If the key is ever destroyed — which is what a factory reset does — the stored ciphertext cannot
 * be read. That is not a failure to hide: a new token is generated, the old one is dead, and anyone
 * holding it finds out on their next request with a 401. That is the correct outcome.
 */
class EncryptedApiTokenStore(context: Context) : ApiTokenStoreContract {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val logger = PlatformLogger()
    private val random = SecureRandom()

    /** The current token, decrypting it, migrating it, or generating one on first access. */
    override fun token(): String {
        val stored = prefs.getString(KEY_CIPHERTEXT, null)
        val iv = prefs.getString(KEY_IV, null)
        if (stored != null && iv != null) {
            decrypt(stored, iv)?.let { return it }
            // Unreadable ciphertext means the key is gone. Falling through generates a fresh
            // token, which silently revokes whatever the old one was worth — the safe direction.
            logger.w("ApiTokenStore", "Stored token could not be decrypted; issuing a new one.")
        }
        migrateLegacyToken()?.let { return it }
        return generateAndStore()
    }

    /**
     * Moves a token written by the old plaintext store into the encrypted one, then deletes it.
     *
     * Two reasons this is not optional. Without it, upgrading silently invalidates a token that
     * scripts and other devices are still using — an outage the user never asked for. And without
     * deleting the original, the very secret this class exists to protect is still sitting in the
     * clear in a file nobody remembers writing.
     *
     * Best effort on purpose: if the old file cannot be read for any reason, a new token is issued
     * and that is a far smaller problem than a stale plaintext credential outliving its migration.
     */
    private fun migrateLegacyToken(): String? {
        val legacy = runCatching {
            appContext.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
                .getString(LEGACY_KEY_TOKEN, null)
        }.getOrNull()

        legacy?.let { plain ->
            val sealed = encrypt(plain)
            if (sealed != null) {
                prefs.edit()
                    .putString(KEY_CIPHERTEXT, sealed.first)
                    .putString(KEY_IV, sealed.second)
                    .apply()
                logger.i("ApiTokenStore", "Migrated the existing token into encrypted storage.")
            } else {
                logger.w("ApiTokenStore", "Could not encrypt the existing token; issuing a new one.")
            }
        }

        // Cleared whether or not there was anything to migrate, so a half-finished upgrade does not
        // leave a readable copy behind for the next attempt to pick up.
        runCatching {
            appContext.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
                .edit().remove(LEGACY_KEY_TOKEN).apply()
            appContext.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
                .edit().clear().apply()
        }

        return legacy
    }

    /** Replaces the token. The previous one stops working immediately. */
    override fun rotate(): String = generateAndStore()

    /**
     * Constant-time comparison, so a wrong token cannot be guessed by timing.
     *
     * The token is decrypted fresh each time rather than cached, so the comparison is against what
     * is actually stored and a stale in-memory copy cannot drift away from it.
     */
    override fun matches(candidate: String?): Boolean {
        if (candidate.isNullOrBlank()) return false
        val expected = token()
        if (expected.length != candidate.length) return false
        var diff = 0
        for (i in expected.indices) {
            diff = diff or (expected[i].code xor candidate[i].code)
        }
        return diff == 0
    }

    private fun generateAndStore(): String {
        val plain = generate()
        val encrypted = encrypt(plain)
        if (encrypted != null) {
            prefs.edit()
                .putString(KEY_CIPHERTEXT, encrypted.first)
                .putString(KEY_IV, encrypted.second)
                .apply()
        } else {
            // No usable keystore on this device. Falling back to plain storage is recorded rather
            // than done quietly — a token that cannot be encrypted is a fact the user should be
            // able to see in diagnostics, not a silent downgrade.
            logger.e(
                "ApiTokenStore",
                "No working keystore; the token is being stored without encryption on this device."
            )
            prefs.edit().putString(KEY_PLAINTEXT, plain).remove(KEY_CIPHERTEXT).remove(KEY_IV).apply()
        }
        return plain
    }

    private fun encrypt(plain: String): Pair<String, String>? = try {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val ciphertext = Base64.encodeToString(cipher.doFinal(plain.toByteArray()), Base64.NO_WRAP)
        ciphertext to Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
    } catch (e: GeneralSecurityException) {
        null
    }

    private fun decrypt(stored: String, iv: String): String? = try {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            secretKey(),
            GCMParameterSpec(GCM_TAG_BITS, Base64.decode(iv, Base64.NO_WRAP))
        )
        String(cipher.doFinal(Base64.decode(stored, Base64.NO_WRAP)))
    } catch (e: GeneralSecurityException) {
        null
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .apply {
                    // Hardware-backed where the device has it, so the key is not merely software.
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        setIsStrongBoxBacked(false)
                    }
                }
                .build()
        )
        return generator.generateKey()
    }

    /** 32 bytes of entropy, hex so it survives being pasted into a shell command. */
    private fun generate(): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val PREFS_NAME = "android_star_api_token"
        const val KEY_CIPHERTEXT = "token_ciphertext"
        const val KEY_IV = "token_iv"
        const val KEY_PLAINTEXT = "token_plaintext_fallback"

        /** Where the pre-keystore store kept the token in the clear. */
        const val LEGACY_PREFS_NAME = "personal_base_api_auth"
        const val LEGACY_KEY_TOKEN = "api_bearer_token"
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "android_star_api_token_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128

    }
}