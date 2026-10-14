package com.personal.base.core.platform

import com.personal.base.core.command.CommandResult
import com.personal.base.core.command.RemoteCommand
import com.personal.base.core.logging.PlatformLogger
import java.util.concurrent.ConcurrentHashMap

typealias CommandHandler = suspend (RemoteCommand) -> CommandResult

/**
 * Dynamic command handler registry and type-based dispatcher.
 */
class CommandRouter(
    private val logger: PlatformLogger
) {
    // Key: "$target:$action"
    private val handlers = ConcurrentHashMap<String, CommandHandler>()

    fun registerHandler(target: String, action: String, handler: CommandHandler) {
        val key = "$target:$action"
        handlers[key] = handler
        logger.i("CommandRouter", "Registered handler for $key")
    }

    suspend fun route(command: RemoteCommand): CommandResult {
        val key = "${command.targetPluginId}:${command.action}"
        val handler = handlers[key]
            ?: handlers["*:${command.action}"]
            ?: return CommandResult.failure(command.id, "No handler registered for action '$key'", "UNKNOWN_ACTION")

        return try {
            handler(command)
        } catch (e: Exception) {
            logger.e("CommandRouter", "Handler failed for $key: ${e.message}", e.message)
            CommandResult.failure(command.id, "Handler execution failed: ${e.message}")
        }
    }
}
