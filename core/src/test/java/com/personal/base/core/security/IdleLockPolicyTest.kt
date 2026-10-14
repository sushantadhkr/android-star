package com.personal.base.core.security

import com.personal.base.core.logging.PlatformLogger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Proves the inactivity rule: the platform locks once the configured idle window closes, and
 * activity resets it.
 */
class IdleLockPolicyTest {

    private var now: Long = 1_000L
    private fun policy() = IdleLockPolicy(PlatformLogger()) { now }

    @Test
    fun `does not lock before the window closes`() {
        val p = policy()
        p.setTimeout(LockTimeout.FIVE_MINUTES)
        now += 299_000

        assertFalse(p.shouldLock())
    }

    @Test
    fun `locks once the window closes`() {
        val p = policy()
        p.setTimeout(LockTimeout.FIVE_MINUTES)
        now += 300_000

        assertTrue(p.shouldLock())
    }

    @Test
    fun `activity resets the idle window`() {
        val p = policy()
        p.setTimeout(LockTimeout.ONE_MINUTE)
        now += 90_000
        assertTrue("idle long enough", p.shouldLock())

        p.recordActivity()
        assertFalse("activity resets the window", p.shouldLock())
    }

    @Test
    fun `immediate policy locks as soon as any time passes`() {
        val p = policy()
        p.setTimeout(LockTimeout.IMMEDIATE)

        assertFalse("not yet at the instant of leaving", p.shouldLock())
        now += 1
        assertTrue("locks immediately after leaving", p.shouldLock())
    }

    @Test
    fun `timeout choice changes the threshold`() {
        val p = policy()
        p.setTimeout(LockTimeout.ONE_HOUR)
        now += 301_000
        assertFalse(p.shouldLock())

        p.setTimeout(LockTimeout.FIVE_MINUTES)
        assertTrue(p.shouldLock())
    }

    @Test
    fun `idle duration never goes negative`() {
        val p = policy()
        now -= 5_000
        assertEquals(0L, p.idleMillis())
    }

    @Test
    fun `unknown persisted timeout falls back to a safe default`() {
        assertEquals(LockTimeout.FIVE_MINUTES, LockTimeout.fromNameOrDefault("nonsense"))
        assertEquals(LockTimeout.FIVE_MINUTES, LockTimeout.fromNameOrDefault(null))
        assertEquals(LockTimeout.IMMEDIATE, LockTimeout.fromNameOrDefault("IMMEDIATE"))
    }
}