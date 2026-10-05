package com.codex.quota.notifications.task

/** Follow-ups are independent requests; their acknowledgements cannot finish the parent turn. */
object FollowUpState {
    /** Accepted/running means the text is already in the conversation, not waiting to be sent. */
    fun visible(state: RemoteConversationState): Boolean = state.command.action != "cancel_queue" &&
        !(state.event?.status == "cancelled" && state.event.error.isBlank()) &&
        (state.event?.attachment_pending == true || state.event?.status !in setOf("accepted", "running", "completed", "interrupted", "steered", "cancelled") || recoverable(state))
    fun pending(state: RemoteConversationState): Boolean = state.event?.status !in
        setOf("completed", "interrupted", "failed", "cancelled", "steered", "unknown")
    fun queued(state: RemoteConversationState): Boolean = state.command.action == "queue" &&
        state.event?.status == "queued"
    /** The server rejected the malformed read-watch flag before any steering effect. */
    fun retryable(state: RemoteConversationState): Boolean = state.command.watch &&
        state.event?.status == "unknown" && state.event.error == "REQUEST_NOT_FOUND"
    fun recoverable(state: RemoteConversationState): Boolean = state.command.text.isNotBlank() &&
        state.event?.status in setOf("cancelled", "failed", "unknown")
}
