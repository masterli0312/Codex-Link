package com.codex.quota.notifications.task

/** Only the authenticated current native turn can be steered. A desktop turn is not a new phone task. */
internal object RemoteFollowUpRules {
    fun source(record: TaskInboxRecord): RemoteConversationState? {
        val root = record.remoteState
        val active = root?.event
        val ref = record.snapshot.thread_ref
        if (active?.status in setOf("running", "approval", "input_required") && active?.turn_id?.isNotBlank() == true &&
            !active.attachment_pending && active.request_id == root?.command?.id && active.host_id == root.command.host_id &&
            active.thread_id == root.command.thread_id && active.conversation_id == root.command.conversation_id &&
            (!record.snapshot.running || ref == null || ref.baseline_turn == active.turn_id)) return root
        val query = record.libraryRequest ?: return null
        val result = record.libraryResult ?: return null
        if (!record.snapshot.running || ref == null || !RemoteProtocol.validRef(ref, record.snapshot.conversation_id) ||
            query.action != "read" || !query.watch || query.thread_id != ref.thread_id || query.read_thread_id != ref.thread_id ||
            query.host_id != ref.host_id || query.conversation_id != record.snapshot.conversation_id || result.request_id != query.id ||
            result.host_id != query.host_id || result.thread_id != query.thread_id || result.conversation_id != query.conversation_id ||
            result.status != "snapshot" || result.error.isNotBlank() || result.attachment_pending) return null
        return RemoteConversationState(query, result.copy(status = "running", turn_id = ref.baseline_turn))
    }
    fun desktop(record: TaskInboxRecord) = source(record)?.command?.action == "read"
}
