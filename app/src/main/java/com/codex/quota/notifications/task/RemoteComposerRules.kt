package com.codex.quota.notifications.task

/** Attachments can steer the current turn; installed skills require an ordinary start. */
internal object RemoteComposerRules {
    fun idle(record: TaskInboxRecord): Boolean = record.snapshot.remote_ref != null && !record.snapshot.running &&
        (record.remoteState == null || record.remoteState.event?.status in setOf("completed", "interrupted", "failed") &&
            record.remoteState.event?.attachment_pending != true)

    fun followUp(record: TaskInboxRecord): Boolean = RemoteFollowUpRules.source(record) != null

    fun immediateFollowUp(record: TaskInboxRecord, selectedMode: String): Boolean =
        RemoteFollowUpRules.desktop(record) || record.remoteState?.command?.action in RemoteGoalRules.rootActions || selectedMode == "steer"

    fun canSend(record: TaskInboxRecord, attachments: Boolean, skills: Boolean): Boolean =
        idle(record) || followUp(record) && !skills
}
