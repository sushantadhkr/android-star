package com.personal.base

import com.personal.base.core.capability.CapabilityProvider
import com.personal.base.core.capability.CapabilityStatus
import com.personal.base.core.capability.CapabilityType
import com.personal.base.core.capability.PlatformCapabilityRegistry
import com.personal.base.core.command.CommandResult
import com.personal.base.core.command.CommandValidator
import com.personal.base.core.command.RemoteCommand
import com.personal.base.core.command.UserRole
import com.personal.base.core.config.PlatformConfig
import com.personal.base.core.logging.PlatformLogger
import com.personal.base.core.plugin.PlatformContext
import com.personal.base.core.plugin.PlatformLocation
import com.personal.base.core.plugin.PlatformPlugin
import com.personal.base.core.plugin.PluginManager
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PluginSystemTest {

    private lateinit var logger: PlatformLogger
    private lateinit var fakeConfig: FakeConfigRepository
    private lateinit var registry: PlatformCapabilityRegistry
    private lateinit var validator: CommandValidator
    private lateinit var fakeContext: FakePlatformContext
    private lateinit var pluginManager: PluginManager

    @Before
    fun setUp() {
        logger = PlatformLogger()
        fakeConfig = FakeConfigRepository(
            PlatformConfig(
                requireSignedCommands = false,
                enabledPlugins = setOf("test-plugin")
            )
        )
        registry = PlatformCapabilityRegistry()
        validator = CommandValidator(fakeConfig, logger)
        fakeContext = FakePlatformContext(logger)

        pluginManager = PluginManager(
            platformContext = fakeContext,
            capabilityRegistry = registry,
            configRepository = fakeConfig,
            commandValidator = validator,
            logger = logger
        )
    }

    @Test
    fun testPluginLifecycleAndCommandDispatch() = runTest {
        val plugin = TestPlugin("test-plugin")
        pluginManager.registerPlugin(plugin)

        assertTrue("Plugin should be initialized", plugin.isInitialized)

        val command = RemoteCommand(
            id = "cmd-101",
            targetPluginId = "test-plugin",
            action = "ping",
            role = UserRole.AUTHENTICATED
        )

        val result = pluginManager.dispatchCommand(command)
        assertTrue(result.isSuccess)
        assertEquals("pong", result.message)
    }

    @Test
    fun testDisabledPluginRejectsCommand() = runTest {
        val plugin = TestPlugin("test-plugin")
        pluginManager.registerPlugin(plugin)

        // Disable plugin
        pluginManager.setPluginEnabled("test-plugin", false)
        assertFalse(fakeConfig.isPluginEnabled("test-plugin"))

        val command = RemoteCommand(
            id = "cmd-102",
            targetPluginId = "test-plugin",
            action = "ping",
            role = UserRole.AUTHENTICATED
        )

        val result = pluginManager.dispatchCommand(command)
        assertFalse(result.isSuccess)
        assertEquals("PLUGIN_DISABLED", result.errorCode)
    }

    @Test
    fun testNonExistentPluginRejectsCommand() = runTest {
        val command = RemoteCommand(
            id = "cmd-103",
            targetPluginId = "non-existent-plugin",
            action = "ping",
            role = UserRole.AUTHENTICATED
        )

        val result = pluginManager.dispatchCommand(command)
        assertFalse(result.isSuccess)
        assertEquals("PLUGIN_NOT_FOUND", result.errorCode)
    }

    @Test
    fun testCapabilityRequirementEnforcement() = runTest {
        // Plugin requires LOCATION capability
        val locationPlugin = object : PlatformPlugin {
            override val id: String = "loc-plugin"
            override val name: String = "Loc Plugin"
            override val version: String = "1.0"
            override val description: String = "Test"
            override val requiredCapabilities: Set<CapabilityType> = setOf(CapabilityType.LOCATION)
            override fun onInitialize(context: PlatformContext) {}
            override suspend fun handleCommand(command: RemoteCommand): CommandResult = CommandResult.success(command.id, "ok")
            override fun onDestroy() {}
        }

        fakeConfig.setPluginEnabled("loc-plugin", true)
        pluginManager.registerPlugin(locationPlugin)

        // When role is AUTHENTICATED (insufficient for LOCATION which requires ADMIN)
        val cmdAuth = RemoteCommand(
            id = "cmd-104",
            targetPluginId = "loc-plugin",
            action = "get",
            role = UserRole.AUTHENTICATED
        )
        val resultAuth = pluginManager.dispatchCommand(cmdAuth)
        assertFalse(resultAuth.isSuccess)
        assertEquals("UNAUTHORIZED", resultAuth.errorCode)

        // When role is ADMIN, but LOCATION provider is not registered in registry
        val cmdAdmin = RemoteCommand(
            id = "cmd-105",
            targetPluginId = "loc-plugin",
            action = "get",
            role = UserRole.ADMIN
        )
        val resultAdmin = pluginManager.dispatchCommand(cmdAdmin)
        assertFalse(resultAdmin.isSuccess)
        assertEquals("CAPABILITY_UNAVAILABLE", resultAdmin.errorCode)
    }
}

private class TestPlugin(override val id: String) : PlatformPlugin {
    override val name: String = "Test Plugin"
    override val version: String = "1.0.0"
    override val description: String = "Plugin for unit testing"
    override val requiredCapabilities: Set<CapabilityType> = emptySet()

    var isInitialized = false

    override fun onInitialize(context: PlatformContext) {
        isInitialized = true
    }

    override suspend fun handleCommand(command: RemoteCommand): CommandResult {
        return if (command.action == "ping") {
            CommandResult.success(command.id, "pong")
        } else {
            CommandResult.failure(command.id, "Unknown action")
        }
    }

    override fun onDestroy() {
        isInitialized = false
    }
}

private class FakePlatformContext(override val logger: PlatformLogger) : PlatformContext {
    // This fake covers plugin wiring, not platform services, so there is no real
    // Android Context to hand out.
    override fun appContext(): android.content.Context =
        throw UnsupportedOperationException("a fake context has no Android Context")
    override fun hasCapability(type: CapabilityType): Boolean = true
    override fun getCapabilityStatus(type: CapabilityType): CapabilityStatus = CapabilityStatus.Available
    override fun <T : CapabilityProvider> getCapability(type: CapabilityType): T? = null
    override fun <T : CapabilityProvider> getCapabilityByClass(clazz: Class<T>): T? = null
    override fun postNotification(title: String, message: String, priorityHigh: Boolean) {}
    override fun triggerHaptic(durationMs: Long) {}
    override fun setTorch(enabled: Boolean): Boolean = true
    override fun getBatteryPercentage(): Int = 85
    override fun getClipboardContent(): String? = "clipboard"
    override fun setClipboardContent(text: String, label: String) {}
    override fun shareText(text: String, title: String) {}
    override suspend fun getFreshLocation(): PlatformLocation? = null
}
