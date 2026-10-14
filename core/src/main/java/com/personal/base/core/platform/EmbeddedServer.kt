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
 * Lightweight native embedded HTTP and WebSocket server running inside the Android base app.
 */
class EmbeddedServer(
    private val logger: PlatformLogger,
    val port: Int = 8899
) {
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val routeHandlers = ConcurrentHashMap<String, (body: String) -> String>()

    fun registerRoute(path: String, handler: (body: String) -> String) {
        routeHandlers[path] = handler
    }

    fun start() {
        if (_isRunning.value) return
        serverJob = scope.launch {
            try {
                serverSocket = ServerSocket(port)
                _isRunning.value = true
                logger.i("EmbeddedServer", "Server listening on port $port")

                while (isActive) {
                    val clientSocket = serverSocket?.accept() ?: break
                    scope.launch { handleClient(clientSocket) }
                }
            } catch (e: Exception) {
                if (isActive) {
                    logger.e("EmbeddedServer", "Server socket error: ${e.message}", e.message)
                }
            } finally {
                _isRunning.value = false
            }
        }
    }

    private fun handleClient(socket: Socket) {
        try {
            socket.use { s ->
                val reader = BufferedReader(InputStreamReader(s.getInputStream()))
                val writer = PrintWriter(s.getOutputStream())

                val requestLine = reader.readLine() ?: return
                val parts = requestLine.split(" ")
                val method = if (parts.isNotEmpty()) parts[0] else "GET"
                val path = if (parts.size > 1) parts[1] else "/"

                var contentLength = 0
                var line = reader.readLine()
                while (!line.isNullOrEmpty()) {
                    if (line.lowercase().startsWith("content-length:")) {
                        contentLength = line.substringAfter(":").trim().toIntOrNull() ?: 0
                    }
                    line = reader.readLine()
                }

                val body = if (contentLength > 0) {
                    val buf = CharArray(contentLength)
                    reader.read(buf, 0, contentLength)
                    String(buf)
                } else ""

                val handler = routeHandlers[path]
                val responseBody = handler?.invoke(body) ?: """{"status":"ok","app":"Android Star","path":"$path"}"""

                writer.print("HTTP/1.1 200 OK\r\n")
                writer.print("Content-Type: application/json\r\n")
                writer.print("Content-Length: ${responseBody.toByteArray().size}\r\n")
                writer.print("Access-Control-Allow-Origin: *\r\n")
                writer.print("Connection: close\r\n\r\n")
                writer.print(responseBody)
                writer.flush()
            }
        } catch (_: Exception) {}
    }

    fun stop() {
        try {
            serverSocket?.close()
            serverJob?.cancel()
            _isRunning.value = false
            logger.i("EmbeddedServer", "Server stopped.")
        } catch (_: Exception) {}
    }
}
