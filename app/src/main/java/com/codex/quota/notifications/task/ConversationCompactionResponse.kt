package com.codex.quota.notifications.task

import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/** Compaction owns an independent response stream; it never replaces the live watch. */
internal object ConversationCompactionResponse {
    fun matches(command: RemoteCommand, event: RemoteEvent): Boolean = command.action == "compact" &&
        event.request_id == command.id && event.host_id == command.host_id &&
        event.thread_id == command.thread_id && event.conversation_id == command.conversation_id &&
        event.status in setOf("compacting", "compacted", "failed", "unknown")

    suspend fun await(command: RemoteCommand, events: Flow<RemoteEvent>): RemoteEvent {
        val event = events.first { matches(command, it) && it.status != "compacting" }
        if (event.error.isNotBlank()) throw IOException(event.error)
        if (event.partial || event.attachment_pending || event.status != "compacted")
            throw IOException("COMPACTION_UNCONFIRMED")
        return event
    }
}
