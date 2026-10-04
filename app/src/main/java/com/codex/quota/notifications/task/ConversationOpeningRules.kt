package com.codex.quota.notifications.task

/** A first frame must not briefly expose a stale offline tail as the current online conversation. */
object ConversationOpeningRules {
    fun display(current: TaskInboxRecord?, incoming: TaskInboxRecord?): TaskInboxRecord? =
        if (current != null && incoming != null && ConversationIndex.sameConversation(current, incoming) &&
            ConversationSnapshotRules.regresses(current, incoming.snapshot, nativeRewrite = true)) current else incoming ?: current

    fun contentReady(record: TaskInboxRecord?, openedAt: Long, online: Boolean): Boolean =
        record != null && (!online || record.contentValidatedAt >= openedAt - 15_000 ||
            record.remoteState?.let { it.command.issued_at >= openedAt - 15_000 && ConversationIndex.hasPendingTurn(record) &&
                ConversationSnapshotRules.currentTask(record, it) } == true)
}
