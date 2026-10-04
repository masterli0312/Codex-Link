package com.codex.quota.notifications.task

/** Local preparation is independent of a running desktop task. Text-only follow-ups
 * cannot carry attachments/skills, so retain them for the next ordinary send. */
internal object RemoteComposerRules {
    fun idle(record: TaskInboxRecord): Boolean = record.snapshot.remote_ref != null && !record.snapshot.running &&
        (record.remoteState == null || record.remoteState.event?.status in setOf("completed", "interrupted", "failed") &&
            record.remoteState.event?.attachment_pending != true)

    fun followUp(record: TaskInboxRecord): Boolean = RemoteFollowUpRules.source(record) != null

    fun canSend(record: TaskInboxRecord, attachments: Boolean, skills: Boolean): Boolean =
        idle(record) || followUp(record) && !attachments && !skills
}
