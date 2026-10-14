package com.personal.base.core.platform

import com.personal.base.core.logging.PlatformLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket
import javax.net.ssl.SSLSocket

/**
 * Low-level TLS TCP and UDP broadcast transport layer.
 */
class RawSocketTransport(
    private val tlsCertManager: TlsCertManager,
    private val logger: PlatformLogger
) {
    suspend fun sendUdpBroadcast(data: ByteArray, port: Int, broadcastAddress: String = "255.255.255.255") = withContext(Dispatchers.IO) {
        try {
            DatagramSocket().use { socket ->
                socket.broadcast = true
                val address = InetAddress.getByName(broadcastAddress)
                val packet = DatagramPacket(data, data.size, address, port)
                socket.send(packet)
                logger.i("RawSocketTransport", "Sent UDP broadcast (${data.size} bytes) to port $port")
            }
        } catch (e: Exception) {
            logger.e("RawSocketTransport", "Failed to send UDP broadcast: ${e.message}", e.message)
        }
    }

    suspend fun connectSecureTcp(host: String, port: Int): Socket = withContext(Dispatchers.IO) {
        val sslSocketFactory = tlsCertManager.getPlatformSslContext().socketFactory
        val socket = sslSocketFactory.createSocket(host, port) as SSLSocket
        socket.startHandshake()
        logger.i("RawSocketTransport", "Established mutual TLS socket to $host:$port")
        socket
    }
}
