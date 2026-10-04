package com.codex.quota.notifications.task

internal object RemoteSendRules {
    fun begin(record: TaskInboxRecord, command: RemoteCommand): TaskInboxRecord {
        require(!record.archived && !record.libraryAnchor && !record.snapshot.running && !ConversationIndex.hasPendingTurn(record)) { "REMOTE_BUSY" }
        val ref = requireNotNull(record.snapshot.remote_ref)
        require(command.action == "send" && command.host_id == ref.host_id && command.thread_id == ref.thread_id &&
            command.conversation_id == record.snapshot.conversation_id && command.baseline_turn == ref.baseline_turn) { "REMOTE_STALE" }
        return record.copy(remoteState = RemoteConversationState(command))
    }
}
