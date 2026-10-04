package com.codex.quota

import com.codex.quota.notifications.task.*
import org.junit.Assert.*
import org.junit.Test

class RemoteComposerRulesTest {
    private val ref = RemoteThreadRef("host", "thread", "previous")
    private val command = RemoteCommand("request", "send", "host", "thread", "conversation", "previous", 1000)
    private val idle = TaskInboxRecord("record", 1, "event", "pair",
        TaskConversationSnapshot(conversation_id = "conversation", remote_ref = ref))

    @Test fun preparedFilesAndSkillsCannotBeSilentlyDroppedByTextOnlyFollowUps() {
        val active = idle.copy(snapshot = idle.snapshot.copy(running = true, remote_ref = null),
            remoteState = RemoteConversationState(command, RemoteEvent("event", "request", "host", "thread",
                "conversation", "running", 1, 2000, turn_id = "active")))
        assertTrue(RemoteComposerRules.canSend(active, false, false))
        assertFalse(RemoteComposerRules.canSend(active, true, false))
        assertFalse(RemoteComposerRules.canSend(active, false, true))
        val completed = idle.copy(remoteState = active.remoteState?.copy(event = active.remoteState.event?.copy(status = "completed")))
        assertTrue(RemoteComposerRules.canSend(completed, true, true))
    }

    @Test fun missingLiveTurnAndPartialRepliesDoNotEnableFollowUp() {
        val pending = idle.copy(remoteState = RemoteConversationState(command))
        assertFalse(RemoteComposerRules.canSend(pending, true, false))
        assertFalse(RemoteComposerRules.canSend(pending, false, false))
        val partial = pending.copy(remoteState = pending.remoteState?.copy(event = RemoteEvent("event", "request", "host", "thread",
            "conversation", "running", 1, 2000, turn_id = "active", attachment_pending = true)))
        assertFalse(RemoteComposerRules.canSend(partial, false, false))
    }
}
