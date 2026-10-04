package com.codex.quota

import com.codex.quota.notifications.task.*
import org.junit.Assert.*
import org.junit.Test

class ConversationTimelineTest {
    private fun taskState(status: String, turn: String = "phone-turn", pending: Boolean = false) = RemoteConversationState(
        RemoteCommand("request", "send", "computer", "thread", "conversation", "baseline", 1),
        RemoteEvent("event", "request", "computer", "thread", "conversation", status, 1, 2,
            turn_id = turn, attachment_pending = pending)
    )

    @Test fun aNewDesktopTurnDoesNotShowThePreviousPhoneCompletionOrError() {
        val desktopRunning = TaskConversationSnapshot(running = true,
            thread_ref = RemoteThreadRef("computer", "thread", "desktop-turn"))
        for (status in listOf("completed", "failed", "interrupted")) {
            assertFalse(ConversationTimeline.showsTaskStatus(desktopRunning, taskState(status)))
        }
        assertTrue(ConversationTimeline.showsTaskStatus(desktopRunning, taskState("approval")))
        assertTrue(ConversationTimeline.showsTaskStatus(desktopRunning, taskState("input_required")))
    }

    @Test fun aNewerFinishedDesktopTurnDoesNotKeepAnOlderPhoneFailure() {
        val desktopFinished = TaskConversationSnapshot(remote_ref = RemoteThreadRef("computer", "thread", "desktop-turn"))
        assertFalse(ConversationTimeline.showsTaskStatus(desktopFinished, taskState("failed")))
        assertFalse(ConversationTimeline.showsTaskStatus(desktopFinished, taskState("failed", turn = "")))
        assertTrue(ConversationTimeline.showsTaskStatus(desktopFinished, taskState("completed", turn = "desktop-turn")))
        // A freshly completed phone reply can update remote_ref before the next library snapshot replaces thread_ref.
        assertTrue(ConversationTimeline.showsTaskStatus(desktopFinished.copy(
            thread_ref = RemoteThreadRef("computer", "thread", "older-turn")), taskState("completed", turn = "desktop-turn")))
    }

    @Test fun pendingDownloadsAndUnconfirmedTasksRemainVisible() {
        val snapshot = TaskConversationSnapshot(running = true)
        assertTrue(ConversationTimeline.showsTaskStatus(snapshot, taskState("completed", pending = true)))
        assertTrue(ConversationTimeline.showsTaskStatus(snapshot, taskState("unknown")))
        assertTrue(ConversationTimeline.showsTaskStatus(snapshot, taskState("running")))
        assertFalse(ConversationTimeline.showsTaskStatus(snapshot, null))
        assertTrue(ConversationTimeline.showsTaskStatus(TaskConversationSnapshot(), taskState("failed")))
    }

    private fun message(turn: String, id: String, position: Int) = TaskConversationMessage("assistant", id, "$turn:$id", position)
    private fun command(turn: String, id: String, position: Int) = RemoteActivity("$turn:$id", turn, "commandExecution", title = id, position = position)

    @Test fun completedCurrentMessagesKeepTheirStreamingRendererIdentity() {
        val user = TaskConversationMessage("user", "Question", "turn:user-id", 0)
        val comment = message("turn", "comment", 1)
        val final = message("turn", "answer", 3)
        val result = ConversationTimeline.build(listOf(user, comment, final), listOf(command("turn", "cmd", 2)), "turn")
        assertEquals(listOf(ConversationTimeline.liveUserKey("turn"), "message:turn:comment", "activity:turn:cmd",
            ConversationTimeline.liveReplyKey("turn")), result.map { it.key })
        assertEquals(4, result.map { it.key }.distinct().size)
    }

    @Test fun continuityNeverChangesAnotherTurnsIdentityOrDropsInterleavedInput() {
        val older = message("older", "answer", 1)
        val first = TaskConversationMessage("user", "Same", "turn:first", 0)
        val steered = TaskConversationMessage("user", "Same", "turn:steer", 2)
        val result = ConversationTimeline.build(listOf(older, first, steered, message("turn", "answer", 3)), emptyList(), "turn")
        assertEquals("message:older:answer", result.first().key)
        assertEquals("message:turn:steer", result[2].key)
        assertEquals(listOf(older, first, steered, message("turn", "answer", 3)), result.map { (it as ConversationTimelineEntry.Message).value })
    }

    @Test fun nativePositionsPlaceCommandsBetweenCommentaryAndFinalReply() {
        val messages = listOf(message("old", "q", 0), message("old", "comment", 2), message("old", "answer", 6), message("new", "q", 0))
        val activities = listOf(command("old", "cmd", 3), command("old", "patch", 5), command("new", "cmd", 1))
        assertEquals(listOf("message:old:q", "message:old:comment", "activity:old:cmd", "activity:old:patch", "message:old:answer", "message:new:q", "activity:new:cmd"),
            ConversationTimeline.build(messages, activities).map { it.key })
    }

    @Test fun providerTurnOrderWinsOverCachedActivityArrivalOrder() {
        val messages = listOf(message("old", "answer", 3), message("new", "answer", 3))
        val activities = listOf(command("new", "cmd", 1), command("old", "cmd", 1))
        assertEquals(listOf("activity:old:cmd", "message:old:answer", "activity:new:cmd", "message:new:answer"),
            ConversationTimeline.build(messages, activities).map { it.key })
    }

    @Test fun legacyMessagesAndUnpositionedItemsAreNotDroppedOrMergedByText() {
        val messages = listOf(TaskConversationMessage("user", "same"), TaskConversationMessage("assistant", "same"), message("turn", "answer", 4))
        val activities = listOf(command("turn", "legacy", -1), command("active", "cmd", -1))
        val result = ConversationTimeline.build(messages, activities)
        assertEquals(5, result.size)
        assertEquals(5, result.map { it.key }.distinct().size)
        assertEquals(listOf("message:turn:answer", "activity:turn:legacy", "activity:active:cmd"), result.takeLast(3).map { it.key })
    }

    @Test fun metadataSurvivesAnOlderUnpositionedUpdate() {
        val positioned = command("turn", "cmd", 2)
        assertEquals(2, RemoteActivityRules.merge(listOf(positioned), listOf(positioned.copy(position = -1, detail = "Updated output"))).single().position)
        assertEquals("Updated output", RemoteActivityRules.merge(listOf(positioned), listOf(positioned.copy(position = -1, detail = "Updated output"))).single().detail)
    }
    @Test fun acceptedInterventionsMoveAboveExecutionOnlyWithinTheirOwnTurn() {
        val old = message("old","answer",0)
        val root = TaskConversationMessage("user","Original","turn:u",position = 0)
        val first = TaskConversationMessage("user","First guide","turn:g1",position = 3)
        val second = TaskConversationMessage("user","Second guide","turn:g2",position = 5)
        val timeline = ConversationTimeline.build(listOf(old,root,message("turn","progress",2),first,second),listOf(command("turn","cmd",1)))
        val ordered = ConversationTimeline.userInputsFirst(timeline,"turn")
        assertEquals(listOf("old:answer","turn:u","turn:g1","turn:g2"),ordered.take(4).filterIsInstance<ConversationTimelineEntry.Message>().map { it.value.id })
        assertEquals("activity:turn:cmd",ordered[4].key)
        assertEquals(2,ConversationTimeline.turnGroups(ordered).size)
    }

}
