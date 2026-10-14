package com.personal.base.core.capability

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The record of notification button presses.
 *
 * Bounded on purpose: this is a live view of what was just pressed, and a device left running for
 * weeks must not accumulate presses forever.
 */
class NotificationActionLogTest {

    private fun record(id: String, at: Long) = NotificationActionRecord(
        buttonId = id,
        label = id.replaceFirstChar { it.uppercase() },
        notificationId = 1,
        notificationTitle = "Build passed",
        typedText = null,
        invokeTarget = "CLIPBOARD:write",
        invokeStatus = 200,
        invokeBody = """{"written":true}""",
        atEpochMillis = at
    )

    @Test
    fun `an empty log reports nothing`() {
        assertEquals(emptyList<NotificationActionRecord>(), NotificationActionLog().recent())
    }

    @Test
    fun `the newest press comes first`() {
        val log = NotificationActionLog()
        log.record(record("first", 1))
        log.record(record("second", 2))

        assertEquals(listOf("second", "first"), log.recent().map { it.buttonId })
    }

    @Test
    fun `a limit returns only the newest presses`() {
        val log = NotificationActionLog()
        log.record(record("first", 1))
        log.record(record("second", 2))
        log.record(record("third", 3))

        assertEquals(listOf("third", "second"), log.recent(limit = 2).map { it.buttonId })
    }

    @Test
    fun `the log never grows past its capacity`() {
        val log = NotificationActionLog(capacity = 3)
        (1..10).forEach { log.record(record("press$it", it.toLong())) }

        assertEquals(3, log.size())
        assertEquals(listOf("press10", "press9", "press8"), log.recent().map { it.buttonId })
    }

    @Test
    fun `what the user typed is kept`() {
        val log = NotificationActionLog()
        log.record(
            record("reply", 1).copy(typedText = "looks good", invokeTarget = "NOTIFICATIONS:post:text")
        )

        assertEquals("looks good", log.recent().single().typedText)
    }
}