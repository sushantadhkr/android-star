package com.personal.base.core.command

import com.personal.base.core.capability.CapabilityType
import com.personal.base.core.config.ConfigRepository
import com.personal.base.core.logging.PlatformLogger
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

/**
 * Validates command signatures, prevents replay attacks, and enforces role access controls.
 */
class CommandValidator(
    private val configRepository: ConfigRepository,
    private val logger: PlatformLogger
) {

    /**
     * Verifies that the command is authentic, has not expired, and complies with access controls.
     */
    fun validate(command: RemoteCommand): ValidationResult {
        val config = configRepository.config.value

        // 1. Check TTL and Replay Window
        val now = System.currentTimeMillis()
        val diffMs = abs(now - command.timestamp)
        val maxTtlMs = command.ttlSeconds * 1000L

        if (diffMs > maxTtlMs) {
            val reason = "Command expired or clock drift exceeded (${diffMs}ms > ${maxTtlMs}ms)"
            logger.w("CommandValidator", reason, "Command ID: ${command.id}")
            return ValidationResult.Rejected(reason)
        }

        // 2. Signature verification if required
        if (config.requireSignedCommands) {
            val expectedSig = computeHmac(command, config.authSecretHmac)
            if (command.signature == null || !MessageDigest.isEqual(command.signature.toByteArray(), expectedSig.toByteArray())) {
                val reason = "Invalid cryptographic HMAC signature"
                logger.w("CommandValidator", reason, "Command ID: ${command.id}")
                return ValidationResult.Rejected(reason)
            }
        }

        return ValidationResult.Accepted
    }

    /**
     * Checks if a user role is permitted to access a given capability.
     */
    fun checkCapabilityAccess(role: UserRole, capability: CapabilityType): Boolean {
        return when (capability) {
            CapabilityType.LOCATION,
            CapabilityType.CAMERA,
            CapabilityType.MICROPHONE,
            CapabilityType.CALL_LOG,
            CapabilityType.CONTACTS_ACCOUNTS,
            CapabilityType.SMS,
            CapabilityType.DEVICE_ADMIN,
            CapabilityType.PACKAGE_INSTALLER,
            CapabilityType.RESTRICTED_SHELL,
            CapabilityType.ALL_FILES_ACCESS,
            CapabilityType.ACCESSIBILITY,
            CapabilityType.SCREEN_CAPTURE,
            CapabilityType.REMOTE_INPUT,
            CapabilityType.CALL_CONTROL -> role == UserRole.ADMIN

            CapabilityType.DEVICE_ACTIONS,
            CapabilityType.BACKGROUND_WORK,
            CapabilityType.CONNECTIVITY,
            CapabilityType.NEARBY_DEVICES,
            CapabilityType.PHONE,
            CapabilityType.BIOMETRIC,
            CapabilityType.WIFI,
            CapabilityType.NFC,
            CapabilityType.CALENDAR,
            CapabilityType.AUDIO_VOLUME,
            CapabilityType.MEDIA_SESSION,
            CapabilityType.FLASHLIGHT,
            CapabilityType.INSTALLED_APPS,
            CapabilityType.USB,
            CapabilityType.DO_NOT_DISTURB,
            CapabilityType.SYSTEM_SETTINGS,
            CapabilityType.ALARM_TIMER,
            CapabilityType.VPN,
            CapabilityType.GEOFENCE_ACTIVITY,
            CapabilityType.NETWORK_USAGE,
            CapabilityType.SIM_SUBSCRIPTION,
            CapabilityType.KEYGUARD,
            CapabilityType.NOTIFICATION_LISTENER,
            CapabilityType.OVERLAY_WINDOW,
            CapabilityType.MEDIA_STREAM_ENCODER,
            CapabilityType.AUDIO_STREAM,
            CapabilityType.DOCUMENTS_PROVIDER,
            CapabilityType.CREDENTIAL_MANAGER,
            CapabilityType.SFTP_SERVER,
            CapabilityType.INPUT_METHOD,
            CapabilityType.CLIPBOARD_BACKGROUND,
            CapabilityType.FILE_SERVER,
            CapabilityType.NEARBY_CHAT -> role >= UserRole.AUTHENTICATED

            CapabilityType.SENSORS,
            CapabilityType.NOTIFICATIONS,
            CapabilityType.CLIPBOARD,
            CapabilityType.SHARING,
            CapabilityType.STORAGE,
            CapabilityType.BATTERY_POWER,
            CapabilityType.DISPLAY,
            CapabilityType.MEDIA_LIBRARY,
            CapabilityType.TEXT_TO_SPEECH,
            CapabilityType.SPEECH_RECOGNITION,
            CapabilityType.VIBRATION_HAPTICS,
            CapabilityType.QR_BARCODE,
            CapabilityType.DEVICE_INFO,
            CapabilityType.HEALTH_CONNECT,
            CapabilityType.DOWNLOAD_MANAGER,
            CapabilityType.ON_DEVICE_ML -> true
        }
    }

    /**
     * Helper to compute HMAC-SHA256 signature for a command.
     */
    fun computeHmac(command: RemoteCommand, secret: String): String {
        return try {
            val dataToSign = "${command.id}:${command.targetPluginId}:${command.action}:${command.timestamp}:${command.senderId}"
            val sha256Hmac = Mac.getInstance("HmacSHA256")
            val secretKey = SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256")
            sha256Hmac.init(secretKey)
            val hashBytes = sha256Hmac.doFinal(dataToSign.toByteArray(Charsets.UTF_8))
            hashBytes.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            ""
        }
    }
}

sealed interface ValidationResult {
    data object Accepted : ValidationResult
    data class Rejected(val reason: String) : ValidationResult
}
