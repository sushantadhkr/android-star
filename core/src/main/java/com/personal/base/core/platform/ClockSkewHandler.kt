package com.personal.base.core.platform

import com.personal.base.core.logging.PlatformLogger
import java.util.concurrent.ConcurrentHashMap

/**
 * Handles time synchronization tolerance and clock drift compensation across peers.
 */
class ClockSkewHandler(
    private val logger: PlatformLogger
) {
    // PeerId -> offsetMs (remoteTime - localTime)
    private val peerOffsets = ConcurrentHashMap<String, Long>()

    fun recordPeerTime(peerId: String, peerReportedTime: Long) {
        val now = System.currentTimeMillis()
        val offset = peerReportedTime - now
        peerOffsets[peerId] = offset
        logger.i("ClockSkewHandler", "Clock skew for peer $peerId: ${offset}ms")
    }

    fun getCompensatedTimestamp(peerId: String): Long {
        val offset = peerOffsets[peerId] ?: 0L
        return System.currentTimeMillis() + offset
    }
}
