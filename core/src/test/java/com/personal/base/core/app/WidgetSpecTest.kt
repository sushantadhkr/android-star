package com.personal.base.core.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Widgets, as data.
 *
 * The rules exist because a home-screen widget is the one place the platform cannot check
 * anything at runtime — once a tile is on someone's home screen it is presenting the platform's
 * name, and a wrong number is a lie with no error message.
 */
class WidgetSpecTest {

    // ---- the spec refuses nonsense at construction -------------------------------

    @Test
    fun `a widget needs an id`() {
        assertRefused { WidgetSpec(id = "  ", title = "Title") }
    }

    @Test
    fun `a widget needs a title`() {
        assertRefused { WidgetSpec(id = "w", title = "") }
    }

    @Test
    fun `a title too long for a home screen is refused rather than truncated silently`() {
        // Silently truncating would mean the App believes it said something it did not.
        assertRefused { WidgetSpec(id = "w", title = "x".repeat(41)) }
        WidgetSpec(id = "w", title = "x".repeat(40))
    }

    @Test
    fun `progress outside zero to a hundred is refused`() {
        assertRefused { WidgetSpec(id = "w", title = "T", progress = -1) }
        assertRefused { WidgetSpec(id = "w", title = "T", progress = 101) }
        WidgetSpec(id = "w", title = "T", progress = 0)
        WidgetSpec(id = "w", title = "T", progress = 100)
    }

    private fun assertRefused(block: () -> Unit) {
        val failure = runCatching { block() }.exceptionOrNull()
        assertTrue("an invalid widget must not be constructible", failure is IllegalArgumentException)
    }

    // ---- the registry follows the App toggle --------------------------------------

    @Test
    fun `publishing twice for one app replaces rather than duplicates`() {
        val registry = WidgetRegistry()
        registry.publish("a", WidgetSpec("a", "First"))
        registry.publish("a", WidgetSpec("a", "Second"))

        assertEquals(1, registry.size())
        assertEquals("Second", registry.forApp("a")!!.spec.title)
    }

    @Test
    fun `a switched-off app's widget is not visible`() {
        val registry = WidgetRegistry()
        registry.publish("a", WidgetSpec("a", "Title"), isEnabled = false)

        assertEquals("a hidden widget is still recorded", 1, registry.size())
        assertTrue("but it must not be on a home screen", registry.visible().isEmpty())
    }

    @Test
    fun `switching an app back on makes its widget visible again`() {
        val registry = WidgetRegistry()
        val spec = WidgetSpec("a", "Title")
        registry.publish("a", spec, isEnabled = false)
        registry.publish("a", spec, isEnabled = true)

        assertEquals(1, registry.visible().size)
    }

    @Test
    fun `withdrawing removes the widget entirely`() {
        val registry = WidgetRegistry()
        registry.publish("a", WidgetSpec("a", "Title"))
        registry.withdraw("a")

        assertNull(registry.forApp("a"))
        assertTrue(registry.visible().isEmpty())
    }

    @Test
    fun `one app's widget never touches another's`() {
        val registry = WidgetRegistry()
        registry.publish("a", WidgetSpec("a", "A"))
        registry.publish("b", WidgetSpec("b", "B"))

        registry.publish("a", WidgetSpec("a", "A again"))

        assertEquals(2, registry.size())
        assertEquals("A again", registry.forApp("a")!!.spec.title)
        assertEquals("B", registry.forApp("b")!!.spec.title)
    }

    @Test
    fun `an app with no widget simply has none`() {
        assertNull(WidgetRegistry().forApp("nothing-here"))
    }

    // ---- the icons ----------------------------------------------------------------

    @Test
    fun `the documented icons are stable`() {
        // The console and the shell both read this set, so a rename would silently blank icons.
        assertEquals(
            setOf(
                "check", "alarm", "sync", "download", "camera",
                "mic", "location", "sms", "storage", "network"
            ),
            WidgetSpec.WIDGET_ICONS
        )
    }
}