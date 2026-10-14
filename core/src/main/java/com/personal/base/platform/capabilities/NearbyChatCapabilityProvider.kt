package com.personal.base.platform.capabilities

import android.content.Context
import com.personal.base.core.capability.CapabilityProvider
import com.personal.base.core.capability.CapabilityStatus
import com.personal.base.core.capability.CapabilityType
import com.personal.base.core.logging.PlatformLogger
import com.personal.base.core.security.DeviceSigner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

enum class PeerAuthDecision {
    ALWAYS,
    THIS_TIME_ONLY,
    REJECT
}

data class PeerAuthRequest(
    val peerId: String,
    val peerName: String,
    val ipAddress: String,
    val deviceFingerprint: String
)

@Serializable
data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val senderId: String,
    val senderName: String,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val formattedTime: String = "",
    val isOutgoing: Boolean = false,
    val signature: String = "",
    val publicKey: String = "",
    val isVerified: Boolean = false
)

@Serializable
data class ChatPeer(
    val id: String,
    val name: String,
    val address: String,
    val isConnected: Boolean,
    val trustLevel: String // "ALWAYS", "SESSION", "PENDING"
)

/**
 * Base Platform Capability Provider: Nearby & Local Wi-Fi / Bluetooth P2P Chat Socket.
 * All connections must pass user security consent (Always or This Time Only).
 * Fully integrated with DeviceSigner for end-to-end cryptographic verification.
 */
class NearbyChatCapabilityProvider(
    private val context: Context,
    private val deviceSigner: DeviceSigner,
    private val connectivityProvider: ConnectivityCapabilityProvider,
    private val logger: PlatformLogger
) : CapabilityProvider {

    override val type: CapabilityType = CapabilityType.NEARBY_CHAT

    override val status: CapabilityStatus
        get() = CapabilityStatus.Available

    private val scope = CoroutineScope(Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }
    private val prefs = context.getSharedPreferences("android_star_chat_prefs", Context.MODE_PRIVATE)

    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null

    private val _isServerRunning = MutableStateFlow(false)
    val isServerRunning: StateFlow<Boolean> = _isServerRunning.asStateFlow()

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _activePeers = MutableStateFlow<List<ChatPeer>>(emptyList())
    val activePeers: StateFlow<List<ChatPeer>> = _activePeers.asStateFlow()

    private val _pendingAuthRequest = MutableStateFlow<PeerAuthRequest?>(null)
    val pendingAuthRequest: StateFlow<PeerAuthRequest?> = _pendingAuthRequest.asStateFlow()

    // Sockets connected to peers (peerId -> Socket)
    private val peerSockets = mutableMapOf<String, Socket>()
    private val peerWriters = mutableMapOf<String, PrintWriter>()

    // In-memory trust set for "THIS TIME ONLY"
    private val sessionTrustedFingerprints = mutableSetOf<String>()

    override fun initialize() {
        addSystemMessage("Star Chat Initialized. Local device ID: ${deviceSigner.identity.deviceId}")
    }

    fun startServer(port: Int = 9090): Boolean {
        if (_isServerRunning.value) return true

        return try {
            val s = ServerSocket()
            s.reuseAddress = true
            s.bind(InetSocketAddress("0.0.0.0", port))
            serverSocket = s
            _isServerRunning.value = true

            logger.i("NearbyChat", "Chat Socket Server listening on port $port")

            serverJob = scope.launch {
                while (isActive && !s.isClosed) {
                    try {
                        val clientSocket = s.accept()
                        launch { handleIncomingPeerConnection(clientSocket) }
                    } catch (e: Exception) {
                        if (!s.isClosed) {
                            logger.w("NearbyChat", "Error accepting peer socket: ${e.message}")
                        }
                    }
                }
            }
            true
        } catch (e: Exception) {
            logger.e("NearbyChat", "Failed to bind chat server socket on port $port: ${e.message}", e.message)
            false
        }
    }

    fun stopServer() {
        try {
            serverSocket?.close()
            serverJob?.cancel()
            serverSocket = null
            serverJob = null
            _isServerRunning.value = false

            peerSockets.values.forEach { try { it.close() } catch (_: Exception) {} }
            peerSockets.clear()
            peerWriters.clear()
            _activePeers.value = emptyList()

            logger.i("NearbyChat", "Chat Server stopped")
        } catch (e: Exception) {
            logger.w("NearbyChat", "Error stopping chat server: ${e.message}")
        }
    }

    fun connectToPeer(ip: String, port: Int = 9090, peerDisplayName: String = "Peer Device") {
        scope.launch {
            try {
                logger.i("NearbyChat", "Connecting to peer at $ip:$port...")
                val socket = Socket()
                socket.connect(InetSocketAddress(ip, port), 5000)

                val peerId = "peer-$ip-$port"
                val writer = PrintWriter(socket.getOutputStream(), true)
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))

                // Send handshake with our identity
                val handshake = json.encodeToString(
                    mapOf(
                        "type" to "HANDSHAKE",
                        "deviceId" to deviceSigner.identity.deviceId,
                        "fingerprint" to deviceSigner.identity.fingerprint,
                        "publicKey" to deviceSigner.identity.publicKeyBase64
                    )
                )
                writer.println(handshake)

                peerSockets[peerId] = socket
                peerWriters[peerId] = writer

                updatePeerList(peerId, peerDisplayName, "$ip:$port", true, "ACTIVE")
                addSystemMessage("Connected to peer $peerDisplayName ($ip:$port)")

                listenToSocket(socket, reader, peerId, peerDisplayName)
            } catch (e: Exception) {
                logger.e("NearbyChat", "Could not connect to peer at $ip:$port: ${e.message}", e.message)
                addSystemMessage("Failed to connect to $ip:$port: ${e.message}")
            }
        }
    }

    private fun handleIncomingPeerConnection(socket: Socket) {
        val ip = socket.inetAddress.hostAddress ?: "Unknown"
        val port = socket.port
        val peerId = "peer-$ip-$port"

        try {
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val writer = PrintWriter(socket.getOutputStream(), true)

            // Read handshake
            val firstLine = reader.readLine() ?: return
            val handshakeData = try {
                json.decodeFromString<Map<String, String>>(firstLine)
            } catch (_: Exception) {
                emptyMap()
            }

            val remoteName = handshakeData["deviceId"] ?: "Device-$ip"
            val fingerprint = handshakeData["fingerprint"] ?: "UNKNOWN-FINGERPRINT"

            // Check authorization gate
            if (!isPeerAuthorized(fingerprint)) {
                logger.i("NearbyChat", "Unauthorized peer connecting: $remoteName ($fingerprint). Prompting user...")
                
                // Set pending auth request
                _pendingAuthRequest.value = PeerAuthRequest(
                    peerId = peerId,
                    peerName = remoteName,
                    ipAddress = ip,
                    deviceFingerprint = fingerprint
                )

                // Wait until authorized
                var timeoutSec = 30
                while (_pendingAuthRequest.value != null && timeoutSec > 0) {
                    Thread.sleep(1000)
                    timeoutSec--
                }

                if (!isPeerAuthorized(fingerprint)) {
                    logger.w("NearbyChat", "Peer connection rejected or timed out: $remoteName")
                    writer.println("REJECTED: Unauthorized")
                    socket.close()
                    return
                }
            }

            // Successfully authorized!
            peerSockets[peerId] = socket
            peerWriters[peerId] = writer
            updatePeerList(peerId, remoteName, "$ip:$port", true, "CONNECTED")
            addSystemMessage("Peer $remoteName connected from $ip")

            listenToSocket(socket, reader, peerId, remoteName)
        } catch (e: Exception) {
            logger.d("NearbyChat", "Incoming connection handling completed: ${e.message}")
        }
    }

    private fun listenToSocket(socket: Socket, reader: BufferedReader, peerId: String, peerName: String) {
        try {
            while (!socket.isClosed) {
                val line = reader.readLine() ?: break
                handleIncomingMessageLine(line, peerName)
            }
        } catch (_: Exception) {
        } finally {
            peerSockets.remove(peerId)
            peerWriters.remove(peerId)
            updatePeerList(peerId, peerName, "", false, "DISCONNECTED")
            addSystemMessage("Peer $peerName disconnected")
        }
    }

    private fun handleIncomingMessageLine(line: String, peerName: String) {
        try {
            val msg = json.decodeFromString<ChatMessage>(line)
            val timeStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(msg.timestamp))

            // Verify cryptographic signature if available
            val isVerified = if (msg.signature.isNotEmpty() && msg.publicKey.isNotEmpty()) {
                deviceSigner.verify(msg.content.toByteArray(Charsets.UTF_8), msg.signature, msg.publicKey)
            } else {
                false
            }

            val finalMsg = msg.copy(
                formattedTime = timeStr,
                isOutgoing = false,
                isVerified = isVerified
            )

            _messages.value = _messages.value + finalMsg
            logger.i("NearbyChat", "Received message from ${msg.senderName} (Verified: $isVerified): ${msg.content}")
        } catch (e: Exception) {
            logger.w("NearbyChat", "Could not parse incoming chat line: ${e.message}")
        }
    }

    fun sendMessage(content: String): Boolean {
        if (content.isBlank()) return false

        // Reporting success with nobody listening would be the worst kind of lie here: the caller
        // would tell a peer something was sent when no socket was ever written to. Refuse, and say
        // why, instead of pretending.
        if (peerWriters.isEmpty()) {
            logger.w("NearbyChat", "Refusing to send: no peers are connected")
            return false
        }

        val timeStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        val signature = deviceSigner.signString(content)
        val outgoingMsg = ChatMessage(
            senderId = deviceSigner.identity.deviceId,
            senderName = "Me (${deviceSigner.identity.deviceId.take(9)})",
            content = content,
            formattedTime = timeStr,
            isOutgoing = true,
            signature = signature,
            publicKey = deviceSigner.identity.publicKeyBase64,
            isVerified = true
        )

        _messages.value = _messages.value + outgoingMsg

        scope.launch {
            val encoded = json.encodeToString(outgoingMsg)
            peerWriters.values.forEach { writer ->
                try {
                    writer.println(encoded)
                } catch (e: Exception) {
                    logger.w("NearbyChat", "Error sending message to peer: ${e.message}")
                }
            }
        }
        return true
    }

    fun resolveAuthRequest(decision: PeerAuthDecision) {
        val req = _pendingAuthRequest.value ?: return
        when (decision) {
            PeerAuthDecision.ALWAYS -> {
                prefs.edit().putString("trusted_${req.deviceFingerprint}", req.peerName).apply()
                addSystemMessage("Peer ${req.peerName} permanently trusted.")
            }
            PeerAuthDecision.THIS_TIME_ONLY -> {
                sessionTrustedFingerprints.add(req.deviceFingerprint)
                addSystemMessage("Peer ${req.peerName} allowed for this session only.")
            }
            PeerAuthDecision.REJECT -> {
                addSystemMessage("Connection from ${req.peerName} rejected.")
            }
        }
        _pendingAuthRequest.value = null
    }

    private fun isPeerAuthorized(fingerprint: String): Boolean {
        if (sessionTrustedFingerprints.contains(fingerprint)) return true
        return prefs.contains("trusted_$fingerprint")
    }

    fun clearTrustedPeers() {
        prefs.edit().clear().apply()
        sessionTrustedFingerprints.clear()
        addSystemMessage("All trusted peer authorizations cleared.")
    }

    private fun updatePeerList(id: String, name: String, address: String, connected: Boolean, trust: String) {
        val current = _activePeers.value.toMutableList()
        current.removeAll { it.id == id }
        if (connected) {
            current.add(ChatPeer(id, name, address, true, trust))
        }
        _activePeers.value = current
    }

    private fun addSystemMessage(text: String) {
        val timeStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        val sysMsg = ChatMessage(
            senderId = "SYSTEM",
            senderName = "System",
            content = text,
            formattedTime = timeStr,
            isOutgoing = false,
            isVerified = true
        )
        _messages.value = _messages.value + sysMsg
    }

    override fun release() {
        stopServer()
    }
}
