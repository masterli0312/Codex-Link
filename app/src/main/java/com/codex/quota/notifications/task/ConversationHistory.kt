package com.codex.quota.notifications.task

/** Server pages are oldest first; de-duplicate by item identity, never by identical message text. */
object ConversationHistory {
    fun activeInput(state: RemoteConversationState, children: List<RemoteConversationState>): String {
        val event = state.event
        if (event?.attachment_pending != true && !event?.active_input.isNullOrBlank()) return event!!.active_input
        val active = children.filter { it.command.action == "queue" && it.event?.parent_request_id == state.command.id &&
            it.event.status in setOf("accepted", "running") && (event?.status == "accepted" || it.event.turn_id == event?.turn_id) }
        return active.singleOrNull()?.command?.text ?: state.command.text
    }
    fun key(message: TaskConversationMessage, messages: List<TaskConversationMessage>): String =
        if (message.id.isNotBlank()) "message:" + message.id else
            "legacy:" + TaskInbox.hash(message.role + ":" + message.text) + ":" + messages.indexOfFirst { it === message }
    fun merge(older: List<TaskConversationMessage>, newer: List<TaskConversationMessage>): List<TaskConversationMessage> {
        val newIds = newer.map { it.id }.filter { it.isNotBlank() }.toSet()
        return older.filter { it.id.isBlank() || it.id !in newIds } + newer
    }
}
