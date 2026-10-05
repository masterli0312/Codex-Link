package com.codex.quota

import com.codex.quota.notifications.task.*
import org.junit.Assert.*
import org.junit.Test

class ConversationManagementTest {
    private val thread = "11111111-2222-3333-4444-555555555555"
    private val query = RemoteCommand("request", "rename", "host", "anchor", "anchor-chat", "turn", 1000,
        text = "New name", read_thread_id = thread)
    private val event = RemoteEvent("event", "request", "host", "anchor", "anchor-chat", "managed", 1, 2000,
        managed_thread_id = thread, managed_action = "rename", managed_name = "New name")
    private val record = TaskInboxRecord("id", 500, "identity", "pairing", TaskConversationSnapshot(
        conversation_id = TaskInbox.hash(thread), title = "Old name", reply = "Keep reply", completed_at = "1970-01-01T00:00:01Z",
        thread_ref = RemoteThreadRef("host", thread, "turn")),
        selectedModel = "model", selectedEffort = "high", historyMessages = listOf(TaskConversationMessage("user", "Keep history")))

    @Test fun renameDoesNotChangeTimeChoicesOrHistory() {
        val updated = ConversationIndex.applyManagement(record, "pairing", query, event)
        assertEquals("New name", updated.snapshot.title)
        assertEquals(record.snapshot.completed_at, updated.snapshot.completed_at)
        assertEquals(record.snapshot.reply, updated.snapshot.reply)
        assertEquals(record.selectedModel, updated.selectedModel)
        assertEquals(record.historyMessages, updated.historyMessages)
    }

    @Test fun anotherPairingThreadOrUnconfirmedResponseCannotChangeTheCache() {
        assertEquals(record, ConversationIndex.applyManagement(record, "other-pairing", query, event))
        assertEquals(record, ConversationIndex.applyManagement(record, "pairing", query, event.copy(request_id = "other")))
        assertEquals(record, ConversationIndex.applyManagement(record, "pairing", query, event.copy(managed_thread_id = "other")))
        assertEquals(record, ConversationIndex.applyManagement(record, "pairing", query, event.copy(error = "UNKNOWN")))
        assertEquals(record, ConversationIndex.applyManagement(record, "pairing", query, event.copy(partial = true)))
    }

    @Test fun archiveAndRestoreKeepAllMessages() {
        val archive = query.copy(action = "archive")
        val archived = ConversationIndex.applyManagement(record, "pairing", archive, event.copy(managed_action = "archive"))
        assertTrue(archived.archived)
        assertEquals(record.snapshot, archived.snapshot)
        val restored = ConversationIndex.applyManagement(archived, "pairing", query.copy(action = "unarchive"), event.copy(managed_action = "unarchive"))
        assertFalse(restored.archived)
        assertEquals(record.historyMessages, restored.historyMessages)
    }

    @Test fun staleCatalogCannotResurfaceAnArchivedConversation() {
        val archived = ConversationIndex.applyManagement(record, "pairing", query.copy(action = "archive"),
            event.copy(managed_action = "archive"))
        val computer = ComputerConnection("computer", "host", "Computer", "https://example.test/topic", "key", 0)
        val local = archived.copy(endpointHash = TaskInbox.hash(computer.endpoint))
        val stale = listOf(RemoteThreadSummary(thread, "Old name"))
        assertFalse(ConversationListPresentation.build(listOf(local), stale, local.endpointHash, "", false, computer, false, "").hasResults)
        val other = local.copy(snapshot = local.snapshot.copy(thread_ref = RemoteThreadRef("another-host", thread, "turn")))
        assertEquals(other, ConversationIndex.applyManagement(other, local.endpointHash, query.copy(action = "archive"), event.copy(managed_action = "archive")))
    }

    @Test fun onlyANewerScopedCatalogCanConfirmExternalRestoration() {
        val archived = ConversationIndex.applyManagement(record, "pairing", query.copy(action = "archive"),
            event.copy(managed_action = "archive"), appliedAt = 3000)
        val catalog = query.copy(action = "threads", issued_at = 2500)
        val result = event.copy(status = "threads", threads = listOf(RemoteThreadSummary(thread)))
        assertEquals(archived, ConversationIndex.applyCatalogArchive(archived, "pairing", catalog, result))
        val fresh = catalog.copy(issued_at = 3500)
        val restored = ConversationIndex.applyCatalogArchive(archived, "pairing", fresh, result)
        assertFalse(restored.archived)
        assertEquals(record.historyMessages, restored.historyMessages)
        assertEquals(archived, ConversationIndex.applyCatalogArchive(archived, "pairing", fresh, result.copy(threads = emptyList())))
        assertEquals(archived, ConversationIndex.applyCatalogArchive(archived, "pairing", fresh, result.copy(host_id = "other")))
        val staleDuplicate = record.copy(id = "older", receivedAt = 9000)
        assertTrue(ConversationIndex.latestForDisplay(listOf(archived, staleDuplicate)).single().archived)
    }
}
