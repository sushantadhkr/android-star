package com.personal.base.core.platform

import com.personal.base.core.logging.PlatformLogger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket

/**
 * The server has to come back on its own.
 *
 * A foreground service alone is not enough to keep a phone reachable: the CPU still suspends, and a
 * socket that closed for any other reason used to stay closed until someone restarted the host by
 * hand. These cover the parts that make a dead server visible or self-healing rather than silent.
 */
class EmbeddedServerResilienceTest {

    private var server: EmbeddedServer? = null

    @After
    fun tearDown() {
        server?.stop()
    }

    private fun awaitStarted(s: EmbeddedServer, timeoutMs: Long = 5_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!s.isRunning.value && System.currentTimeMillis() < deadline) Thread.sleep(20)
        return s.isRunning.value
    }

    @Test
    fun `a server reports itself running once it is listening`() {
        val s = EmbeddedServer(PlatformLogger(), port = ServerSocket(0).use { it.localPort })
        server = s
        s.start()
        assertTrue("the server never started", awaitStarted(s))
        assertTrue(s.isRunning.value)
    }

    @Test
    fun `a port already in use is reported rather than left silently dead`() {
        // Hold the port so the server cannot have it.
        val squatter = ServerSocket(0)
        val port = squatter.localPort

        val s = EmbeddedServer(PlatformLogger(), port = port)
        server = s
        s.start()

        // It retries, then gives up. It must never claim to be running when it is not listening —
        // that is the failure that makes the platform look healthy while nothing answers.
        val deadline = System.currentTimeMillis() + 12_000
        while (s.isRunning.value && System.currentTimeMillis() < deadline) Thread.sleep(50)

        assertFalse("a server that could not bind must not report itself running", s.isRunning.value)
        squatter.close()
    }

    @Test
    fun `a stopped server reports itself stopped`() {
        val s = EmbeddedServer(PlatformLogger(), port = ServerSocket(0).use { it.localPort })
        server = s
        s.start()
        assertTrue(awaitStarted(s))

        s.stop()
        val deadline = System.currentTimeMillis() + 5_000
        while (s.isRunning.value && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertFalse("stop() must actually stop it", s.isRunning.value)
    }

    @Test
    fun `a server can be started again after being stopped`() {
        val port = ServerSocket(0).use { it.localPort }
        val s = EmbeddedServer(PlatformLogger(), port = port)
        server = s

        s.start()
        assertTrue(awaitStarted(s))
        s.stop()
        Thread.sleep(200)

        // The retry loop from a previous failure must not leave a stale job behind that makes
        // every restart a no-op.
        s.start()
        assertTrue("a server must be restartable", awaitStarted(s))
    }

    @Test
    fun `start is idempotent while it is already listening`() {
        val s = EmbeddedServer(PlatformLogger(), port = ServerSocket(0).use { it.localPort })
        server = s
        s.start()
        assertTrue(awaitStarted(s))

        s.start()
        Thread.sleep(100)
        assertTrue("a second start must not disturb a healthy server", s.isRunning.value)
    }

    @Test
    fun `the health signal a watchdog would use is truthful`() {
        val s = EmbeddedServer(PlatformLogger(), port = ServerSocket(0).use { it.localPort })
        server = s
        assertFalse("a server that has not started is not healthy", s.isRunning.value)

        s.start()
        assertTrue(awaitStarted(s))
        assertTrue("a listening server is healthy", s.isRunning.value)
    }
}