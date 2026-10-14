package com.personal.base.core.platform

import org.json.JSONObject

data class ResultEnvelope<T>(
    val correlationId: String,
    val statusCode: Int,
    val isSuccess: Boolean,
    val data: T?,
    val errorCode: String? = null,
    val errorMessage: String? = null,
    val timestamp: Long = System.currentTimeMillis()
) {
    fun toJson(): String {
        return JSONObject().apply {
            put("cid", correlationId)
            put("status", statusCode)
            put("success", isSuccess)
            put("data", data?.toString() ?: JSONObject.NULL)
            put("errorCode", errorCode ?: JSONObject.NULL)
            put("errorMessage", errorMessage ?: JSONObject.NULL)
            put("ts", timestamp)
        }.toString()
    }
}
