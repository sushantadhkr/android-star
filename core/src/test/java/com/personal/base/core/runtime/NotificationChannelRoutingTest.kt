package com.personal.base.core.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Proves the notification routing rule that makes per-App muting work: every App gets its own
 * channel, namespaced so it can never collide with a platform channel or another App.
 */
class NotificationChannelRoutingTest {

    private val platformChannels = listOf(
        NotificationChannelManager.CHANNEL_FOREGROUND,
        NotificationChannelManager.CHANNEL_SECURITY,
        NotificationChannelManager.CHANNEL_AUTOMATION
    )

    @Test
    fun `an app channel is derived from its id`() {
        assertEquals("app_webcam", NotificationChannelManager.appChannelId("webcam"))
    }

    @Test
    fun `the channel id is stable across restarts`() {
        // Deterministic derivation is what makes the mute preference survive a reboot.
        assertEquals(
            NotificationChannelManager.appChannelId("stocks"),
            NotificationChannelManager.appChannelId("stocks")
        )
    }

    @Test
    fun `an app channel never collides with a platform channel`() {
        platformChannels.forEach { platform ->
            assertNotEquals(
                "App channel must be distinct from platform channel $platform",
                platform,
                NotificationChannelManager.appChannelId("webcam")
            )
        }
    }

    @Test
    fun `two apps never share a channel`() {
        assertNotEquals(
            NotificationChannelManager.appChannelId("webcam"),
            NotificationChannelManager.appChannelId("stocks")
        )
    }

    @Test
    fun `groups separate the platform from apps`() {
        assertNotEquals(NotificationGroup.PLATFORM, NotificationGroup.APPS)
        assertTrue(NotificationGroup.entries.size == 2)
    }
}
