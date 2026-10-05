package com.codex.quota.notifications.task

import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/** Management owns its response stream; the list's catalog stream is a different request. */
internal object ConversationManagementResponse {
    fun matches(command: RemoteCommand, event: RemoteEvent): Boolean =
        command.action in setOf("rename", "archive", "unarchive") &&
            event.request_id == command.id && event.host_id == command.host_id &&
            event.thread_id == command.thread_id && event.conversation_id == command.conversation_id &&
            event.status in setOf("managed", "failed", "unknown")

    suspend fun await(command: RemoteCommand, events: Flow<RemoteEvent>): RemoteEvent {
        val event = events.first { matches(command, it) }
        if (event.error.isNotBlank()) throw IOException(event.error)
        if (event.partial || event.attachment_pending || event.status != "managed" ||
            event.managed_thread_id != command.read_thread_id || event.managed_action != command.action)
            throw IOException("MANAGEMENT_UNCONFIRMED")
        return event
    }
}
