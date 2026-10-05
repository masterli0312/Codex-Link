package com.codex.quota

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.codex.quota.notifications.task.*
import com.codex.quota.ui.feature.taskhistory.ConversationSwipeRow
import com.codex.quota.ui.feature.taskhistory.DesktopRowTarget
import com.codex.quota.ui.feature.taskhistory.desktopRowAction
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConversationArchiveTest {
    @get:Rule val compose = createComposeRule()

    /** One-off, explicitly authorized cleanup, only after native thread/delete confirmed these exact IDs. */
    @Test fun removeConfirmedNativeEmptyDeletionsFromPhoneCache() {
        val args = InstrumentationRegistry.getArguments()
        val host = args.getString("nativeDeletedHost").orEmpty()
        val deleted = args.getString("nativeDeletedThreads").orEmpty().split(",").toSet()
        val uuid = Regex("[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}")
        assumeTrue(host.matches(uuid) && deleted.isNotEmpty() && deleted.all { it.matches(uuid) })
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val computer = ComputerConnectionStore(context).all().single { it.hostId == host }
        val inbox = TaskInbox(context)
        val before = inbox.list()
        val preserved = before.filterNot { record -> computer.matches(record) &&
            (record.snapshot.thread_ref ?: record.snapshot.remote_ref)?.thread_id in deleted }.map { it.id }.toSet()
        var removed = 0
        for (record in before.filter { computer.matches(it) }) {
            val ref = record.snapshot.thread_ref ?: record.snapshot.remote_ref
            val empty = record.snapshot.messages.isEmpty() && record.snapshot.reply.isBlank() &&
                record.historyMessages.isEmpty() && !record.snapshot.running && record.followUps.isEmpty() &&
                record.localQueuedReplies.isEmpty() && record.remoteState == null
            if (!record.libraryAnchor && ref?.thread_id in deleted && empty) {
                val directory = java.io.File(context.noBackupFilesDir, "task-inbox")
                val file = java.io.File(directory, "${record.id}.json")
                check(file.canonicalFile.parentFile == directory.canonicalFile)
                android.util.AtomicFile(file).delete()
                removed++
            } else {
                val result = record.libraryResult?.let { it.copy(threads = it.threads.filterNot { t -> t.thread_id in deleted }) }
                inbox.replaceExisting(record.copy(threadCatalog = record.threadCatalog.filterNot { it.thread_id in deleted }, libraryResult = result))
            }
        }
        TaskInbox.changes.value += 1
        assertTrue(inbox.list().map { it.id }.toSet().containsAll(preserved))
        assertTrue(inbox.list().filter { computer.matches(it) }.all { r -> r.threadCatalog.none { it.thread_id in deleted } })
        android.util.Log.i("ArchiveFixture", "confirmedEmptyCacheRemoval=$removed")
    }

    @Test fun leftSwipeExposesClickableArchiveAndDoesNotArchiveOnDrag() {
        val open = mutableStateOf(false)
        var archives = 0
        val label = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.conversation_menu_archive)
        compose.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxWidth().testTag("fixture-row")) {
                    ConversationSwipeRow(open.value, { open.value = true }, { open.value = false }, false, false, true,
                        onPin = {}, onRename = {}, onArchive = { archives++ }) { Text("Fixture conversation") }
                }
            }
        }
        compose.onNodeWithTag("fixture-row").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(0, archives) }
        compose.onNodeWithText(label, useUnmergedTree = true).performClick()
        compose.runOnIdle { assertEquals(1, archives); assertFalse(open.value) }
    }

    /** Only the IDs from the user's already-requested, natively archived failed actions may be supplied. */
    @Test fun previouslyRequestedArchivesReceiveConfirmedState() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val ids = args.getString("previouslyRequestedArchives").orEmpty().split(",").toSet()
        val hostId = args.getString("archiveFixtureHost").orEmpty()
        assumeTrue(ids.size in 1..10 && ids.all { it.matches(Regex("[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}")) })
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val computer = ComputerConnectionStore(context).all().single { it.hostId == hostId }
        val inbox = TaskInbox(context)
        for (threadId in ids) {
            val cached = inbox.list().firstOrNull { ConversationIndex.matchesThread(it, computer, threadId) }
            val thread = RemoteThreadSummary(threadId, cached?.snapshot?.title.orEmpty())
            val id = cached?.id ?: RemoteConversationClient(context).openThread(computer, thread, false)
            val before = requireNotNull(inbox.read(id))
            desktopRowAction(context, DesktopRowTarget(before, thread, computer, false), "archive")
            val after = requireNotNull(inbox.read(id))
            assertTrue(after.archived)
            assertEquals(before.snapshot.messages, after.snapshot.messages)
            assertEquals(before.historyMessages, after.historyMessages)
            val staleCatalog = listOf(thread)
            assertFalse(ConversationListPresentation.build(listOf(after), staleCatalog, after.endpointHash,
                "", false, computer, true, "").hasResults)
        }
    }

    /** Opt-in fixture only. No messages or mutations target a user's working thread. */
    @Test fun desktopRowRenameArchiveAndRestoreReceiveTheirOwnRealRelayAcknowledgements() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val threadId = args.getString("archiveFixtureThread").orEmpty()
        val hostId = args.getString("archiveFixtureHost").orEmpty()
        val title = args.getString("archiveFixtureTitle").orEmpty()
        assumeTrue(threadId.matches(Regex("[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}")) && title.startsWith("Codex Link archive fixture "))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val computer = ComputerConnectionStore(context).all().single { it.hostId == hostId }
        val inbox = TaskInbox(context)
        val thread = RemoteThreadSummary(threadId, title)
        val id = TaskInbox.hash(TaskInbox.hash(computer.endpoint) + ":host:" + hostId + ":conversation:" + TaskInbox.hash(threadId))
        assertNull("Only a fresh isolated fixture may be changed", inbox.read(id))
        val opened = RemoteConversationClient(context).openThread(computer, thread, false)
        assertEquals(id, opened)
        fun target(archived: Boolean) = DesktopRowTarget(requireNotNull(inbox.read(id)), thread, computer, archived)
        try {
            desktopRowAction(context, target(false), "rename", "$title renamed")
            assertEquals("$title renamed", inbox.read(id)!!.snapshot.title)
            desktopRowAction(context, target(false), "archive")
            assertTrue(inbox.read(id)!!.archived)
            desktopRowAction(context, target(false), "archive")
            assertTrue(inbox.read(id)!!.archived)
            assertFalse(ConversationListPresentation.build(listOf(inbox.read(id)!!), emptyList(), TaskInbox.hash(computer.endpoint),
                "", false, computer, true, "").hasResults)
            desktopRowAction(context, target(true), "unarchive")
            assertFalse(inbox.read(id)!!.archived)
            desktopRowAction(context, target(true), "unarchive")
            assertFalse(inbox.read(id)!!.archived)
            assertTrue(ConversationListPresentation.build(listOf(inbox.read(id)!!), emptyList(), TaskInbox.hash(computer.endpoint),
                "", false, computer, false, "").hasResults)
            desktopRowAction(context, target(false), "archive")
            assertTrue(inbox.read(id)!!.archived)
        } finally {
            // Remove only the fixture cache this test created. Keep the native fixture archived.
            val record = inbox.read(id)
            if (record?.identity == "remote-sync:" + TaskInbox.hash(threadId) && record.snapshot.thread_ref?.thread_id == threadId) {
                val directory = java.io.File(context.noBackupFilesDir, "task-inbox")
                val file = java.io.File(directory, "$id.json")
                check(file.canonicalFile.parentFile == directory.canonicalFile)
                android.util.AtomicFile(file).delete()
                TaskInbox.changes.value += 1
            }
        }
    }
}
