package com.codex.quota

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.codex.quota.notifications.task.*
import com.codex.quota.ui.feature.taskhistory.ConversationSyncPicker
import java.io.File
import java.util.UUID
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConversationSyncSelectionDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val first = RemoteThreadSummary("11111111-2222-3333-4444-555555555555", "Fixture first")
    private val second = RemoteThreadSummary("22222222-2222-3333-4444-555555555555", "Fixture second")
    @Test fun phoneCreationJoinsSyncOnceAndDoesNotRejoinAfterDeselection() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(target.cacheDir, "sync-selection-fixture-" + UUID.randomUUID()).apply { mkdirs() }
        check(root.canonicalFile.parentFile == target.cacheDir.canonicalFile)
        val context = object : ContextWrapper(target) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = root
        }
        val computer = ComputerConnection("fixture", "host-fixture", "Fixture", "https://example.invalid/fixture", "fixture-key", 0)
        try {
            File(root, "remote-computers.json").writeText(TaskContentKeys(context).encryptLocal(
                "{\"connections\":[" + Json.encodeToString(computer) + "],\"legacyRemoved\":true}"))
            val store = ConversationSyncSelectionStore(context)
            val command = RemoteCommand("fixture-create", "create", computer.hostId, computer.hostId, "library", "library", 1)
            val pending = TaskInboxRecord(TaskInbox.hash("fixture-create"), 1, "remote-create:" + command.id,
                TaskInbox.hash(computer.endpoint), TaskConversationSnapshot(conversation_id = TaskInbox.hash(command.id)),
                remoteState = RemoteConversationState(command), pendingSyncCreation = true)
            val inbox = TaskInbox(context)
            inbox.save(pending)
            assertTrue(ConversationSyncRules.allows(pending, computer, store.read(computer)))
            val native = TaskConversationSnapshot(conversation_id = TaskInbox.hash(first.thread_id), title = first.title,
                remote_ref = RemoteThreadRef(computer.hostId, first.thread_id, "turn"))
            val event = RemoteEvent("fixture-event", command.id, computer.hostId, computer.hostId, "library", "running", 1, 2, snapshot = native)
            inbox.updateRemote(pending.id) { it.copy(event = event, transport = "received") }
            assertEquals(setOf(first.thread_id), store.read(computer).ids)
            assertFalse(inbox.read(pending.id)!!.pendingSyncCreation)
            store.replace(computer, emptySet(), emptyList())
            inbox.updateRemote(pending.id) { it.copy(event = event.copy(id = "fixture-event-next", seq = 2)) }
            assertTrue(store.read(computer).ids.isEmpty())
        } finally {
            check(root.canonicalFile.parentFile == target.cacheDir.canonicalFile)
            root.deleteRecursively()
        }
    }
    @Test fun pickerMultiSelectsAndSavesOnlyChosenConversations() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var saved: Set<String>? = null
        compose.setContent { MaterialTheme {
            ConversationSyncPicker("fixture", "Fixture computer", ConversationSyncSelection(), listOf(first, second),
                loading = false, saving = false, error = false, more = false, onSearch = {}, onMore = {},
                onSave = { ids, _ -> saved = ids }, onBack = {})
        } }
        compose.onNodeWithText(first.title).performClick()
        compose.onNodeWithText(second.title).performClick()
        compose.onNodeWithText(context.getString(R.string.action_save)).performClick()
        compose.runOnIdle { assertEquals(setOf(first.thread_id, second.thread_id), saved) }
        compose.onNodeWithText(context.getString(R.string.conversation_sync_clear)).performClick()
        compose.onNodeWithText(context.getString(R.string.action_save)).performClick()
        compose.runOnIdle { assertEquals(emptySet<String>(), saved) }
    }
    @Test fun encryptedSelectionPersistsAndIsIsolatedWithoutChangingPhonePairingsOrChats() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(target.cacheDir, "sync-selection-fixture-" + UUID.randomUUID()).apply { mkdirs() }
        check(root.canonicalFile.parentFile == target.cacheDir.canonicalFile)
        val context = object : ContextWrapper(target) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = root
        }
        val computer = ComputerConnection("fixture", "host-fixture", "Fixture", "https://example.invalid/fixture", "fixture-key", 0)
        try {
            val store = ConversationSyncSelectionStore(context)
            assertTrue(store.read(computer).ids.isEmpty())
            store.replace(computer, setOf(first.thread_id), listOf(first, second))
            assertEquals(setOf(first.thread_id), ConversationSyncSelectionStore(context).read(computer).ids)
            assertTrue(store.read(computer.copy(hostId = "other-fixture")).ids.isEmpty())
            assertTrue(store.read(computer.copy(endpoint = "https://example.invalid/other")).ids.isEmpty())
            val file = File(root, "conversation-sync-selection").listFiles()!!.single()
            assertFalse(file.readText().contains(first.title))
            store.confirmArchive(computer, first.thread_id, true, 3000)
            val query = RemoteCommand("request", "threads", computer.hostId, computer.hostId, "catalog", "library", 2000)
            val event = RemoteEvent("event", "request", computer.hostId, computer.hostId, "catalog", "threads", 1, 2000, threads = listOf(first))
            store.catalog(computer, query, event)
            assertTrue(store.read(computer).conversations.single().archived)
            store.catalog(computer, query.copy(issued_at = 4000), event)
            assertFalse(store.read(computer).conversations.single().archived)
            store.confirmName(computer, first.thread_id, "Fixture renamed", 5000)
            store.catalog(computer, query.copy(issued_at = 4500), event)
            assertEquals("Fixture renamed", store.read(computer).conversations.single().thread.title)
            store.replace(computer, emptySet(), emptyList())
            assertTrue(ConversationSyncSelectionStore(context).read(computer).ids.isEmpty())
        } finally {
            check(root.canonicalFile.parentFile == target.cacheDir.canonicalFile)
            root.deleteRecursively()
        }
    }
}
