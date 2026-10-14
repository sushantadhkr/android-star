package com.personal.base.core.platform

import com.personal.base.core.command.RemoteCommand
import com.personal.base.core.logging.PlatformLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentLinkedQueue

data class QueuedCommand(
    val command: RemoteCommand,
    val targetPeerId: String,
    val enqueuedAt: Long = System.currentTimeMillis(),
    var retryCount: Int = 0,
    var nextRetryAt: Long = System.currentTimeMillis()
)

/**
 * Offline command queue persisting commands until peer connectivity is established,
 * retrying with exponential backoff.
 */
class OutboxQueue(
    private val logger: PlatformLogger
) {
    private val queue = ConcurrentLinkedQueue<QueuedCommand>()
    private val _queueSize = MutableStateFlow(0)
    val queueSize: StateFlow<Int> = _queueSize.asStateFlow()

    fun enqueue(command: RemoteCommand, targetPeerId: String) {
        queue.add(QueuedCommand(command, targetPeerId))
        _queueSize.value = queue.size
        logger.i("OutboxQueue", "Enqueued command ${command.id} for peer $targetPeerId (queue size: ${queue.size})")
    }

    fun pollReadyCommands(): List<QueuedCommand> {
        val now = System.currentTimeMillis()
        val ready = mutableListOf<QueuedCommand>()
        val it = queue.iterator()
        while (it.hasNext()) {
            val item = it.next()
            if (now >= item.nextRetryAt) {
                ready.add(item)
                it.remove()
            }
        }
        _queueSize.value = queue.size
        return ready
    }

    fun reEnqueueWithBackoff(item: QueuedCommand) {
        item.retryCount++
        val backoffMs = (1000L * (1 shl item.retryCount.coerceAtMost(6)))
        item.nextRetryAt = System.currentTimeMillis() + backoffMs
        queue.add(item)
        _queueSize.value = queue.size
        logger.w("OutboxQueue", "Re-enqueued command ${item.command.id} with retry #${item.retryCount} in ${backoffMs}ms")
    }
}
