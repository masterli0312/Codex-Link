package com.codex.quota

import com.codex.quota.notifications.task.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class RemoteSessionTest {
    private val host = UUID.randomUUID().toString()
    private val thread = UUID.randomUUID().toString()
    private val conversation = TaskInbox.hash(thread)
    private val query = RemoteCommand(UUID.randomUUID().toString(), "session_info", host, thread, conversation, "last", 1)
    private fun event() = RemoteEvent(UUID.randomUUID().toString(), query.id, host, thread, conversation, "session_info", 10, 1,
        context_usage = RemoteContextUsage(60000, 260000), five_hour = RemoteLimitWindow(30.0, 300, 1000))
    @Test fun statusIsBoundToComputerConversationRequestAndSequence() {
        val event = event()
        assertTrue(RemoteSessionRules.accepts(query, null, event))
        assertFalse(RemoteSessionRules.accepts(query, null, event.copy(host_id = UUID.randomUUID().toString())))
        assertFalse(RemoteSessionRules.accepts(query, null, event.copy(thread_id = UUID.randomUUID().toString())))
        assertFalse(RemoteSessionRules.accepts(query, null, event.copy(request_id = UUID.randomUUID().toString())))
        assertFalse(RemoteSessionRules.accepts(query, event, event.copy(seq = 9)))
    }
    @Test fun invalidUsageAndSkillsAreNeverAccepted() {
        assertTrue(RemoteSessionRules.valid(event()))
        assertFalse(RemoteSessionRules.valid(event().copy(context_usage = RemoteContextUsage(-1, 260000))))
        assertFalse(RemoteSessionRules.valid(event().copy(weekly = RemoteLimitWindow(Double.NaN, 10080))))
        assertFalse(RemoteSessionRules.valid(event().copy(skills = listOf(RemoteSkill("../../path", "skill")))))
    }
    @Test fun controlCommandsDoNotResendAttachmentsOrInvokeSkills() {
        val command = query.copy(action = "send", skill_ids = listOf("a".repeat(64)), attachments = listOf(RemoteAttachment(UUID.randomUUID().toString(), "note.txt", "text/plain", "https://ntfy.sh/file/a", 3, "a".repeat(64))))
        val control = RemoteProtocol.control(command, "stop")
        assertTrue(control.attachments.isEmpty())
        assertTrue(control.skill_ids.isEmpty())
        assertEquals(command.id, control.target_id)
    }
    private fun record() = TaskInboxRecord("a".repeat(64), 0, "test", "pairing", TaskConversationSnapshot(
        conversation_id = conversation, remote_ref = RemoteThreadRef(host, thread, "last")), sessionRequest = query,
        sessionResult = event().copy(at = 1000))
    @Test fun freshStatusOpensImmediatelyAndRefreshOnlyAfterExpiryOrNewTurn() {
        val record = record()
        assertNotNull(RemoteSessionCache.display(record))
        assertFalse(RemoteSessionCache.shouldRefresh(record, 2000))
        assertTrue(RemoteSessionCache.shouldRefresh(record, 61000))
        assertTrue(RemoteSessionCache.shouldRefresh(record.copy(snapshot = record.snapshot.copy(
            remote_ref = record.snapshot.remote_ref!!.copy(baseline_turn = "next"))), 2000))
    }
    @Test fun refreshingAndFailureKeepPreviousStatusButNeverAnotherHostOrThread() {
        val record = record().copy(sessionRequest = query.copy(id = "new", issued_at = 2000),
            lastSessionResult = event().copy(at = 1000), sessionResult = null)
        assertNotNull(RemoteSessionCache.display(record))
        assertFalse(RemoteSessionCache.shouldRefresh(record, 3000))
        assertNotNull(RemoteSessionCache.display(record.copy(sessionResult = event().copy(request_id = "new", error = "STATUS_UNAVAILABLE"))))
        assertNull(RemoteSessionCache.display(record.copy(lastSessionResult = event().copy(thread_id = "other"))))
        assertNull(RemoteSessionCache.display(record.copy(lastSessionResult = event().copy(host_id = "other"))))
    }
    @Test fun goalControlsCarryStableGenerationInsteadOfOnlyLiveRevision() {
        val goal = RemoteGoal(thread, "A real objective", "active", 300, 10, 10, 90)
        val command = RemoteGoalRules.command(RemoteThreadRef(host, thread, "last"), conversation, "goal_pause", goal, UUID.randomUUID().toString())
        assertEquals(goal.created_at, command.expected_goal_created_at)
        assertEquals(TaskInbox.hash(goal.objective), command.expected_goal_hash)
    }
}
