package com.personal.base.platform.capabilities

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.SharedPreferences
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import com.personal.base.core.capability.CapabilityProvider
import com.personal.base.core.capability.CapabilityStatus
import com.personal.base.core.capability.CapabilityType
import com.personal.base.core.logging.PlatformLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executor

/**
 * Base platform capability managing hardware biometric authentication,
 * device screen lock state, and secure platform access gates.
 *
 * Uses genuine native device security (Biometric Strong + Device Credential PIN/Pattern).
 */
class BiometricAuthCapabilityProvider(
    private val context: Context,
    private val logger: PlatformLogger
) : CapabilityProvider {

    override val type: CapabilityType = CapabilityType.BIOMETRIC

    private val prefs: SharedPreferences =
        context.getSharedPreferences("android_star_security_prefs", Context.MODE_PRIVATE)

    private val _isLockEnabled = MutableStateFlow(prefs.getBoolean(KEY_LOCK_ENABLED, false))
    val isLockEnabled: StateFlow<Boolean> = _isLockEnabled.asStateFlow()

    private val _isAppLocked = MutableStateFlow(_isLockEnabled.value)
    val isAppLocked: StateFlow<Boolean> = _isAppLocked.asStateFlow()

    private var cancellationSignal: CancellationSignal? = null

    val isDeviceSecure: Boolean
        get() {
            val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
            return keyguardManager?.isDeviceSecure == true
        }

    val isBiometricAvailable: Boolean
        get() {
            val biometricManager = context.getSystemService(Context.BIOMETRIC_SERVICE) as? BiometricManager
            return if (biometricManager != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val res = biometricManager.canAuthenticate(
                    BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL
                )
                res == BiometricManager.BIOMETRIC_SUCCESS
            } else {
                isDeviceSecure
            }
        }

    val hasHardware: Boolean
        get() {
            val biometricManager = context.getSystemService(Context.BIOMETRIC_SERVICE) as? BiometricManager
            return if (biometricManager != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) !=
                        BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE
            } else {
                true
            }
        }

    val statusDescription: String
        get() = when {
            isBiometricAvailable -> "Biometric / Device Security Ready"
            isDeviceSecure -> "Device PIN / Pattern Security Ready"
            else -> "No Device Lock Screen Configured"
        }

    override fun checkStatus(): CapabilityStatus {
        return if (isBiometricAvailable || isDeviceSecure) {
            CapabilityStatus.Available
        } else {
            CapabilityStatus.Unavailable("Device lock screen / biometric not enrolled")
        }
    }

    fun setLockEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_LOCK_ENABLED, enabled).apply()
        _isLockEnabled.value = enabled
        if (!enabled) {
            _isAppLocked.value = false
        }
        logger.i("BiometricAuth", "Biometric platform lock enabled = $enabled")
    }

    fun lockNow() {
        if (_isLockEnabled.value) {
            _isAppLocked.value = true
            logger.i("BiometricAuth", "Device platform locked by user request.")
        }
    }

    fun unlockApp() {
        _isAppLocked.value = false
        logger.i("BiometricAuth", "Device unlocked successfully.")
    }

    /**
     * Confirms the person in front of the phone is its owner, without touching the app lock.
     *
     * Separate from [authenticate] because that one calls [unlockApp] as part of succeeding, which
     * is right for getting into the app and wrong for guarding one secret inside it. This never
     * changes any lock state; it only answers "was that you".
     *
     * The one rule it keeps: **it never succeeds without a prompt the user actually answered.** On
     * a device with no screen lock there is nothing to ask, so it reports that rather than waving
     * the request through — a guard that opens on old hardware is worse than no guard, because it
     * looks like one.
     */
    fun confirmIdentity(
        activity: Activity,
        title: String,
        subtitle: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        if (!isDeviceSecure) {
            onError(
                "This phone has no screen lock, so there is nothing to verify you against. " +
                    "Set a PIN, pattern or password in Android settings first."
            )
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            // Pre-P there is no BiometricPrompt and [authenticate] would have waved this through
            // without asking. Refusing is the honest answer for a secret guard.
            onError(
                "This version of Android cannot verify who is asking, so the token stays hidden."
            )
            return
        }
        authenticate(activity, title, subtitle, onSuccess = onSuccess, onError = onError)
    }

    /**
     * Trigger native Android fingerprint / biometric or device credential (PIN/pattern/password) prompt.
     */
    fun authenticate(
        activity: Activity,
        title: String = "Unlock Android Star",
        subtitle: String = "Verify fingerprint or device PIN/pattern to access platform",
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                cancellationSignal?.cancel()
                val signal = CancellationSignal()
                cancellationSignal = signal

                val executor: Executor = activity.mainExecutor
                val callback = object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
                        unlockApp()
                        onSuccess()
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                        val msg = errString?.toString() ?: "Authentication error code $errorCode"
                        logger.w("BiometricAuth", "Authentication error: $msg")
                        onError(msg)
                    }

                    override fun onAuthenticationFailed() {
                        logger.w("BiometricAuth", "Biometric authentication not recognized")
                        onError("Biometric not recognized. Try again or use device PIN.")
                    }
                }

                val promptBuilder = BiometricPrompt.Builder(activity)
                    .setTitle(title)
                    .setSubtitle(subtitle)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    promptBuilder.setAllowedAuthenticators(
                        BiometricManager.Authenticators.BIOMETRIC_STRONG or
                        BiometricManager.Authenticators.DEVICE_CREDENTIAL
                    )
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    @Suppress("DEPRECATION")
                    promptBuilder.setDeviceCredentialAllowed(true)
                } else {
                    promptBuilder.setNegativeButton("Cancel", executor) { _, _ ->
                        onError("Authentication cancelled by user")
                    }
                }

                val prompt = promptBuilder.build()
                prompt.authenticate(signal, executor, callback)
            } catch (e: Exception) {
                logger.e("BiometricAuth", "BiometricPrompt invocation failed: ${e.message}", e.message)
                onError("Failed to launch security prompt: ${e.message}")
            }
        } else {
            unlockApp()
            onSuccess()
        }
    }

    override fun release() {
        cancellationSignal?.cancel()
        cancellationSignal = null
    }

    companion object {
        private const val KEY_LOCK_ENABLED = "biometric_lock_enabled_v1"
    }
}
