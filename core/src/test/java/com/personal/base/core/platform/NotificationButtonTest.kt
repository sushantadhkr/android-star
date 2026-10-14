package com.personal.base.core.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The notification button rules.
 *
 * A button is the one piece of the platform a user presses rather than a caller invoking, so the
 * definitions have to be wrong-proof: nothing that looks like a button should turn out to be one
 * that does nothing, and nothing the user typed should be silently discarded.
 */
class NotificationButtonTest {

    // ---- invoke targets -------------------------------------------------

    @Test
    fun `a two-part invoke target names a capability and action`() {
        val target = NotificationActionContract.parseInvoke("CLIPBOARD:write")
        assertEquals("CLIPBOARD", target!!.capability)
        assertEquals("write", target.action)
        assertNull("no field was named", target.field)
    }

    @Test
    fun `a three-part invoke target says where typed text goes`() {
        val target = NotificationActionContract.parseInvoke("ALARM_TIMER:set:minutes")
        assertEquals("minutes", target!!.field)
    }

    @Test
    fun `whitespace around an invoke target is forgiven`() {
        val target = NotificationActionContract.parseInvoke(" CLIPBOARD : write : text ")
        assertEquals("CLIPBOARD", target!!.capability)
        assertEquals("write", target.action)
        assertEquals("text", target.field)
    }

    @Test
    fun `a blank field counts as no field`() {
        assertNull(NotificationActionContract.parseInvoke("CLIPBOARD:write:")!!.field)
    }

    @Test
    fun `a malformed invoke target is refused rather than guessed at`() {
        listOf("", "CLIPBOARD", "CLIPBOARD:", ":write", "a:b:c:d").forEach { spec ->
            assertNull("'$spec' must not parse", NotificationActionContract.parseInvoke(spec))
        }
    }

    // ---- buttons ------------------------------------------------------

    @Test
    fun `a button needs a label`() {
        val failure = runCatching { NotificationButton(id = "done", label = "  ") }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
    }

    @Test
    fun `a button needs an id`() {
        val failure = runCatching { NotificationButton(id = "", label = "Done") }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
    }

    @Test
    fun `a button may run nothing and simply record the press`() {
        val button = NotificationButton(id = "ack", label = "Acknowledge")
        assertNull(button.invoke)
    }

    @Test
    fun `a reply field with nowhere to send the text is refused`() {
        val failure = runCatching {
            NotificationButton(
                id = "reply",
                label = "Reply",
                input = NotificationInput("text", "Type a reply"),
                invoke = "NOTIFICATIONS:post"
            )
        }.exceptionOrNull()

        assertTrue(
            "text the user typed must not be thrown away",
            failure is IllegalArgumentException
        )
    }

    @Test
    fun `a reply field that names its destination is accepted`() {
        val button = NotificationButton(
            id = "reply",
            label = "Reply",
            input = NotificationInput("text", "Type a reply"),
            invoke = "NOTIFICATIONS:post:text"
        )
        assertEquals("text", button.input!!.key)
        assertEquals("NOTIFICATIONS", NotificationActionContract.parseInvoke(button.invoke!!)!!.capability)
    }

    @Test
    fun `a button with a malformed invoke is refused`() {
        val failure =
            runCatching { NotificationButton(id = "x", label = "X", invoke = "nonsense") }
                .exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
    }

    @Test
    fun `the documented icons are all lowercase and stable`() {
        assertTrue(NotificationActionContract.ICONS.all { it == it.lowercase() })
        assertEquals(
            setOf("check", "alarm", "reply", "delete", "refresh", "open"),
            NotificationActionContract.ICONS
        )
    }
}