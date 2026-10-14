package com.personal.base

import com.personal.base.core.capability.CapabilityType
import com.personal.base.core.command.CommandValidator
import com.personal.base.core.command.RemoteCommand
import com.personal.base.core.command.UserRole
import com.personal.base.core.command.ValidationResult
import com.personal.base.core.config.ConfigRepository
import com.personal.base.core.config.PlatformConfig
import com.personal.base.core.logging.PlatformLogger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlinx.coroutines.flow.MutableStateFlow

class CommandSecurityTest {

    private lateinit var logger: PlatformLogger
    private lateinit var fakeConfigRepo: FakeConfigRepository
    private lateinit var validator: CommandValidator
    private val secret = "test_hmac_secret_key_123"

    @Before
    fun setUp() {
        logger = PlatformLogger()
        fakeConfigRepo = FakeConfigRepository(
            PlatformConfig(
                authSecretHmac = secret,
                requireSignedCommands = true
            )
        )
        validator = CommandValidator(fakeConfigRepo as ConfigRepository, logger)
    }

    @Test
    fun testValidCommandHmacAccepted() {
        val cmd = RemoteCommand(
            id = "cmd-1",
            targetPluginId = "system-diagnostics",
            action = "run",
            senderId = "test-runner",
            role = UserRole.ADMIN,
            timestamp = System.currentTimeMillis(),
            ttlSeconds = 300L
        )

        val validSig = validator.computeHmac(cmd, secret)
        val signedCmd = cmd.copy(signature = validSig)

        val result = validator.validate(signedCmd)
        assertTrue(result is ValidationResult.Accepted)
    }

    @Test
    fun testTamperedSignatureRejected() {
        val cmd = RemoteCommand(
            id = "cmd-2",
            targetPluginId = "system-diagnostics",
            action = "run",
            senderId = "test-runner",
            role = UserRole.ADMIN,
            timestamp = System.currentTimeMillis(),
            signature = "tampered_fake_signature",
            ttlSeconds = 300L
        )

        val result = validator.validate(cmd)
        assertTrue(result is ValidationResult.Rejected)
        assertEquals("Invalid cryptographic HMAC signature", (result as ValidationResult.Rejected).reason)
    }

    @Test
    fun testExpiredCommandRejected() {
        val oldTimestamp = System.currentTimeMillis() - 500_000L // 500s ago (> 300s TTL)
        val cmd = RemoteCommand(
            id = "cmd-3",
            targetPluginId = "system-diagnostics",
            action = "run",
            senderId = "test-runner",
            role = UserRole.ADMIN,
            timestamp = oldTimestamp,
            ttlSeconds = 300L
        )

        val sig = validator.computeHmac(cmd, secret)
        val expiredCmd = cmd.copy(signature = sig)

        val result = validator.validate(expiredCmd)
        assertTrue(result is ValidationResult.Rejected)
        assertTrue((result as ValidationResult.Rejected).reason.contains("expired or clock drift"))
    }

    @Test
    fun testRoleCapabilityAccessMatrix() {
        // GUEST cannot access Location or Camera or Device Actions
        assertFalse(validator.checkCapabilityAccess(UserRole.GUEST, CapabilityType.LOCATION))
        assertFalse(validator.checkCapabilityAccess(UserRole.GUEST, CapabilityType.CAMERA))
        assertFalse(validator.checkCapabilityAccess(UserRole.GUEST, CapabilityType.DEVICE_ACTIONS))

        // AUTHENTICATED can access Sensors, Notifications, Device Actions, but not Location or Camera
        assertTrue(validator.checkCapabilityAccess(UserRole.AUTHENTICATED, CapabilityType.SENSORS))
        assertTrue(validator.checkCapabilityAccess(UserRole.AUTHENTICATED, CapabilityType.NOTIFICATIONS))
        assertTrue(validator.checkCapabilityAccess(UserRole.AUTHENTICATED, CapabilityType.DEVICE_ACTIONS))
        assertFalse(validator.checkCapabilityAccess(UserRole.AUTHENTICATED, CapabilityType.LOCATION))
        assertFalse(validator.checkCapabilityAccess(UserRole.AUTHENTICATED, CapabilityType.CAMERA))

        // ADMIN has access to all capabilities
        assertTrue(validator.checkCapabilityAccess(UserRole.ADMIN, CapabilityType.LOCATION))
        assertTrue(validator.checkCapabilityAccess(UserRole.ADMIN, CapabilityType.CAMERA))
        assertTrue(validator.checkCapabilityAccess(UserRole.ADMIN, CapabilityType.DEVICE_ACTIONS))
        assertTrue(validator.checkCapabilityAccess(UserRole.ADMIN, CapabilityType.SENSORS))
    }
}
