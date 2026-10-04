package com.codex.quota.ui.feature.taskhistory

import com.codex.quota.data.cloud.CloudDetails
import com.codex.quota.notifications.task.*

internal fun cloudTranscript(details: CloudDetails): List<ConversationTimelineEntry> {
    val messages = details.messages.map { TaskConversationMessage(it.role,it.text.take(48_000),it.id,position = it.position,phase = it.phase) }
    return ConversationActivityGroups.present(ConversationTimeline.userInputsFirst(
        ConversationTimeline.build(messages,details.activities),details.latestTurnId))
}
