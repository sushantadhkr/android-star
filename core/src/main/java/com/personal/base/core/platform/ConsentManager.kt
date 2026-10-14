package com.personal.base.core.platform

import com.personal.base.core.logging.PlatformLogger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class ConsentPrompt(
    val id: String,
    val peerId: String,
    val action: String,
    val description: String,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Manages user approval consent prompts for sensitive incoming commands with timeout.
 */
class ConsentManager(
    private val logger: PlatformLogger
) {
    private val _pendingPrompts = MutableSharedFlow<ConsentPrompt>(extraBufferCapacity = 16)
    val pendingPrompts: SharedFlow<ConsentPrompt> = _pendingPrompts.asSharedFlow()

    private val pendingResults = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    suspend fun requestConsent(peerId: String, action: String, description: String, timeoutMs: Long = 30_000L): Boolean {
        val promptId = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<Boolean>()
        pendingResults[promptId] = deferred

        val prompt = ConsentPrompt(promptId, peerId, action, description)
        _pendingPrompts.emit(prompt)
        logger.i("ConsentManager", "Awaiting user consent for [$action] from $peerId...")

        val result = withTimeoutOrNull(timeoutMs) {
            deferred.await()
        } ?: false

        pendingResults.remove(promptId)
        logger.i("ConsentManager", "Consent result for [$action]: $result")
        return result
    }

    fun submitUserDecision(promptId: String, approved: Boolean) {
        pendingResults[promptId]?.complete(approved)
    }
}
