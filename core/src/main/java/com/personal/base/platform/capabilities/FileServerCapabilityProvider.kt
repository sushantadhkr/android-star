package com.personal.base.platform.capabilities

import android.content.Context
import com.personal.base.core.capability.CapabilityProvider
import com.personal.base.core.capability.CapabilityStatus
import com.personal.base.core.capability.CapabilityType
import com.personal.base.core.logging.PlatformLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Serializable
data class FileItem(
    val name: String,
    val sizeBytes: Long,
    val formattedSize: String,
    val lastModifiedFormatted: String,
    val isDirectory: Boolean
)

@Serializable
data class FileServerAccessLog(
    val timestamp: String,
    val clientIp: String,
    val method: String,
    val path: String,
    val statusCode: Int
)

@Serializable
data class FileServerStatus(
    val isRunning: Boolean = false,
    val port: Int = 8080,
    val serverUrl: String = "",
    val sharedDirectoryPath: String = "",
    val totalFiles: Int = 0,
    val totalRequestsServed: Long = 0,
    val totalBytesTransferred: Long = 0,
    val logs: List<FileServerAccessLog> = emptyList()
)

/**
 * Base Platform Capability Provider: Embedded HTTP File Server.
 * Runs on standard ServerSocket with zero external dependencies.
 * Platform apps consume this capability without writing any low-level networking code.
 */
class FileServerCapabilityProvider(
    private val context: Context,
    private val connectivityProvider: ConnectivityCapabilityProvider,
    private val logger: PlatformLogger
) : CapabilityProvider {

    override val type: CapabilityType = CapabilityType.FILE_SERVER

    override val status: CapabilityStatus
        get() = CapabilityStatus.Available

    private val scope = CoroutineScope(Dispatchers.IO)
    private var serverJob: Job? = null
    private var serverSocket: ServerSocket? = null

    private val sharedDir: File = File(context.filesDir, "shared_files").apply { mkdirs() }

    private val _serverState = MutableStateFlow(
        FileServerStatus(
            isRunning = false,
            port = 8080,
            sharedDirectoryPath = sharedDir.absolutePath,
            totalFiles = sharedDir.listFiles()?.size ?: 0
        )
    )
    val serverState: StateFlow<FileServerStatus> = _serverState.asStateFlow()

    private val accessLogs = mutableListOf<FileServerAccessLog>()
    private var requestsCount = 0L
    private var bytesSentTotal = 0L

    override fun initialize() {
        ensureSampleFiles()
        updateFileCount()
    }

    private fun ensureSampleFiles() {
        try {
            val readme = File(sharedDir, "welcome_to_android_star.txt")
            if (!readme.exists()) {
                readme.writeText(
                    "Welcome to Android Star Local File Hub!\n" +
                    "This file is being served directly from your Android device.\n" +
                    "Device Architecture: Modular Base Platform\n" +
                    "Timestamp: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}\n"
                )
            }

            val systemInfo = File(sharedDir, "device_manifest.json")
            if (!systemInfo.exists()) {
                systemInfo.writeText(
                    """
                    {
                      "platform": "Android Star",
                      "version": "1.0.0",
                      "engine": "Kotlin Coroutines ServerSocket",
                      "status": "ONLINE"
                    }
                    """.trimIndent()
                )
            }
        } catch (e: Exception) {
            logger.w("FileServer", "Error seeding sample files: ${e.message}")
        }
    }

    fun startServer(port: Int = 8080): Boolean {
        if (_serverState.value.isRunning) return true

        return try {
            val socket = ServerSocket()
            socket.reuseAddress = true
            socket.bind(InetSocketAddress("0.0.0.0", port))
            serverSocket = socket

            val ip = connectivityProvider.networkState.value.wifiIpAddress.ifEmpty { "127.0.0.1" }
            val url = "http://$ip:$port"

            _serverState.value = _serverState.value.copy(
                isRunning = true,
                port = port,
                serverUrl = url,
                sharedDirectoryPath = sharedDir.absolutePath,
                totalFiles = getFilesList().size
            )

            logger.i("FileServer", "File Server started successfully on $url")

            serverJob = scope.launch {
                while (isActive && !socket.isClosed) {
                    try {
                        val client = socket.accept()
                        launch { handleClient(client) }
                    } catch (e: Exception) {
                        if (!socket.isClosed) {
                            logger.w("FileServer", "Socket accept exception: ${e.message}")
                        }
                    }
                }
            }
            true
        } catch (e: Exception) {
            logger.e("FileServer", "Failed to start File Server on port $port: ${e.message}", e.message)
            false
        }
    }

    fun stopServer() {
        try {
            serverSocket?.close()
            serverJob?.cancel()
            serverSocket = null
            serverJob = null

            _serverState.value = _serverState.value.copy(
                isRunning = false,
                serverUrl = ""
            )
            logger.i("FileServer", "File Server stopped")
        } catch (e: Exception) {
            logger.w("FileServer", "Error stopping server: ${e.message}")
        }
    }

    private fun handleClient(client: Socket) {
        val clientIp = client.inetAddress.hostAddress ?: "Unknown"
        try {
            client.soTimeout = 8000
            val reader = BufferedReader(InputStreamReader(client.getInputStream()))
            val output = client.getOutputStream()

            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return

            val method = parts[0]
            val rawPath = parts[1]
            val path = URLDecoder.decode(rawPath, "UTF-8")

            requestsCount++

            when {
                path == "/" || path == "/index.html" -> {
                    serveHtmlIndex(output, clientIp, method, path)
                }
                path.startsWith("/file/") -> {
                    val fileName = path.removePrefix("/file/").trimStart('/')
                    serveFileDownload(fileName, output, clientIp, method, path)
                }
                path == "/api/files" -> {
                    serveJsonFiles(output, clientIp, method, path)
                }
                else -> {
                    send404(output, clientIp, method, path)
                }
            }
        } catch (e: Exception) {
            logger.d("FileServer", "Client handling finished or timed out: ${e.message}")
        } finally {
            try { client.close() } catch (_: Exception) {}
        }
    }

    private fun serveHtmlIndex(output: OutputStream, clientIp: String, method: String, path: String) {
        val files = getFilesList()
        val writer = PrintWriter(output, false)
        val timeStr = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())

        val html = buildString {
            append("""
                <!DOCTYPE html>
                <html lang="en">
                <head>
                    <meta charset="UTF-8">
                    <meta name="viewport" content="width=device-width, initial-scale=1.0">
                    <title>Android Star • Local File Hub</title>
                    <style>
                        body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; background: #0f172a; color: #f8fafc; margin: 0; padding: 24px; }
                        .container { max-width: 800px; margin: 0 auto; background: #1e293b; border-radius: 12px; padding: 24px; box-shadow: 0 10px 25px rgba(0,0,0,0.5); }
                        h1 { color: #38bdf8; margin-top: 0; display: flex; align-items: center; gap: 8px; }
                        .badge { background: #0284c7; color: white; padding: 4px 10px; border-radius: 12px; font-size: 13px; }
                        table { width: 100%; border-collapse: collapse; margin-top: 20px; }
                        th, td { text-align: left; padding: 12px; border-bottom: 1px solid #334155; }
                        th { color: #94a3b8; font-weight: 600; }
                        a.btn { background: #38bdf8; color: #0f172a; font-weight: 600; padding: 6px 14px; text-decoration: none; border-radius: 6px; display: inline-block; }
                        a.btn:hover { background: #7dd3fc; }
                        .meta { font-size: 13px; color: #64748b; margin-top: 20px; border-top: 1px solid #334155; padding-top: 12px; }
                    </style>
                </head>
                <body>
                    <div class="container">
                        <h1>⭐ Android Star File Server <span class="badge">ONLINE</span></h1>
                        <p>Direct peer-to-peer file transfer from this Android device.</p>
                        <table>
                            <thead>
                                <tr><th>File Name</th><th>Size</th><th>Last Modified</th><th>Action</th></tr>
                            </thead>
                            <tbody>
            """.trimIndent())

            if (files.isEmpty()) {
                append("<tr><td colspan='4'>No shared files available in folder.</td></tr>")
            } else {
                files.forEach { file ->
                    append("""
                        <tr>
                            <td><strong>${file.name}</strong></td>
                            <td>${file.formattedSize}</td>
                            <td>${file.lastModifiedFormatted}</td>
                            <td><a class="btn" href="/file/${file.name}">Download</a></td>
                        </tr>
                    """.trimIndent())
                }
            }

            append("""
                            </tbody>
                        </table>
                        <div class="meta">Connected Client: $clientIp | Server Time: $timeStr</div>
                    </div>
                </body>
                </html>
            """.trimIndent())
        }

        val bytes = html.toByteArray(Charsets.UTF_8)
        writer.print("HTTP/1.1 200 OK\r\n")
        writer.print("Content-Type: text/html; charset=UTF-8\r\n")
        writer.print("Content-Length: ${bytes.size}\r\n")
        writer.print("Connection: close\r\n\r\n")
        writer.flush()
        output.write(bytes)
        output.flush()

        recordLog(clientIp, method, path, 200, bytes.size.toLong())
    }

    private fun serveFileDownload(fileName: String, output: OutputStream, clientIp: String, method: String, path: String) {
        val target = File(sharedDir, fileName)
        if (!target.exists() || !target.isFile) {
            send404(output, clientIp, method, path)
            return
        }

        val writer = PrintWriter(output, false)
        val length = target.length()
        val mime = getMimeType(target.name)

        writer.print("HTTP/1.1 200 OK\r\n")
        writer.print("Content-Type: $mime\r\n")
        writer.print("Content-Length: $length\r\n")
        writer.print("Content-Disposition: attachment; filename=\"${target.name}\"\r\n")
        writer.print("Connection: close\r\n\r\n")
        writer.flush()

        val buffer = ByteArray(8192)
        var bytesSent = 0L
        FileInputStream(target).use { input ->
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                output.write(buffer, 0, read)
                bytesSent += read
            }
        }
        output.flush()

        recordLog(clientIp, method, path, 200, bytesSent)
    }

    private fun serveJsonFiles(output: OutputStream, clientIp: String, method: String, path: String) {
        val files = getFilesList()
        val jsonArray = buildString {
            append("[")
            files.forEachIndexed { index, item ->
                append("{\"name\":\"${item.name}\",\"size\":${item.sizeBytes},\"formatted\":\"${item.formattedSize}\"}")
                if (index < files.size - 1) append(",")
            }
            append("]")
        }

        val bytes = jsonArray.toByteArray(Charsets.UTF_8)
        val writer = PrintWriter(output, false)
        writer.print("HTTP/1.1 200 OK\r\n")
        writer.print("Content-Type: application/json; charset=UTF-8\r\n")
        writer.print("Content-Length: ${bytes.size}\r\n")
        writer.print("Connection: close\r\n\r\n")
        writer.flush()
        output.write(bytes)
        output.flush()

        recordLog(clientIp, method, path, 200, bytes.size.toLong())
    }

    private fun send404(output: OutputStream, clientIp: String, method: String, path: String) {
        val body = "404 Not Found - Android Star File Server\n"
        val bytes = body.toByteArray(Charsets.UTF_8)
        val writer = PrintWriter(output, false)
        writer.print("HTTP/1.1 404 Not Found\r\n")
        writer.print("Content-Type: text/plain\r\n")
        writer.print("Content-Length: ${bytes.size}\r\n")
        writer.print("Connection: close\r\n\r\n")
        writer.flush()
        output.write(bytes)
        output.flush()

        recordLog(clientIp, method, path, 404, bytes.size.toLong())
    }

    private fun recordLog(ip: String, method: String, path: String, status: Int, bytes: Long) {
        bytesSentTotal += bytes
        val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        val log = FileServerAccessLog(time, ip, method, path, status)
        synchronized(accessLogs) {
            accessLogs.add(0, log)
            if (accessLogs.size > 50) accessLogs.removeAt(accessLogs.size - 1)
        }
        _serverState.value = _serverState.value.copy(
            totalRequestsServed = requestsCount,
            totalBytesTransferred = bytesSentTotal,
            logs = accessLogs.toList()
        )
    }

    fun getFilesList(): List<FileItem> {
        val list = sharedDir.listFiles() ?: return emptyList()
        val sdf = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())
        return list.map { file ->
            FileItem(
                name = file.name,
                sizeBytes = file.length(),
                formattedSize = formatBytes(file.length()),
                lastModifiedFormatted = sdf.format(Date(file.lastModified())),
                isDirectory = file.isDirectory
            )
        }.sortedByDescending { it.lastModifiedFormatted }
    }

    fun addCustomFile(name: String, content: String): Boolean {
        return try {
            val file = File(sharedDir, name)
            file.writeText(content)
            updateFileCount()
            true
        } catch (e: Exception) {
            logger.e("FileServer", "Failed to add custom file: ${e.message}", e.message)
            false
        }
    }

    fun deleteFile(name: String): Boolean {
        return try {
            val file = File(sharedDir, name)
            val deleted = file.delete()
            updateFileCount()
            deleted
        } catch (_: Exception) {
            false
        }
    }

    private fun updateFileCount() {
        _serverState.value = _serverState.value.copy(totalFiles = getFilesList().size)
    }

    private fun getMimeType(name: String): String {
        return when {
            name.endsWith(".txt", true) -> "text/plain"
            name.endsWith(".json", true) -> "application/json"
            name.endsWith(".html", true) -> "text/html"
            name.endsWith(".pdf", true) -> "application/pdf"
            name.endsWith(".png", true) -> "image/png"
            name.endsWith(".jpg", true) || name.endsWith(".jpeg", true) -> "image/jpeg"
            name.endsWith(".mp4", true) -> "video/mp4"
            name.endsWith(".zip", true) -> "application/zip"
            else -> "application/octet-stream"
        }
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val exp = (Math.log(bytes.toDouble()) / Math.log(1024.0)).toInt()
        val pre = "KMGTPE"[exp - 1]
        return String.format(Locale.US, "%.1f %cB", bytes / Math.pow(1024.0, exp.toDouble()), pre)
    }

    override fun release() {
        stopServer()
    }
}
