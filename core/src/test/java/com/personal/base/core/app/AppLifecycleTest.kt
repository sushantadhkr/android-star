package com.personal.base.core.app

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import com.personal.base.core.capability.CapabilityProvider
import com.personal.base.core.capability.CapabilityStatus
import com.personal.base.core.capability.CapabilityType
import com.personal.base.core.command.CommandResult
import com.personal.base.core.command.RemoteCommand
import com.personal.base.core.command.UserRole
import com.personal.base.core.logging.PlatformLogger
import com.personal.base.core.plugin.PlatformContext
import com.personal.base.core.plugin.PlatformLocation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Proves the lifecycle invariant the safety toggle depends on:
 * an enabled App has exactly one instance; a disabled App has none.
 */
class AppLifecycleTest {

    private class FakeToggleStore : AppToggleStoreContract {
        private val state = mutableMapOf<String, Boolean>()
        override fun isEnabled(appId: String): Boolean = state[appId] ?: true
        override fun setEnabled(appId: String, enabled: Boolean) {
            state[appId] = enabled
        }
    }

    private class CountingApp : PlatformApp {
        override val id = TEST_ID
        override val name = "Test App"
        override val icon = Icons.Default.Apps
        override val description = "Counts its own lifecycle"
        override val version = "1.0"

        var destroyed = 0
        override fun onDestroy() {
            destroyed++
        }

        override suspend fun handleCommand(command: RemoteCommand): CommandResult =
            CommandResult.success(command.id, "handled")
    }

    private lateinit var manager: AppManager
    private var createdCount = 0
    private var lastInstance: CountingApp? = null

    @Before
    fun setUp() {
        manager = AppManager(
            platformContext = FakeContext,
            toggleStore = FakeToggleStore(),
            logger = PlatformLogger()
        )
        createdCount = 0
        lastInstance = null

        manager.register(
            AppRegistration(
                id = TEST_ID,
                name = "Test App",
                description = "Counts its own lifecycle",
                version = "1.0",
                icon = Icons.Default.Apps,
                category = "Tools",
                requiredCapabilities = emptySet(),
                order = 1,
                factory = {
                    createdCount++
                    CountingApp().also { lastInstance = it }
                }
            )
        )
    }

    @Test
    fun `app is registered and enabled by default`() {
        val entry = manager.apps.value.single()
        assertEquals(TEST_ID, entry.id)
        assertTrue(entry.isEnabled)
    }

    @Test
    fun `enabled app has exactly one instance`() {
        val entry = manager.apps.value.single()
        assertNotNull("an enabled App must have an instance", entry.instance)
        assertTrue(entry.isRunning)
        assertEquals(1, createdCount)
    }

    @Test
    fun `disabling destroys the instance and clears state`() {
        manager.setEnabled(TEST_ID, false)

        assertNull("a disabled App must have no instance", manager.instanceOf(TEST_ID))
        assertEquals(1, lastInstance!!.destroyed)

        val entry = manager.apps.value.single()
        assertFalse(entry.isEnabled)
        assertFalse(entry.isRunning)
    }

    @Test
    fun `a disabled app is still listed so it keeps its toggle`() {
        manager.setEnabled(TEST_ID, false)
        assertEquals(1, manager.apps.value.size)
    }

    @Test
    fun `re-enabling creates a fresh instance`() {
        manager.setEnabled(TEST_ID, false)
        manager.setEnabled(TEST_ID, true)

        assertNotNull(manager.instanceOf(TEST_ID))
        assertEquals(2, createdCount)
    }

    @Test
    fun `enabling an already-running app does not create a second instance`() {
        manager.setEnabled(TEST_ID, true)
        assertEquals(1, createdCount)
    }

    @Test
    fun `a disabled app cannot be opened`() {
        manager.setEnabled(TEST_ID, false)
        assertFalse(manager.openApp(TEST_ID))
    }

    @Test
    fun `an enabled app can be opened and closed`() {
        assertTrue(manager.openApp(TEST_ID))
        assertEquals(TEST_ID, manager.activeAppId.value)
        manager.closeActiveApp()
        assertNull(manager.activeAppId.value)
    }

    @Test
    fun `closing an open app when it is disabled clears the active app`() = runBlocking {
        manager.openApp(TEST_ID)
        manager.setEnabled(TEST_ID, false)
        assertNull(manager.activeAppId.value)
    }

    @Test
    fun `commands to a stopped app are rejected`() = runBlocking {
        manager.setEnabled(TEST_ID, false)
        val result = manager.dispatchCommandToApp(
            RemoteCommand(
                id = "cmd-1",
                targetPluginId = TEST_ID,
                action = "run",
                senderId = "test",
                role = UserRole.ADMIN
            )
        )
        assertFalse(result.isSuccess)
    }

    @Test
    fun `commands to a running app are dispatched`() = runBlocking {
        val result = manager.dispatchCommandToApp(
            RemoteCommand(
                id = "cmd-2",
                targetPluginId = TEST_ID,
                action = "run",
                senderId = "test",
                role = UserRole.ADMIN
            )
        )
        assertTrue(result.isSuccess)
    }

    @Test
    fun `periodic sync runs only for running apps`() = runBlocking {
        manager.setEnabled(TEST_ID, false)
        manager.runPeriodicSync() // must not throw
    }

    @Test
    fun `release tears down every running app`() {
        assertNotNull(manager.instanceOf(TEST_ID))
        manager.releaseAll()
        assertNull(manager.instanceOf(TEST_ID))
        assertEquals(1, lastInstance!!.destroyed)
    }

    private object FakeContext : PlatformContext {
        override val logger: PlatformLogger = PlatformLogger()
        override fun hasCapability(type: CapabilityType) = true
        override fun getCapabilityStatus(type: CapabilityType) = CapabilityStatus.Available
        override fun <T : CapabilityProvider> getCapability(type: CapabilityType): T? = null
        override fun <T : CapabilityProvider> getCapabilityByClass(clazz: Class<T>): T? = null
        override fun postNotification(title: String, message: String, priorityHigh: Boolean) = Unit
        override fun triggerHaptic(durationMs: Long) = Unit
        override fun setTorch(enabled: Boolean) = false
        override fun getBatteryPercentage() = 100
        override fun getClipboardContent(): String? = null
        override fun setClipboardContent(text: String, label: String) = Unit
        override fun shareText(text: String, title: String) = Unit
        override suspend fun getFreshLocation(): PlatformLocation? = null
    }

    private companion object {
        const val TEST_ID = "test-app"
    }
}