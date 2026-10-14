package com.personal.base.core.platform

import com.personal.base.core.logging.PlatformLogger
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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

    /**
     * The server's scope.
     *
     * The exception handler matters more than it looks: an uncaught exception in a child coroutine
     * cancels the whole scope, which would leave the server permanently dead — `start()` would
     * launch into a cancelled scope and silently do nothing, so the phone could never come back
     * without a restart. Catching here means a bad connection can never take the server down.
     */
    private val scope = CoroutineScope(
        Dispatchers.IO + Job() + CoroutineExceptionHandler { _, e ->
            logger.e("EmbeddedServer", "Unhandled server error: ${e.message}", e.stackTraceToString())
        }
    )
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
        // A previous attempt may have left a dead job behind. Without this, a server that failed to
        // bind could never be restarted, because the stale job would make every retry a no-op.
        serverJob?.cancel()

        serverJob = scope.launch {
            var attempt = 0
            while (isActive) {
                attempt++
                try {
                    serverSocket = ServerSocket(port)
                    _isRunning.value = true
                    logger.i(
                        "EmbeddedServer",
                        "Server listening on port $port (${routeHandlers.size} routes, attempt $attempt)"
                    )
                    break
                } catch (e: Exception) {
                    val reason = if (e is java.net.BindException) {
                        "port $port is already in use"
                    } else {
                        e.message ?: e.javaClass.simpleName
                    }
                    logger.e("EmbeddedServer", "Could not listen on port $port: $reason", e.stackTraceToString())
                    _isRunning.value = false
                    if (attempt >= BIND_ATTEMPTS) {
                        logger.e(
                            "EmbeddedServer",
                            "Giving up after $attempt attempts. The host stays up but nothing is " +
                                "reachable until it is restarted."
                        )
                        return@launch
                    }
                    delay(BIND_RETRY_DELAY_MS)
                }
            }

            // Closing the socket is how stop() ends this, so SocketException is the normal exit
            // rather than a failure. It must be caught here: left unhandled it would propagate out
            // of the coroutine, cancel the server's scope, and leave the server unable to ever
            // start again.
            try {
                while (isActive) {
                    val client = serverSocket?.accept() ?: break
                    scope.launch { handleClient(client) }
                }
            } catch (e: java.net.SocketException) {
                logger.i("EmbeddedServer", "Listening socket closed")
            } catch (e: Exception) {
                logger.e("EmbeddedServer", "Accept loop failed: ${e.message}", e.stackTraceToString())
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

                // A CORS preflight carries no credentials — that is what a preflight is — so it
                // must be answered before authorisation, or no browser could ever call this API
                // from another origin. It runs nothing and returns no body: it only says which
                // methods and headers a real request would be allowed to use.
                //
                // Answered rather than returned early so there is no way for a preflight to fall
                // through into routing — which would let a browser's OPTIONS request invoke a
                // capability for real.
                if (method.equals("OPTIONS", ignoreCase = true)) {
                    writeResponse(writer, ServerResponse(204, "text/plain", ""), preflight = true)
                } else {
                    // Authorisation is enforced before routing, so no route can ship
                    // unauthenticated. Only the explicitly declared public paths skip it, and
                    // they are matched exactly.
                    val denied = if (request.path in publicPaths) {
                        null
                    } else {
                        authorizer?.invoke(request)
                    }
                    val response = denied
                        ?: routeHandlers[path]?.invoke(request)
                        ?: ServerResponse(404, "application/json", """{"error":"Not Found"}""")

                    writeResponse(writer, response, preflight = false)
                }
            }
        } catch (e: Exception) {
            // A dropped client is normal and not worth a line, but anything else is exactly the
            // kind of failure that is invisible until someone reports the server as "dead".
            if (e !is java.io.IOException && e !is java.net.SocketException) {
                logger.e("EmbeddedServer", "Failed to serve ${socket.inetAddress}: ${e.message}", e.stackTraceToString())
            }
        }
    }

    private fun writeResponse(writer: PrintWriter, response: ServerResponse, preflight: Boolean) {
        val bytes = response.body.toByteArray()
        writer.print("HTTP/1.1 ${response.status} ${statusText(response.status)}\r\n")
        writer.print("Content-Type: ${response.contentType}\r\n")
        writer.print("Content-Length: ${bytes.size}\r\n")
        // Same-origin callers never see this. It exists so the console also works opened from disk,
        // where the browser asks permission first. Every endpoint still requires the bearer token,
        // so this grants permission to attempt a call and nothing more.
        writer.print("Access-Control-Allow-Origin: *\r\n")
        if (preflight) {
            writer.print("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n")
            writer.print("Access-Control-Allow-Headers: Authorization, Content-Type\r\n")
            writer.print("Access-Control-Max-Age: 86400\r\n")
        }
        writer.print("Connection: close\r\n\r\n")
        writer.print(response.body)
        writer.flush()
    }

    /**
     * The reason phrase for a status.
     *
     * Anything unlisted used to fall through to "OK", putting `HTTP/1.1 401 OK` on the wire.
     * Browsers ignore it, but curl output and anyone reading a packet log are misled by it.
     */
    private fun statusText(status: Int) = when (status) {
        200 -> "OK"
        204 -> "No Content"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        403 -> "Forbidden"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        409 -> "Conflict"
        500 -> "Internal Server Error"
        501 -> "Not Implemented"
        503 -> "Service Unavailable"
        else -> "Status $status"
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

        /**
         * How hard to try before giving up on the socket.
         *
         * Retrying matters because the usual cause is another process holding the port during
         * boot, which clears on its own. Giving up loudly matters because the alternative — a host
         * that looks healthy while nothing is listening — is worse than a visible failure.
         */
        const val BIND_ATTEMPTS = 3
        const val BIND_RETRY_DELAY_MS = 2_000L
    }
}
