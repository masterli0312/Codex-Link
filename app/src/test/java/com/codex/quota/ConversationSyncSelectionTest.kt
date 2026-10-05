package com.codex.quota

import com.codex.quota.notifications.task.*
import org.junit.Assert.*
import org.junit.Test

class ConversationSyncSelectionTest {
    @Test fun onlyExplicitNewCreationsCanReceiveTheirFirstNativeSnapshot() {
        val command = RemoteCommand("request", "create", computer.hostId, computer.hostId, "library", "library", 1)
        val pending = record.copy(identity = "remote-create:" + command.id,
            snapshot = TaskConversationSnapshot(conversation_id = TaskInbox.hash(command.id)),
            remoteState = RemoteConversationState(command), pendingSyncCreation = true)
        assertTrue(ConversationSyncRules.allows(pending, computer, ConversationSyncSelection()))
        assertFalse(ConversationSyncRules.allows(pending.copy(pendingSyncCreation = false), computer, ConversationSyncSelection()))
        assertFalse(ConversationSyncRules.allows(pending, computer.copy(hostId = "other"), ConversationSyncSelection()))
        assertFalse(ConversationSyncRules.allows(pending.copy(snapshot = record.snapshot), computer, ConversationSyncSelection()))
    }
    private val id = "11111111-2222-3333-4444-555555555555"
    private val second = "22222222-2222-3333-4444-555555555555"
    private val computer = ComputerConnection("computer", "host", "Computer", "https://example.test/topic", "key", 0)
    private val thread = RemoteThreadSummary(id, "Chosen")
    private val record = TaskInboxRecord("id", 0, "identity", TaskInbox.hash(computer.endpoint),
        TaskConversationSnapshot(conversation_id = TaskInbox.hash(id), thread_ref = RemoteThreadRef("host", id, "turn")))
    @Test fun existingCachesAreNotAutomaticallySelected() {
        assertFalse(ConversationSyncRules.allows(record, computer, ConversationSyncSelection()))
        val chosen = ConversationSyncRules.replace(ConversationSyncSelection(), setOf(id), listOf(thread))
        assertTrue(ConversationSyncRules.allows(record, computer, chosen))
        assertFalse(ConversationSyncRules.allows(record, computer.copy(hostId = "other"), chosen))
        assertFalse(ConversationSyncRules.allows(record, computer.copy(endpoint = "https://other.test/topic"), chosen))
    }
    @Test fun selectionSurvivesPaginationAndDeselectionDoesNotArchiveOrLoseHistory() {
        val old = ConversationSyncSelection(listOf(SyncedConversation(thread, archived = true)))
        val selected = ConversationSyncRules.replace(old, setOf(id, second), listOf(RemoteThreadSummary(second, "New")))
        assertEquals(true, selected.conversations.first().archived)
        assertEquals(2, selected.ids.size)
        assertTrue(ConversationSyncRules.replace(selected, emptySet(), emptyList()).ids.isEmpty())
        assertEquals(old.conversations.first(), selected.conversations.first())
    }
    @Test fun pairingIdentityAndUnavailableTargetsCannotBeMixed() {
        assertNotEquals(ConversationSyncRules.scope(computer), ConversationSyncRules.scope(computer.copy(hostId = "other")))
        assertNotEquals(ConversationSyncRules.scope(computer), ConversationSyncRules.scope(computer.copy(endpoint = "https://other.test/topic")))
        assertThrows(IllegalArgumentException::class.java) { ConversationSyncRules.replace(ConversationSyncSelection(), setOf(id), emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { ConversationSyncRules.replace(ConversationSyncSelection(), setOf("invalid"), listOf(thread)) }
    }
    @Test fun unselectedNotificationPreviewsCannotImportConversationContent() {
        val chosen = ConversationSyncSelection(listOf(SyncedConversation(thread)))
        assertTrue(ConversationSyncRules.caches(record.snapshot, computer, chosen))
        assertFalse(ConversationSyncRules.caches(record.snapshot, computer, ConversationSyncSelection()))
        assertFalse(ConversationSyncRules.caches(record.snapshot.copy(conversation_id = "another"), computer, chosen))
        assertFalse(ConversationSyncRules.caches(record.snapshot.copy(thread_ref = RemoteThreadRef("other", id, "turn")), computer, chosen))
    }
}
