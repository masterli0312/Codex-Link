package com.codex.quota

import com.codex.quota.notifications.task.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import kotlin.system.measureNanoTime

class ConversationListPerformanceTest {
    @Test fun largeCatalogPreservesCachedMatchesAndPairingIsolation() {
        val catalog = (0 until 1000).map { RemoteThreadSummary("thread-$it", "title $it", updated_at = it.toLong()) }
        val records = catalog.take(50).mapIndexed { i, thread ->
            TaskInboxRecord("record-$i", i.toLong(), "event-$i", if (i < 25) "pair" else "other",
                TaskConversationSnapshot(conversation_id = TaskInbox.hash(thread.thread_id), title = thread.title,
                    completed_at = Instant.ofEpochMilli(i.toLong()).toString()))
        }
        repeat(2) { ConversationIndex.uncoveredThreads(catalog, records, "pair", "", false) }
        val samples = List(5) { measureNanoTime {
            val visible = ConversationIndex.uncoveredThreads(catalog, records, "pair", "", false)
            assertEquals(975, visible.size)
            assertEquals("thread-25", visible.first().thread_id)
        } / 1_000_000.0 }
        println("conversation-list-1000-catalog-50-cache-ms=" + samples.sorted().joinToString())
        val fullSamples = List(5) { measureNanoTime {
            assertEquals(1025, ConversationListPresentation.build(records, catalog, "pair", "", false, null, false, "").rows.size)
        } / 1_000_000.0 }
        println("conversation-list-full-presentation-ms=" + fullSamples.sorted().joinToString())
    }

    @Test fun hashesRemainCompatibleWithStoredCredentialsAndConversationIds() {
        listOf("", "abc", "配对电脑:对话", "https://example.test/topic-a", "😀").forEach { value ->
            val previous = java.security.MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            assertEquals(previous, TaskInbox.hash(value))
        }
    }

    @Test fun presentationKeepsSearchPinningProjectsAndOtherPairings() {
        val project = "p"
        val thread = RemoteThreadSummary("thread", "new title", updated_at = 5000, project_id = project)
        val current = TaskInboxRecord("local", 1, "event", "pair",
            TaskConversationSnapshot(conversation_id = TaskInbox.hash("thread"), title = "old title"), pinned = true)
        val other = current.copy(id = "other", endpointHash = "other", pinned = false)
        val all = ConversationListPresentation.build(listOf(current, other), listOf(thread), "pair", "", false, null, false, "")
        assertEquals(listOf("local", "other"), all.rows.map { it.key })
        assertEquals(5000L, all.rows.first().updatedAt)
        assertNull(all.rows.last().thread)
        val search = ConversationListPresentation.build(listOf(current), listOf(thread), "pair", "new title", false, null, false, "")
        assertEquals(listOf("thread"), search.rows.map { it.key })
        val filtered = ConversationListPresentation.build(listOf(current, other), listOf(thread), "pair", "", false, null, false, project)
        assertEquals(listOf("local"), filtered.rows.map { it.key })
        val archive = ConversationListPresentation.build(listOf(current), listOf(thread), "pair", "", true, null, false, "")
        assertEquals(listOf("thread"), archive.rows.map { it.key })
    }

    @Test fun sameEndpointOnAnotherHostCannotHideOrOpenTheSelectedComputerThread() {
        val computer = ComputerConnection("computer-a", "host-a", "A", "https://example.test/pair", "key", 0)
        val thread = RemoteThreadSummary("thread", "title", updated_at = 5000)
        val other = TaskInboxRecord("other-host", 1, "event", TaskInbox.hash(computer.endpoint),
            TaskConversationSnapshot(conversation_id = TaskInbox.hash(thread.thread_id),
                thread_ref = RemoteThreadRef("host-b", thread.thread_id, "unread")))
        val rows = ConversationListPresentation.build(listOf(other), listOf(thread), other.endpointHash,
            "", false, computer, false, "").rows
        assertEquals(2, rows.size)
        assertNull(rows.single { it.record != null }.thread)
        assertEquals(thread, rows.single { it.record == null }.thread)
        val onlySelected = ConversationListPresentation.build(listOf(other), listOf(thread), other.endpointHash,
            "", false, computer, true, "").rows
        assertEquals(listOf("thread"), onlySelected.map { it.key })
    }
}
