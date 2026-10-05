package com.codex.quota.notifications.task

import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/** A small authenticated receipt never waits for the conversation snapshot download. */
internal object RemoteFollowUpReceipt {
    fun matches(command: RemoteCommand, event: RemoteEvent): Boolean =
        command.action == "steer" && event.request_id == command.id && event.host_id == command.host_id &&
            event.thread_id == command.thread_id && event.conversation_id == command.conversation_id &&
            event.status in setOf("steered", "failed", "unknown")

    suspend fun await(command: RemoteCommand, events: Flow<RemoteEvent>): RemoteEvent {
        val event = events.first { matches(command, it) }
        if (event.status != "steered" || event.error.isNotBlank() || event.partial || event.attachment_pending ||
            event.turn_id != command.expected_turn_id || event.parent_request_id != command.target_id)
            throw IOException("REMOTE_UNCONFIRMED")
        return event
    }
}
