package com.personal.base.core.runtime

import com.personal.base.core.logging.PlatformLogger
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

data class PushMessage(
    val messageId: String,
    val senderId: String,
    val payloadJson: String,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Push messaging channel abstraction supporting UnifiedPush, FCM, or direct peer wake packets.
 */
class PushMessaging(
    private val logger: PlatformLogger
) {
    private val _incomingMessages = MutableSharedFlow<PushMessage>(extraBufferCapacity = 64)
    val incomingMessages: SharedFlow<PushMessage> = _incomingMessages.asSharedFlow()

    private var pushToken: String? = null

    fun setRegistrationToken(token: String) {
        pushToken = token
        logger.i("PushMessaging", "Push token registered: ${token.take(8)}...")
    }

    fun getRegistrationToken(): String? = pushToken

    suspend fun onMessageReceived(messageId: String, senderId: String, payloadJson: String) {
        val msg = PushMessage(messageId, senderId, payloadJson)
        logger.i("PushMessaging", "Received push message [$messageId] from $senderId")
        _incomingMessages.emit(msg)
    }
}
