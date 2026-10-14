package com.personal.base.core.platform

import com.personal.base.core.logging.PlatformLogger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Allows subscribing to capability events occurring on connected remote peer devices.
 */
class RemoteEventSubscription(
    private val logger: PlatformLogger
) {
    // Key: "$peerId:$eventType" -> list of subscriber callbacks
    private val subscriptions = ConcurrentHashMap<String, CopyOnWriteArrayList<(PlatformEvent) -> Unit>>()

    fun subscribe(peerId: String, eventType: String, callback: (PlatformEvent) -> Unit) {
        val key = "$peerId:$eventType"
        subscriptions.computeIfAbsent(key) { CopyOnWriteArrayList() }.add(callback)
        logger.i("RemoteEventSubscription", "Subscribed to remote events for $key")
    }

    fun dispatchRemoteEvent(peerId: String, event: PlatformEvent) {
        val key = "$peerId:${event.type}"
        subscriptions[key]?.forEach { cb ->
            try { cb(event) } catch (_: Exception) {}
        }
    }
}
