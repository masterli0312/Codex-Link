package com.codex.quota

import com.codex.quota.notifications.task.*
import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.encodeToString

class ConversationCompletionStateTest {
    private val host = "11111111-2222-3333-4444-555555555555"
    private val thread = "22222222-2222-3333-4444-555555555555"
    @Test fun completionIsUnreadUntilOpenedAndOldOrDuplicateEventsCannotRestoreIt() {
        val initial = SyncedConversation(RemoteThreadSummary(thread))
        val running = ConversationSyncRules.activity(initial, "turn", true, 100, false)
        assertTrue(running.thread.running); assertFalse(running.unread)
        val done = ConversationSyncRules.activity(running, "turn", false, 200, true)
        assertFalse(done.thread.running); assertTrue(done.unread)
        val seen = done.copy(seenCompletionAt = done.completedAt)
        assertEquals(seen, ConversationSyncRules.activity(seen, "turn", true, 100, false))
        assertEquals(seen, ConversationSyncRules.activity(seen, "turn", false, 300, true))
        val next = ConversationSyncRules.activity(seen, "next", true, 400, false)
        assertEquals(next, ConversationSyncRules.activity(next, "turn", false, 500, true))
        assertTrue(ConversationSyncRules.activity(next, "next", false, 600, true).unread)
    }
    @Test fun authenticatedActivityMustMatchTheThreadAndCannotMasqueradeAsAnotherConversation() {
        val key = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 4 })
        val event = RemoteEvent(host, thread, host, thread, TaskInbox.hash(thread), "thread_activity", 1, 100,
            turn_id = "turn", activity_running = false, activity_completed = true)
        fun parse(e: RemoteEvent) = RemoteProtocol.event(RemoteProtocol.wire("event", e.id, RemoteProtocol.json.encodeToString(e), key), key)
        assertNotNull(parse(event)); assertNull(parse(event.copy(request_id = host)))
        assertNull(parse(event.copy(conversation_id = TaskInbox.hash(host))))
        assertNull(parse(event.copy(activity_running = true)))
    }
    @Test fun officeAndPdfFilesUseCorrectViewerTypesAndQuestionRepliesAreReadable() {
        assertEquals("application/pdf", ConversationDocumentTypes.mime("report.PDF"))
        assertEquals("application/vnd.openxmlformats-officedocument.presentationml.presentation", ConversationDocumentTypes.mime("slides.pptx"))
        assertEquals("application/vnd.openxmlformats-officedocument.wordprocessingml.document", ConversationDocumentTypes.mime("report.docx"))
        val text = "<send_user_message_question_reply>\n[{\"questionItemId\":\"fixture\",\"question\":\"Environment?\",\"answer\":\"Cloud\"}]\n</send_user_message_question_reply>"
        assertEquals("Environment?\nCloud", ConversationDisplayText.userText(text))
        assertNull(ConversationDisplayText.questionAnswers(text.replace("fixture", "")))
    }
}
