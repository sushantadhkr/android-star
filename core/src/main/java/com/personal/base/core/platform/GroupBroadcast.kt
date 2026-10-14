package com.personal.base.core.platform

import com.personal.base.core.command.CommandResult
import com.personal.base.core.command.RemoteCommand
import com.personal.base.core.logging.PlatformLogger
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Broadcasts commands or messages to groups of peers simultaneously and aggregates results.
 */
class GroupBroadcast(
    private val logger: PlatformLogger
) {
    suspend fun broadcastCommand(
        peers: List<String>,
        command: RemoteCommand,
        dispatch: suspend (peerId: String, cmd: RemoteCommand) -> CommandResult
    ): Map<String, CommandResult> = coroutineScope {
        logger.i("GroupBroadcast", "Broadcasting command ${command.action} to ${peers.size} peers...")
        peers.map { peerId ->
            async {
                peerId to dispatch(peerId, command)
            }
        }.awaitAll().toMap()
    }
}
