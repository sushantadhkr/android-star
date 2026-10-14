package com.personal.base.core.platform

import com.personal.base.core.logging.PlatformLogger
import org.json.JSONObject

data class PlatformMessageEnvelope(
    val version: Int = 1,
    val messageId: String,
    val correlationId: String?,
    val senderId: String,
    val recipientId: String,
    val type: String,
    val payloadJson: String,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Message schema definition and serialization/deserialization protocol layer.
 */
class ProtocolLayer(
    private val logger: PlatformLogger
) {
    fun serialize(envelope: PlatformMessageEnvelope): String {
        return JSONObject().apply {
            put("v", envelope.version)
            put("id", envelope.messageId)
            put("cid", envelope.correlationId ?: JSONObject.NULL)
            put("from", envelope.senderId)
            put("to", envelope.recipientId)
            put("type", envelope.type)
            put("payload", envelope.payloadJson)
            put("ts", envelope.timestamp)
        }.toString()
    }

    fun deserialize(jsonStr: String): PlatformMessageEnvelope {
        val root = JSONObject(jsonStr)
        return PlatformMessageEnvelope(
            version = root.optInt("v", 1),
            messageId = root.getString("id"),
            correlationId = if (root.isNull("cid")) null else root.optString("cid"),
            senderId = root.getString("from"),
            recipientId = root.getString("to"),
            type = root.getString("type"),
            payloadJson = root.getString("payload"),
            timestamp = root.getLong("ts")
        )
    }
}
