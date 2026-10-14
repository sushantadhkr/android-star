package com.personal.base.core.app

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import com.personal.base.core.capability.CapabilityType
import com.personal.base.core.logging.PlatformLogger
import com.personal.base.core.plugin.PlatformContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Proves the dashboard resolution rules: empty by default, contributions only, user order wins,
 * hidden items are dropped, and a stopped App keeps its place.
 */
class DashboardComposerTest {

    private class FakeLayoutStore : DashboardLayoutStoreContract {
        private var order: List<String> = emptyList()
        private val hidden = mutableSetOf<String>()

        override fun visibleItems() = order
        override fun setVisibleItems(order: List<String>) {
            this.order = order
        }

        override fun isHidden(itemId: String) = hidden.contains(itemId)
        override fun setHidden(itemId: String, hidden: Boolean) {
            if (hidden) this.hidden.add(itemId) else this.hidden.remove(itemId)
        }

        override fun move(itemId: String, toIndex: Int) {
            val current = order.toMutableList()
            val from = current.indexOf(itemId)
            if (from < 0) return
            current.removeAt(from)
            current.add(toIndex.coerceIn(0, current.size), itemId)
            order = current
        }
    }

    private class TestApp(
        override val id: String,
        private val card: Boolean = true,
        private val actions: List<DashboardAction> = emptyList()
    ) : PlatformApp {
        override val name = id
        override val icon = Icons.Default.Apps
        override val description = ""
        override val version = "1.0"

        override val contribution = AppContribution(
            dashboardCard = if (card) { { } } else null,
            dashboardActions = actions
        )
    }

    private lateinit var layout: FakeLayoutStore
    private lateinit var manager: AppManager
    private lateinit var composer: DashboardComposer

    @Before
    fun setUp() {
        layout = FakeLayoutStore()
        manager = AppManager(
            platformContext = FakeContext,
            toggleStore = object : AppToggleStoreContract {
                private val state = mutableMapOf<String, Boolean>()
                override fun isEnabled(appId: String) = state[appId] ?: true
                override fun setEnabled(appId: String, enabled: Boolean) {
                    state[appId] = enabled
                }
            },
            logger = PlatformLogger()
        )
        composer = DashboardComposer(layout)
    }

    private fun register(id: String, card: Boolean = true, actions: List<DashboardAction> = emptyList()) {
        manager.register(
            AppRegistration(
                id = id,
                name = id,
                description = "",
                version = "1.0",
                icon = Icons.Default.Apps,
                category = "Tools",
                requiredCapabilities = emptySet(),
                order = 10,
                factory = { TestApp(id, card, actions) }
            )
        )
    }

    @Test
    fun `dashboard is empty when no app contributes`() {
        assertTrue(composer.compose(manager.apps.value).isEmpty())
    }

    @Test
    fun `only declaring apps produce items`() {
        register("withcard", card = true)
        register("nocard", card = false)

        val items = composer.compose(manager.apps.value)
        assertEquals(1, items.size)
        assertEquals("withcard", items.first().appId())
    }

    @Test
    fun `an app can contribute several actions`() {
        register(
            "multi", card = false,
            actions = listOf(
                DashboardAction("a1", "First"),
                DashboardAction("a2", "Second")
            )
        )

        val items = composer.compose(manager.apps.value)
        assertEquals(2, items.size)
        assertTrue(items.all { it is DashboardItem.Action })
    }

    @Test
    fun `hidden items are dropped`() {
        register("hidden-one")
        register("shown-one")

        layout.setHidden("card:hidden-one", true)

        val items = composer.compose(manager.apps.value)
        assertEquals(1, items.size)
        assertEquals("shown-one", items.first().appId())
    }

    @Test
    fun `the saved order wins over declaration order`() {
        register("alpha")
        register("beta")

        layout.setVisibleItems(listOf("card:beta", "card:alpha"))

        val items = composer.compose(manager.apps.value)
        assertEquals("beta", items[0].appId())
        assertEquals("alpha", items[1].appId())
    }

    @Test
    fun `unseen items are appended after saved ones`() {
        register("alpha")
        register("beta")

        layout.setVisibleItems(listOf("card:beta"))

        val items = composer.compose(manager.apps.value)
        assertEquals("beta", items[0].appId())
        assertEquals("alpha", items[1].appId())
    }

    @Test
    fun `a stopped app keeps its place and is marked disabled`() {
        register("running")
        register("stopped")
        layout.setVisibleItems(listOf("card:running", "card:stopped"))

        manager.setEnabled("stopped", false)

        val items = composer.compose(manager.apps.value)
        assertEquals("the arrangement survives a toggle", 2, items.size)
        val stoppedItem = items.first { it.appId() == "stopped" }
        assertFalse("a stopped App's item reads as disabled", stoppedItem.isEnabled)
    }

    private fun DashboardItem.appId(): String = when (this) {
        is DashboardItem.Card -> appId
        is DashboardItem.Action -> appId
    }

    private object FakeContext : PlatformContext {
        // These fakes test lifecycle, not platform services, so there is no real Context here.
        override fun appContext(): android.content.Context =
            throw UnsupportedOperationException("a fake context has no Android Context")
        override val logger: PlatformLogger = PlatformLogger()
        override fun hasCapability(type: CapabilityType) = true
        override fun getCapabilityStatus(type: CapabilityType) =
            com.personal.base.core.capability.CapabilityStatus.Available

        override fun <T : com.personal.base.core.capability.CapabilityProvider> getCapability(
            type: CapabilityType
        ): T? = null

        override fun <T : com.personal.base.core.capability.CapabilityProvider> getCapabilityByClass(
            clazz: Class<T>
        ): T? = null

        override fun postNotification(title: String, message: String, priorityHigh: Boolean) = Unit
        override fun triggerHaptic(durationMs: Long) = Unit
        override fun setTorch(enabled: Boolean) = false
        override fun getBatteryPercentage() = 100
        override fun getClipboardContent(): String? = null
        override fun setClipboardContent(text: String, label: String) = Unit
        override fun shareText(text: String, title: String) = Unit
        override suspend fun getFreshLocation(): com.personal.base.core.plugin.PlatformLocation? = null
    }
}