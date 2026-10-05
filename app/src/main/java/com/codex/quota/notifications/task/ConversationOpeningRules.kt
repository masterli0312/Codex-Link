package com.codex.quota.notifications.task

/** A first frame must not briefly expose a stale offline tail as the current online conversation. */
object ConversationOpeningRules {
    // A fresh watch reads the current native transcript and status recovers pending
    // sends. Replaying an old task's whole event log delays that watch behind stale
    // frames and can overflow the stream queue before current content arrives.
    fun replaySince(eventAt: Long?, now: Long): Long =
        ((eventAt ?: now) / 1000 - 1).coerceIn((now / 1000 - 2).coerceAtLeast(0), now / 1000)

    fun display(current: TaskInboxRecord?, incoming: TaskInboxRecord?): TaskInboxRecord? =
        if (current != null && incoming != null && ConversationIndex.sameConversation(current, incoming) &&
            ConversationSnapshotRules.regresses(current, incoming.snapshot, nativeRewrite = true)) current else incoming ?: current

    fun contentReady(record: TaskInboxRecord?, openedAt: Long, online: Boolean): Boolean =
        record != null && (!online || record.contentValidatedAt >= openedAt ||
            record.remoteState?.let { it.command.issued_at >= openedAt - 15_000 && ConversationIndex.hasPendingTurn(record) &&
                ConversationSnapshotRules.currentTask(record, it) } == true)
}
