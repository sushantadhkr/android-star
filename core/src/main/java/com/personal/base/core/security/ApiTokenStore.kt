package com.personal.base.core.security

import android.content.Context
import android.content.SharedPreferences
import java.security.SecureRandom

/**
 * The token that protects the platform's HTTP surface.
 *
 * Extracted as an interface so authentication rules can be tested without Android.
 */
interface ApiTokenStoreContract {
    fun token(): String
    fun rotate(): String
    fun matches(candidate: String?): Boolean
}

/**
 * The bearer token that protects the platform's HTTP surface.
 *
 * The server has write endpoints — it can post notifications, run capabilities and change what the
 * platform is allowed to do. Without a token, anything that can reach the port can drive the phone.
 *
 * The token is generated on first use with a cryptographically secure source, stored locally, and
 * can be rotated at any time. Rotation immediately invalidates the old one, which is the only way to
 * recover if it leaks.
 */
class ApiTokenStore(context: Context) : ApiTokenStoreContract {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val random = SecureRandom()

    /** The current token, generating one on first access. */
    override fun token(): String {
        prefs.getString(KEY_TOKEN, null)?.let { return it }
        val generated = generate()
        prefs.edit().putString(KEY_TOKEN, generated).apply()
        return generated
    }

    /** Replaces the token. The previous one stops working immediately. */
    override fun rotate(): String {
        val generated = generate()
        prefs.edit().putString(KEY_TOKEN, generated).apply()
        return generated
    }

    /** Constant-time comparison, so a wrong token cannot be guessed by timing. */
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

    /**
     * 32 bytes of entropy, URL-safe so it survives being pasted into a shell command.
     */
    private fun generate(): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val PREFS_NAME = "personal_base_api_auth"
        private const val KEY_TOKEN = "api_bearer_token"
    }
}
