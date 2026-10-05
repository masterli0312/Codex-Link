package com.codex.quota

import com.codex.quota.notifications.task.*
import org.junit.Assert.*
import org.junit.Test

class DraftInteractionTest {
    private val host = "11111111-1111-1111-1111-111111111111"
    private val thread = "22222222-2222-2222-2222-222222222222"
    private val request = "33333333-3333-3333-3333-333333333333"
    private val command = RemoteCommand(request, "queue", host, thread, TaskInbox.hash(thread), "before", 1000, text = "change")
    private fun event(status: String) = RemoteEvent(host, request, host, thread, command.conversation_id, status, 1, 1001, turn_id = "turn")
    @Test fun confirmedSentMessagesDisappearFromPendingArea() {
        for (status in listOf("accepted", "running", "steered", "completed", "interrupted", "cancelled"))
            assertFalse(status, FollowUpState.visible(RemoteConversationState(command, event(status))))
        assertTrue(FollowUpState.visible(RemoteConversationState(command)))
        assertTrue(FollowUpState.visible(RemoteConversationState(command, event("queued"))))
        assertTrue(FollowUpState.visible(RemoteConversationState(command, event("unknown"))))
        assertTrue(FollowUpState.visible(RemoteConversationState(command, event("failed").copy(error = "STEER_REJECTED"))))
        assertTrue(FollowUpState.visible(RemoteConversationState(command, event("running").copy(attachment_pending = true))))
    }
    @Test fun anUnconfirmedRequestIsRecoverableWithoutLockingComposerOperations() {
        val state = RemoteConversationState(command.copy(action = "steer"), event("unknown").copy(error = "REQUEST_NOT_FOUND"))
        assertFalse(FollowUpState.pending(state))
        assertTrue(FollowUpState.visible(state))
        assertTrue(FollowUpState.recoverable(state))
    }
    @Test fun aSelectionAddsSeveralPhotosWithoutRemovingOrDuplicatingPreviousOnes() {
        assertEquals(listOf("a", "b", "c"), SelectedAttachmentRules.merge(listOf("a"), listOf("a", "b", "c")) { it })
        assertEquals(listOf("a", "b"), SelectedAttachmentRules.merge(emptyList<String>(), listOf("a", "b")) { it })
        val before = listOf("a", "b")
        assertEquals(listOf("a", "b", "c", "d"), SelectedAttachmentRules.merge(before, listOf("c", "d")) { it })
        assertEquals(10, SelectedAttachmentRules.merge(emptyList<Int>(), (1..10).toList()) { it }.size)
        assertThrows(IllegalArgumentException::class.java) { SelectedAttachmentRules.merge((1..10).toList(), listOf(11)) { it } }
        assertEquals(listOf("a", "b"), before)
    }
    private fun watched(): TaskInboxRecord {
        val watch = command.copy(action = "read", watch = true, read_thread_id = thread)
        val ref = RemoteThreadRef(host, thread, "current-desktop-turn")
        return TaskInboxRecord("record", 1, "identity", "pair", TaskConversationSnapshot(conversation_id = command.conversation_id,
            running = true, thread_ref = ref), libraryRequest = watch, libraryResult = event("snapshot"))
    }
    @Test fun aConfirmedDesktopTurnUsesItsOwnWatchForImmediateSteering() {
        val record = watched()
        assertTrue(RemoteComposerRules.followUp(record))
        assertTrue(RemoteFollowUpRules.desktop(record))
        assertTrue(RemoteComposerRules.immediateFollowUp(record, "queue"))
        val c = RemoteProtocol.followUp(requireNotNull(RemoteFollowUpRules.source(record)), "steer", "now")
        assertEquals(request, c.target_id)
        assertEquals("current-desktop-turn", c.expected_turn_id)
        assertEquals("steer", c.action)
        assertFalse("A mutation must not inherit the read-only watch flag", c.watch)
        assertFalse("Status must remain valid when recovering a desktop steer", RemoteProtocol.control(c,"status").watch)
        assertTrue(RemoteComposerRules.canSend(record, true, false))
        assertFalse(RemoteComposerRules.canSend(record, true, true))
    }
    @Test fun explicitSteeringDoesNotBecomeADeferredMessageOnPhoneTurns() {
        val phone = watched().copy(remoteState = RemoteConversationState(command.copy(action = "send"), event("running").copy(turn_id = "current-desktop-turn")))
        assertFalse(RemoteFollowUpRules.desktop(phone))
        assertTrue(RemoteComposerRules.immediateFollowUp(phone, "steer"))
        assertFalse(RemoteComposerRules.immediateFollowUp(phone, "queue"))
    }
    @Test fun disconnectedCompletedOrOtherComputerWatchesCannotSteer() {
        val record = watched()
        for (bad in listOf(record.copy(snapshot = record.snapshot.copy(running = false)),
            record.copy(libraryRequest = record.libraryRequest!!.copy(watch = false)),
            record.copy(libraryResult = record.libraryResult!!.copy(host_id = thread)),
            record.copy(libraryResult = record.libraryResult!!.copy(error = "LIBRARY_UNAVAILABLE")),
            record.copy(libraryResult = record.libraryResult!!.copy(attachment_pending = true)))) {
            assertNull(RemoteFollowUpRules.source(bad))
        }
    }
    @Test fun stalePhoneTurnCannotOverrideTheCurrentDesktopTurn() {
        val record = watched().copy(remoteState = RemoteConversationState(command.copy(action = "send"), event("running").copy(turn_id = "old")))
        assertTrue(RemoteFollowUpRules.desktop(record))
        assertEquals("current-desktop-turn", RemoteFollowUpRules.source(record)!!.event!!.turn_id)
    }
    @Test fun queuedPromotionPreservesTheOriginalIdentityAndHasAnExactTurnGuard() {
        val state = RemoteConversationState(command.copy(action = "send"), event("running"))
        val c = RemoteProtocol.followUp(state, "steer", "change", host)
        assertEquals(host, c.queue_id);assertEquals("turn", c.expected_turn_id)
        assertThrows(IllegalArgumentException::class.java) { RemoteProtocol.followUp(state, "steer", "change", "../other") }
    }
}
