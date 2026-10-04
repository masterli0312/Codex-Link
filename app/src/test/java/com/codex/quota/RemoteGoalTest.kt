package com.codex.quota

import com.codex.quota.notifications.task.*
import org.junit.Assert.*
import org.junit.Test

class RemoteGoalTest {
    private val host = "11111111-2222-3333-4444-555555555555"
    private val thread = "22222222-2222-3333-4444-555555555555"
    private val ref = RemoteThreadRef(host, thread, "turn")
    private val goal = RemoteGoal(thread, "Complete the fixture", "active", 42, 3, 1, 5)

    @Test fun goalStateIsBoundedAndBoundToTheActualThread() {
        assertTrue(RemoteGoalRules.valid(goal, thread))
        assertFalse(RemoteGoalRules.valid(goal.copy(thread_id = host), thread))
        assertFalse(RemoteGoalRules.valid(goal.copy(status = "invented"), thread))
        assertFalse(RemoteGoalRules.valid(goal.copy(tokens_used = -1), thread))
        assertFalse(RemoteGoalRules.validObjective("x".repeat(4001)))
        assertFalse(RemoteGoalRules.validObjective(" "))
    }
    @Test fun startRequiresNoExistingGoalAndResumeRequiresPausedProviderState() {
        val start = RemoteGoalRules.command(ref, TaskInbox.hash(thread), "goal_start", null, objective = "Work", budget = 2000)
        assertEquals(0, start.expected_goal_updated_at)
        assertEquals("Work", start.objective)
        assertEquals(2000L, start.token_budget)
        val resumed = RemoteGoalRules.command(ref, TaskInbox.hash(thread), "goal_resume", goal.copy(status = "paused"))
        assertEquals("", resumed.objective)
        assertEquals(5L, resumed.expected_goal_updated_at)
        assertThrows(IllegalArgumentException::class.java) { RemoteGoalRules.command(ref, TaskInbox.hash(thread), "goal_resume", goal) }
        assertThrows(IllegalArgumentException::class.java) { RemoteGoalRules.command(ref, TaskInbox.hash(thread), "goal_complete", goal) }
    }
    @Test fun authenticatedGoalResultsRejectOtherComputersAndOlderSnapshots() {
        val c = RemoteGoalRules.command(ref, TaskInbox.hash(thread), "goal_read", null)
        val event = RemoteEvent(host, c.id, host, thread, c.conversation_id, "goal", 2, 100, goal = goal)
        assertTrue(RemoteGoalRules.accepts(c, null, event))
        assertFalse(RemoteGoalRules.accepts(c, event, event.copy(seq = 1)))
        assertFalse(RemoteGoalRules.accepts(c, null, event.copy(host_id = thread)))
        assertFalse(RemoteGoalRules.accepts(c, null, event.copy(conversation_id = TaskInbox.hash(host))))
    }
    @Test fun ordinaryTurnCompletionCannotMarkAContinuousGoalComplete() {
        val c = RemoteGoalRules.command(ref, TaskInbox.hash(thread), "goal_start", null, objective = "Work")
        val e = RemoteEvent(host, c.id, host, thread, c.conversation_id, "running", 1, 100, turn_id = "turn", goal = goal)
        val snapshot = TaskConversationSnapshot(conversation_id = c.conversation_id, remote_ref = ref)
        val record = TaskInboxRecord("id", 100, "root", "endpoint", snapshot, remoteState = RemoteConversationState(c, e))
        val incoming = record.copy(remoteState = null, receivedAt = 101)
        assertEquals("running", ConversationIndex.mergeNotification(record, incoming, true).remoteState?.event?.status)
    }
}
