package com.personal.base.core.security

import com.personal.base.core.logging.PlatformLogger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Proves the lock rule: the clock only runs while the app is *away*, so unlocking returns the
 * user to a usable platform regardless of how aggressive the timeout is.
 */
class IdleLockPolicyTest {

    private var now: Long = 1_000L
    private fun policy() = IdleLockPolicy(PlatformLogger()) { now }

    @Test
    fun `never locks while the app is in the foreground`() {
        val p = policy()
        p.setTimeout(LockTimeout.IMMEDIATE)

        // A long session with no interaction must not lock the user out mid-use.
        now += 600_000
        assertTrue(p.isForeground.value)
        assertFalse(p.shouldLock())
    }

    @Test
    fun `immediate locks as soon as the app leaves`() {
        val p = policy()
        p.setTimeout(LockTimeout.IMMEDIATE)

        p.enterBackground()
        assertTrue(p.shouldLock())
    }

    @Test
    fun `timed policies do not lock before the window closes`() {
        val p = policy()
        p.setTimeout(LockTimeout.FIVE_MINUTES)

        p.enterBackground()
        now += 299_000
        assertFalse(p.shouldLock())

        now += 2_000
        assertTrue(p.shouldLock())
    }

    @Test
    fun `coming back to the foreground clears the lock`() {
        val p = policy()
        p.setTimeout(LockTimeout.IMMEDIATE)

        p.enterBackground()
        assertTrue(p.shouldLock())

        p.enterForeground()
        assertFalse("returning to the app must not stay locked", p.shouldLock())
    }

    @Test
    fun `unlocking then leaving again locks again`() {
        val p = policy()
        p.setTimeout(LockTimeout.IMMEDIATE)

        p.enterBackground()
        assertTrue(p.shouldLock())
        p.enterForeground()
        assertFalse(p.shouldLock())

        now += 5_000
        assertFalse("staying in the app must not re-lock", p.shouldLock())

        p.enterBackground()
        assertTrue("leaving again locks", p.shouldLock())
    }

    @Test
    fun `away duration restarts on each departure`() {
        val p = policy()
        p.setTimeout(LockTimeout.ONE_MINUTE)

        p.enterBackground()
        now += 90_000
        assertTrue(p.shouldLock())

        p.enterForeground()
        p.enterBackground()
        assertFalse("the away window restarts", p.shouldLock())

        now += 61_000
        assertTrue(p.shouldLock())
    }

    @Test
    fun `timeout choice changes the threshold`() {
        val p = policy()
        p.setTimeout(LockTimeout.ONE_HOUR)
        p.enterBackground()
        now += 301_000
        assertFalse(p.shouldLock())

        p.setTimeout(LockTimeout.FIVE_MINUTES)
        assertTrue(p.shouldLock())
    }

    @Test
    fun `away duration never goes negative`() {
        val p = policy()
        p.enterBackground()
        now -= 5_000
        assertEquals(0L, p.awayMillis())
    }

    @Test
    fun `unknown persisted timeout falls back to a safe default`() {
        assertEquals(LockTimeout.FIVE_MINUTES, LockTimeout.fromNameOrDefault("nonsense"))
        assertEquals(LockTimeout.FIVE_MINUTES, LockTimeout.fromNameOrDefault(null))
        assertEquals(LockTimeout.IMMEDIATE, LockTimeout.fromNameOrDefault("IMMEDIATE"))
    }
}