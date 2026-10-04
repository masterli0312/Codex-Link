package com.codex.quota

import com.codex.quota.notifications.task.*
import org.junit.Assert.*
import org.junit.Test

class RemoteForkTest {
    private val host = "11111111-2222-3333-4444-555555555555"
    private val source = "22222222-2222-3333-4444-555555555555"
    private val child = "33333333-2222-3333-4444-555555555555"
    private val command = RemoteCommand(source, "fork", host, source, TaskInbox.hash(source), "turn", 1000,
        read_thread_id = source, expected_turn_id = "turn")
    private val event = RemoteEvent(child, source, host, source, command.conversation_id, "forked", 1, 2000,
        snapshot = TaskConversationSnapshot(conversation_id = TaskInbox.hash(child), remote_ref = RemoteThreadRef(host, child, "turn")),
        forked_from_thread_id = source)

    @Test fun confirmedBranchNeedsNewIdentityOnTheSameComputer() {
        assertTrue(RemoteForkRules.confirmed(command, event))
        assertFalse(RemoteForkRules.confirmed(command, event.copy(host_id = child)))
        assertFalse(RemoteForkRules.confirmed(command, event.copy(forked_from_thread_id = child)))
        assertFalse(RemoteForkRules.confirmed(command, event.copy(partial = true)))
        assertFalse(RemoteForkRules.confirmed(command, event.copy(snapshot = event.snapshot!!.copy(
            conversation_id = TaskInbox.hash(source), remote_ref = RemoteThreadRef(host, source, "turn")))))
    }

    @Test fun explicitDefaultsComeFromCatalogAndNeverEraseAnExistingSelection() {
        val models = listOf(RemoteModelOption("model", "Model", listOf("low", "high"), "low"))
        assertEquals(Triple("model", "high", "default"), ConversationDefaults.explicit(models, "model", "high", "", "", "", listOf("default", "plan")))
        assertEquals(Triple("old-model", "high", "plan"), ConversationDefaults.explicit(models, "model", "low", "old-model", "high", "plan", listOf("default", "plan")))
        assertEquals(Triple("model", "low", "default"), ConversationDefaults.explicit(models, "model", "unknown", "", "", "", listOf("default")))
    }
}
