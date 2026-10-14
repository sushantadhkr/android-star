package com.personal.base.core.platform

import com.personal.base.core.logging.PlatformLogger
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

data class PlatformEvent(
    val type: String,
    val payload: Map<String, Any>,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * High-throughput asynchronous publish/subscribe event bus between platform modules and apps.
 */
class EventBus(
    private val logger: PlatformLogger
) {
    private val _events = MutableSharedFlow<PlatformEvent>(extraBufferCapacity = 128)
    val events: SharedFlow<PlatformEvent> = _events.asSharedFlow()

    fun publish(event: PlatformEvent) {
        val result = _events.tryEmit(event)
        if (!result) {
            logger.w("EventBus", "Event buffer full, dropping event: ${event.type}")
        }
    }
}
