package com.personal.base.core.platform

import com.personal.base.core.logging.PlatformLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

/**
 * What a route handler receives.
 */
data class ServerRequest(
    val method: String,
    val path: String,
    val body: String,
    val headers: Map<String, String> = emptyMap()
) {
    /** The bearer token presented by the caller, if any. */
    fun bearerToken(): String? =
        headers.entries
            .firstOrNull { it.key.equals("authorization", ignoreCase = true) }
            ?.value
            ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
            ?.substring(7)
            ?.trim()
}

/**
 * What a route handler returns.
 *
 * A status code is part of the response because a stopped App must be able to answer
 * "not running" rather than silently serving stale data.
 */
data class ServerResponse(
    val status: Int = 200,
    val contentType: String = "application/json",
    val body: String
) {
    companion object {
        fun json(body: String) = ServerResponse(200, "application/json", body)
        fun text(body: String) = ServerResponse(200, "text/plain", body)

        /** Used when the owning App is switched off. */
        fun unavailable(reason: String) =
            ServerResponse(503, "application/json", """{"error":"$reason"}""")
    }
}

private typealias RouteHandler = (ServerRequest) -> ServerResponse

/**
 * A lightweight HTTP server owned by the platform.
 *
 * The platform runs one instance and Apps register routes onto it, so there is a single server and a
 * single port no matter how many Apps are installed. Routes may be removed again, which is how a
 * stopped App stops answering.
 *
 * When an [authorizer] is supplied, every request must present a valid bearer token. Enforcement
 * lives here rather than in individual routes so a newly added route cannot accidentally ship
 * unauthenticated.
 *
 * @param publicPaths paths answered without a token. Empty by default, so the safe case is the
 *   default case. Exact matches only, never prefixes, so a broad exemption cannot be granted by
 *   accident. A browser cannot attach an `Authorization` header to a plain navigation, which is the
 *   only reason a static page would need this.
 */
class EmbeddedServer(
    private val logger: PlatformLogger,
    val port: Int = DEFAULT_PORT,
    private val authorizer: ((ServerRequest) -> ServerResponse?)? = null,
    private val publicPaths: Set<String> = emptySet()
) {

    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var serverSocket: ServerSocket? = null
    private var serverJob: kotlinx.coroutines.Job? = null

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val routeHandlers = ConcurrentHashMap<String, RouteHandler>()

    /** Registers a route. Re-registering a path replaces the previous handler. */
    fun registerRoute(path: String, handler: RouteHandler) {
        routeHandlers[path] = handler
    }

    /** Removes a route. Used when an App stops serving. */
    fun unregisterRoute(path: String) {
        routeHandlers.remove(path)
    }

    fun hasRoute(path: String): Boolean = routeHandlers.containsKey(path)

    /**
     * The handler registered for a path, or null.
     *
     * Exposed so a route can be exercised without opening a socket, which keeps the server's gating
     * and status behaviour testable.
     */
    fun handlerFor(path: String): ((ServerRequest) -> ServerResponse)? = routeHandlers[path]

    /** Every registered path, sorted — used to publish the current API surface. */
    fun routes(): List<String> = routeHandlers.keys.sorted()

    fun start() {
        if (_isRunning.value) return
        serverJob = scope.launch {
            try {
                serverSocket = ServerSocket(port)
                _isRunning.value = true
                logger.i("EmbeddedServer", "Server listening on port $port (${routeHandlers.size} routes)")

                while (isActive) {
                    val client = serverSocket?.accept() ?: break
                    scope.launch { handleClient(client) }
                }
            } catch (e: Exception) {
                if (isActive) {
                    logger.e("EmbeddedServer", "Server socket error: ${e.message}", e.stackTraceToString())
                }
            } finally {
                _isRunning.value = false
            }
        }
    }

    private fun handleClient(socket: Socket) {
        try {
            socket.use { s ->
                val reader = BufferedReader(InputStreamReader(s.inputStream))
                val writer = PrintWriter(s.outputStream)

                val requestLine = reader.readLine() ?: return
                val parts = requestLine.split(" ")
                val method = parts.getOrNull(0) ?: "GET"
                val rawPath = parts.getOrNull(1) ?: "/"
                val path = rawPath.substringBefore('?')

                var contentLength = 0
                val headers = HashMap<String, String>()
                var line = reader.readLine()
                while (!line.isNullOrEmpty()) {
                    val colon = line.indexOf(':')
                    if (colon > 0) {
                        val name = line.substring(0, colon).trim()
                        val value = line.substring(colon + 1).trim()
                        headers[name] = value
                        if (name.equals("content-length", ignoreCase = true)) {
                            contentLength = value.toIntOrNull() ?: 0
                        }
                    }
                    line = reader.readLine()
                }

                val body = if (contentLength > 0) {
                    CharArray(contentLength).also { reader.read(it, 0, contentLength) }.concatToString()
                } else ""

                val request = ServerRequest(method, path, body, headers)

                // Authorisation is enforced before routing, so no route can ship unauthenticated.
                // Only the explicitly declared public paths skip it, and they are matched exactly.
                val denied = if (request.path in publicPaths) {
                    null
                } else {
                    authorizer?.invoke(request)
                }
                val response = denied
                    ?: routeHandlers[path]?.invoke(request)
                    ?: ServerResponse(404, "application/json", """{"error":"Not Found"}""")

                val bytes = response.body.toByteArray()
                writer.print("HTTP/1.1 ${response.status} ${statusText(response.status)}\r\n")
                writer.print("Content-Type: ${response.contentType}\r\n")
                writer.print("Content-Length: ${bytes.size}\r\n")
                writer.print("Access-Control-Allow-Origin: *\r\n")
                writer.print("Connection: close\r\n\r\n")
                writer.print(response.body)
                writer.flush()
            }
        } catch (_: Exception) {
        }
    }

    private fun statusText(status: Int) = when (status) {
        200 -> "OK"
        400 -> "Bad Request"
        404 -> "Not Found"
        500 -> "Internal Server Error"
        503 -> "Service Unavailable"
        else -> "OK"
    }

    fun stop() {
        try {
            serverSocket?.close()
            serverJob?.cancel()
            serverSocket = null
            _isRunning.value = false
            logger.i("EmbeddedServer", "Server stopped.")
        } catch (_: Exception) {
        }
    }

    companion object {
        /** The port the platform's network surface listens on. */
        const val DEFAULT_PORT = 8899
    }
}
