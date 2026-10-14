package com.personal.base.core.command

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Access levels for command execution and capability access.
 */
enum class UserRole {
    GUEST,
    AUTHENTICATED,
    ADMIN
}

/**
 * Standard remote command payload sent to the platform.
 */
@Serializable
data class RemoteCommand(
    val id: String,
    val targetPluginId: String,
    val action: String,
    val params: Map<String, String> = emptyMap(),
    val senderId: String = "local-tester",
    val role: UserRole = UserRole.AUTHENTICATED,
    val timestamp: Long = System.currentTimeMillis(),
    val signature: String? = null,
    val ttlSeconds: Long = 300L
)

/**
 * Result returned after command execution.
 */
@Serializable
data class CommandResult(
    val commandId: String,
    val isSuccess: Boolean,
    val message: String,
    val output: Map<String, String> = emptyMap(),
    val executionTimeMs: Long = 0L,
    val errorCode: String? = null
) {
    companion object {
        fun success(commandId: String, message: String, output: Map<String, String> = emptyMap(), durationMs: Long = 0L) =
            CommandResult(commandId, true, message, output, durationMs)

        fun failure(commandId: String, message: String, errorCode: String = "EXECUTION_ERROR", durationMs: Long = 0L) =
            CommandResult(commandId, false, message, emptyMap(), durationMs, errorCode)
    }
}
