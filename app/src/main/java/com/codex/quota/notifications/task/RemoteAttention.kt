package com.codex.quota.notifications.task

/** A replay can notify only while the exact interaction is still awaiting an answer. */
object RemoteAttention {
    fun matches(state: RemoteConversationState, event: RemoteEvent): Boolean {
        val current = state.event ?: return false
        if (event.status != current.status ||
            event.request_id != state.command.id || event.thread_id != state.command.thread_id ||
            event.host_id != state.command.host_id || event.conversation_id != state.command.conversation_id ||
            event.turn_id.isBlank() || event.turn_id != current.turn_id) return false
        return when (event.status) {
            "input_required" -> RemoteInputRules.ready(event) && RemoteInputRules.ready(current) && event.user_input?.id == current.user_input?.id
            "approval" -> !event.attachment_pending && !current.attachment_pending && event.approval_id.isNotBlank() && event.approval_id == current.approval_id
            else -> false
        }
    }
    fun identity(event: RemoteEvent): String = "remote-attention:" + TaskInbox.hash(event.request_id + ":" + event.turn_id + ":" +
        (event.user_input?.id ?: event.approval_id))
}
