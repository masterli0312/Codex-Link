package com.codex.quota

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.pdf.PdfDocument
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.codex.quota.notifications.task.*
import com.codex.quota.ui.feature.taskhistory.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConversationInteractionDeviceTest {
    @get:Rule val compose = createComposeRule()
    @Test fun questionDialogAcceptsAnExplicitSelectionAndKeepsItsAnswerOnStreamUpdates() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val question = RemoteInputRequest(UUID.randomUUID().toString(), "fixture-turn", false,
            listOf(RemoteInputQuestion("fixture", "", "Fixture environment?", true, options = listOf(RemoteInputOption("Fixture Cloud"), RemoteInputOption("Fixture computer")))))
        var submitted: Map<String, List<String>>? = null
        compose.setContent { MaterialTheme { RemoteInputPrompt(question) { submitted = it } } }
        compose.onNodeWithText("Fixture environment?").assertExists()
        compose.onNodeWithText("Fixture Cloud").performClick()
        compose.onNodeWithText(context.getString(R.string.remote_input_submit)).performClick()
        compose.waitUntil(3000) { submitted != null }
        assertEquals(listOf("Fixture Cloud"), submitted!!["fixture"])
        compose.onNodeWithText(context.getString(R.string.remote_input_submitted)).assertExists()
    }
    @Test fun downloadedPdfRendersAndOfficeViewIntentGrantsOnlyReadAccess() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val downloads = File(context.cacheDir, "conversation-downloads").apply { mkdirs() }
        val source = File(downloads, UUID.randomUUID().toString()+".pdf")
        var staged: File? = null
        try {
            val pdf = PdfDocument()
            try {
                val page = pdf.startPage(PdfDocument.PageInfo.Builder(300, 400, 1).create())
                page.canvas.drawText("Fixture PDF", 20f, 40f, android.graphics.Paint().apply { textSize = 20f })
                pdf.finishPage(page); source.outputStream().use { pdf.writeTo(it) }
            } finally { pdf.close() }
            staged = ConversationDocuments.stage(context, source, "Fixture report.pdf")
            ConversationPdf(staged).use { document ->
                assertEquals(1, document.pages)
                val bitmap = document.render(0); assertTrue(bitmap.width > 0); assertTrue(bitmap.height > 0); bitmap.recycle()
            }
            val pdfIntent = ConversationDocuments.viewIntent(context, staged)
            assertEquals("application/pdf", pdfIntent.type)
            assertEquals("content", pdfIntent.data!!.scheme)
            assertTrue(pdfIntent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            assertEquals(0, pdfIntent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            val office = File(staged.parentFile, "Fixture slides.pptx").apply { writeText("fixture") }
            assertEquals("application/vnd.openxmlformats-officedocument.presentationml.presentation", ConversationDocuments.viewIntent(context, office).type)
            assertThrows(IllegalArgumentException::class.java) { ConversationDocuments.viewIntent(context, source) }
        } finally {
            source.delete()
            staged?.parentFile?.let { folder ->
                check(folder.canonicalFile.parentFile == File(context.cacheDir, "conversation-previews").canonicalFile)
                folder.deleteRecursively()
            }
        }
    }
    @Test fun executionMessagesRemainPendingUntilExplicitSteeringAndRetainAttachments() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(target.cacheDir, "pending-fixture-"+UUID.randomUUID()).apply { mkdirs() }
        val context = object : ContextWrapper(target) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = root
        }
        try {
            val id = TaskInbox.hash("fixture-pending-"+UUID.randomUUID())
            val ref = RemoteThreadRef(UUID.randomUUID().toString(), UUID.randomUUID().toString(), "fixture-turn")
            val store = TaskInbox(context)
            store.save(TaskInboxRecord(id, 1, "fixture", "fixture", TaskConversationSnapshot(running = true, thread_ref = ref)))
            val photo = LocalQueuedAttachment("content://fixture/photo", "photo.png", "image/png")
            store.queueLocalReply(id, "Fixture edit", listOf(photo), awaitingChoice = true)
            val pending = requireNotNull(store.read(id)).localQueuedReplies.single()
            assertEquals(listOf(photo), pending.files)
            store.save(requireNotNull(store.read(id)).copy(snapshot = TaskConversationSnapshot(running = false, remote_ref = ref)))
            assertNull(store.claimLocalReply(id)) // A completed turn cannot silently dispatch an unchosen message.
            compose.setContent { MaterialTheme { QueuedMessageChip(pending.text, true, true, {}, {}, {}) } }
            compose.onNodeWithContentDescription(target.getString(R.string.conversation_actions)).performClick()
            for (label in listOf(R.string.conversation_edit_queued, R.string.conversation_steer_queued, R.string.conversation_cancel_queued))
                compose.onNodeWithText(target.getString(label)).assertExists()
            store.cancelLocalReply(id, pending.id)
            assertTrue(requireNotNull(store.read(id)).localQueuedReplies.isEmpty())
        } finally { check(root.canonicalFile.parentFile == target.cacheDir.canonicalFile); root.deleteRecursively() }
    }
    @Test fun completionMetadataPersistsWithoutImportingUnselectedThreads() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(target.cacheDir, "completion-fixture-"+UUID.randomUUID()).apply { mkdirs() }
        val context = object : ContextWrapper(target) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = root
        }
        val computer = ComputerConnection("fixture", "fixture-host", "Fixture", "https://example.invalid/fixture", "fixture-key", 0)
        val thread = RemoteThreadSummary(UUID.randomUUID().toString(), "Fixture selected")
        try {
            val store = ConversationSyncSelectionStore(context)
            store.replace(computer, setOf(thread.thread_id), listOf(thread))
            store.markActivity(computer, thread.thread_id, "turn", true, 100)
            store.markActivity(computer, thread.thread_id, "turn", false, 200)
            assertTrue(ConversationSyncSelectionStore(context).read(computer).conversations.single().unread)
            store.markSeen(computer, thread.thread_id)
            store.markActivity(computer, thread.thread_id, "turn", false, 300)
            assertFalse(store.read(computer).conversations.single().unread)
            store.markActivity(computer, UUID.randomUUID().toString(), "other", false, 400)
            assertEquals(setOf(thread.thread_id), store.read(computer).ids)
        } finally { check(root.canonicalFile.parentFile == target.cacheDir.canonicalFile); root.deleteRecursively() }
    }
    @Test fun secondSteerAcknowledgementAndTimeoutNeverTrapTheFirstDraft() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(target.cacheDir, "steer-fixture-"+UUID.randomUUID()).apply { mkdirs() }
        val context = object : ContextWrapper(target) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = root
        }
        try {
            val host = UUID.randomUUID().toString(); val thread = UUID.randomUUID().toString()
            val conversation = TaskInbox.hash(thread); val id = TaskInbox.hash("fixture-steer-"+UUID.randomUUID())
            val first = LocalQueuedReply(UUID.randomUUID().toString(),"Fixture first", awaitingChoice = true)
            val second = LocalQueuedReply(UUID.randomUUID().toString(),"Fixture second", awaitingChoice = true)
            val command = RemoteCommand(second.id,"steer",host,thread,conversation,"turn",1,text = second.text)
            val store = TaskInbox(context)
            store.save(TaskInboxRecord(id,1,"fixture","fixture",TaskConversationSnapshot(conversation_id = conversation),
                localQueuedReplies = listOf(first,second),followUps = listOf(RemoteConversationState(command))))
            store.markFollowUpUnconfirmed(id,second.id)
            val timed = store.read(id)!!
            assertEquals(2,timed.localQueuedReplies.size)
            assertFalse(FollowUpState.pending(timed.followUps.single()))
            assertTrue(FollowUpState.recoverable(timed.followUps.single()))
            store.updateFollowUp(id,second.id) { it.copy(event = RemoteEvent(UUID.randomUUID().toString(),second.id,host,thread,conversation,"steered",1,2),transport = "received") }
            assertEquals(listOf(first),store.read(id)!!.localQueuedReplies)
            val lost = command.copy(id = UUID.randomUUID().toString())
            store.save(store.read(id)!!.copy(followUps = listOf(RemoteConversationState(lost))))
            store.markFollowUpUnconfirmed(id,lost.id)
            store.dismissUnconfirmedReply(id,lost.id)
            assertTrue(store.read(id)!!.followUps.isEmpty())
            assertEquals(listOf(first),store.read(id)!!.localQueuedReplies)
        } finally { check(root.canonicalFile.parentFile == target.cacheDir.canonicalFile); root.deleteRecursively() }
    }
    @Test fun notificationBrandHasTransparentPaddingAndAVisibleAlphaMark() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bitmap = android.graphics.Bitmap.createBitmap(96,96,android.graphics.Bitmap.Config.ARGB_8888)
        try {
            val icon = requireNotNull(context.getDrawable(R.drawable.ic_notification_brand))
            icon.setBounds(0,0,96,96);icon.draw(android.graphics.Canvas(bitmap))
            assertEquals(0,android.graphics.Color.alpha(bitmap.getPixel(0,0)))
            var opaque = 0
            for(y in 0 until 96) for(x in 0 until 96) if(android.graphics.Color.alpha(bitmap.getPixel(x,y))>200) opaque++
            assertTrue(opaque > 1200); assertTrue(opaque < 6000)
        } finally { bitmap.recycle() }
    }

}
