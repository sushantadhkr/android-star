package com.personal.base.core.platform

import com.personal.base.core.capability.CapabilityType
import com.personal.base.core.logging.PlatformLogger
import org.json.JSONArray
import org.json.JSONObject

data class PeerCapabilityManifest(
    val peerId: String,
    val platformVersion: String,
    val supportedCapabilities: List<String>,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Handles capability negotiation, semantic version exchange, and feature handshake between peers.
 */
class CapabilityNegotiation(
    private val logger: PlatformLogger
) {
    fun createLocalManifest(peerId: String, capabilities: List<CapabilityType>): String {
        return JSONObject().apply {
            put("peerId", peerId)
            put("platformVersion", "1.0.0")
            put("timestamp", System.currentTimeMillis())
            val arr = JSONArray()
            capabilities.forEach { arr.put(it.name) }
            put("capabilities", arr)
        }.toString()
    }

    fun parseRemoteManifest(jsonStr: String): PeerCapabilityManifest {
        val root = JSONObject(jsonStr)
        val arr = root.getJSONArray("capabilities")
        val caps = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            caps.add(arr.getString(i))
        }

        return PeerCapabilityManifest(
            peerId = root.getString("peerId"),
            platformVersion = root.optString("platformVersion", "1.0.0"),
            supportedCapabilities = caps,
            timestamp = root.optLong("timestamp", System.currentTimeMillis())
        )
    }

    fun intersect(local: List<CapabilityType>, remote: PeerCapabilityManifest): List<String> {
        val remoteSet = remote.supportedCapabilities.toSet()
        val common = local.map { it.name }.filter { remoteSet.contains(it) }
        logger.i("CapabilityNegotiation", "Negotiated ${common.size} shared capabilities with ${remote.peerId}")
        return common
    }
}
