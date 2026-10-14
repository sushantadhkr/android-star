package com.personal.base.core.platform

import com.personal.base.core.logging.PlatformLogger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket

/**
 * The server's authentication boundary, over a real socket.
 *
 * Exactly one static page is served without a token, because a browser cannot attach an
 * `Authorization` header to a plain navigation — the console could otherwise never load, and could
 * never show the form that asks for the token. That exception has to stay one static page.
 *
 * These open an actual socket rather than calling a helper, because the check lives in the request
 * path. A test of anything else would pass whether or not the boundary worked.
 */
class EmbeddedServerAuthTest {

    private var server: EmbeddedServer? = null

    @After
    fun tearDown() {
        server?.stop()
    }

    /** An ephemeral port, so tests never collide with a real server or each other. */
    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun start(publicPaths: Set<String>): Pair<EmbeddedServer, Int> {
        val port = freePort()
        val s = EmbeddedServer(
            logger = PlatformLogger(),
            port = port,
            authorizer = { request ->
                if (request.bearerToken() == TOKEN) {
                    null
                } else {
                    ServerResponse(401, "application/json", """{"error":"Unauthorized"}""")
                }
            },
            publicPaths = publicPaths,
        )
        s.registerRoute("/") { ServerResponse(200, "text/html; charset=utf-8", CONSOLE_PAGE) }
        s.registerRoute("/index.html") { ServerResponse(200, "text/html; charset=utf-8", CONSOLE_PAGE) }
        s.registerRoute("/api/capabilities") { ServerResponse.json("""{"capabilities":[]}""") }
        s.registerRoute("/api/capabilities/invoke") { ServerResponse.json("""{"ok":true}""") }
        s.start()
        server = s

        // start() binds the socket on a background coroutine; connecting before it is open would
        // fail for a reason that has nothing to do with what is being tested.
        val deadline = System.currentTimeMillis() + 5_000
        while (!s.isRunning.value && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertTrue("the server never started", s.isRunning.value)

        return s to port
    }

    /** A real HTTP/1.1 request, so the whole path including headers is exercised. */
    private fun get(port: Int, path: String, token: String? = null): Pair<Int, String> {
        Socket("127.0.0.1", port).use { socket ->
            val authHeader = if (token != null) "Authorization: Bearer $token\r\n" else ""
            val request = "GET $path HTTP/1.1\r\nHost: localhost\r\n${authHeader}Connection: close\r\n\r\n"

            socket.getOutputStream().apply {
                write(request.toByteArray())
                flush()
            }

            val reader = BufferedReader(InputStreamReader(socket.inputStream))
            val status = reader.readLine()
                ?.split(" ")
                ?.getOrNull(1)
                ?.toIntOrNull()
                ?: 0

            // Headers end at the first blank line; the body is everything after.
            var line = reader.readLine()
            while (line != null && line.isNotEmpty()) line = reader.readLine()

            return status to reader.readText()
        }
    }

    @Test
    fun `the console loads without a token`() {
        val (_, port) = start(CONSOLE)
        val (status, body) = get(port, "/")

        assertEquals(200, status)
        assertTrue("the page must actually be served", body.contains("Android Star"))
    }

    @Test
    fun `the console is reachable by name too`() {
        val (_, port) = start(CONSOLE)
        assertEquals(200, get(port, "/index.html").first)
    }

    @Test
    fun `listing capabilities still needs the token`() {
        val (_, port) = start(CONSOLE)
        assertEquals(401, get(port, "/api/capabilities").first)
    }

    @Test
    fun `invoking a capability still needs the token`() {
        val (_, port) = start(CONSOLE)
        assertEquals(401, get(port, "/api/capabilities/invoke").first)
    }

    @Test
    fun `the right token is still accepted`() {
        val (_, port) = start(CONSOLE)
        assertEquals(200, get(port, "/api/capabilities", token = TOKEN).first)
    }

    @Test
    fun `the wrong token is refused`() {
        val (_, port) = start(CONSOLE)
        assertEquals(401, get(port, "/api/capabilities", token = "wrong").first)
    }

    @Test
    fun `without a declared exemption nothing is public`() {
        val (_, port) = start(emptySet())
        assertEquals("an empty set must mean fully authenticated", 401, get(port, "/").first)
        assertEquals(401, get(port, "/index.html").first)
    }

    @Test
    fun `the exemption does not leak onto a path that merely resembles it`() {
        val (_, port) = start(CONSOLE)
        // Authorisation runs before routing, so anything not on the public list is turned away with
        // 401 whether or not a route exists. A prefix match would instead have served these.
        assertEquals(401, get(port, "/index.html.bak").first)
        assertEquals(401, get(port, "/api/index.html").first)
        assertEquals(401, get(port, "/index").first)
    }

    @Test
    fun `the public set is exactly the console and nothing under the API`() {
        assertEquals(setOf("/", "/index.html"), CONSOLE)
        assertTrue(CONSOLE.none { it.startsWith("/api") })
    }

    private companion object {
        val CONSOLE = setOf("/", "/index.html")
        const val TOKEN = "secret"
        const val CONSOLE_PAGE = "<html><title>Android Star</title></html>"
    }
}