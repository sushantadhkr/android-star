package com.personal.base.core.capability

import com.personal.base.core.app.WidgetRegistry
import com.personal.base.core.app.WidgetSpec
import com.personal.base.core.logging.PlatformLogger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Capabilities that declare a tile.
 *
 * Declaring is the whole contract: the shell asks every registered provider what it wants shown
 * rather than holding a list of which capabilities deserve one. So a capability with nothing to say
 * must be able to stay silent without being special-cased anywhere.
 */
class CapabilityWidgetTest {

    /** A provider that declares a tile. */
    private class TiledProvider(
        private val spec: WidgetSpec?
    ) : CapabilityProvider {
        override val type = CapabilityType.BATTERY_POWER
        override fun widgetSpec(): WidgetSpec? = spec
    }

    @Test
    fun `a capability with nothing to show declares nothing by default`() {
        val registry = PlatformCapabilityRegistry()
        val plain = object : CapabilityProvider {
            override val type = CapabilityType.STORAGE
        }
        registry.register(plain)

        assertNull("the default must be silence", plain.widgetSpec())
    }

    @Test
    fun `a declaring capability's tile reaches the widget registry`() {
        val registry = WidgetRegistry()
        val provider = TiledProvider(WidgetSpec("battery", "Battery", status = "82%"))

        registry.publish("capability:BATTERY_POWER", provider.widgetSpec())

        assertEquals(1, registry.size())
        assertEquals("Battery", registry.forApp("capability:BATTERY_POWER")!!.spec.title)
        assertEquals("82%", registry.forApp("capability:BATTERY_POWER")!!.spec.status)
    }

    @Test
    fun `a capability tile follows the capability's switch`() {
        // The rule the whole platform rests on: switch something off and nothing may reach it.
        // A tile is a window onto that capability, so it goes with it.
        val registry = WidgetRegistry()
        val spec = WidgetSpec("battery", "Battery")

        registry.publish("capability:BATTERY_POWER", spec, isEnabled = false)
        assertTrue(
            "a switched-off capability must not keep a home-screen tile",
            registry.visible().isEmpty()
        )
    }

    @Test
    fun `a capability tile cannot collide with an apps tile`() {
        val registry = WidgetRegistry()
        registry.publish("capability:DOWNLOAD_MANAGER", WidgetSpec("downloads", "Downloads"))
        registry.publish("my-app", WidgetSpec("my-app", "My App"))

        assertEquals(2, registry.size())
        assertEquals("Downloads", registry.forApp("capability:DOWNLOAD_MANAGER")!!.spec.title)
        assertEquals("My App", registry.forApp("my-app")!!.spec.title)
    }

    @Test
    fun `a capability that stops declaring withdraws its tile`() {
        val registry = WidgetRegistry()
        registry.publish("capability:BATTERY_POWER", WidgetSpec("battery", "Battery"))

        registry.publish("capability:BATTERY_POWER", TiledProvider(null).widgetSpec())

        assertNull("a silent capability must leave nothing behind", registry.forApp("capability:BATTERY_POWER"))
    }

}
