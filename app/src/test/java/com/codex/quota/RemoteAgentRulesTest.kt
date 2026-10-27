package com.codex.quota

import com.codex.quota.notifications.task.*
import com.codex.quota.ui.feature.taskhistory.elapsedText
import com.codex.quota.ui.feature.taskhistory.shortModel
import kotlinx.serialization.decodeFromString
import org.junit.Assert.*
import org.junit.Test

class RemoteAgentRulesTest {
    private val host = "11111111-2222-3333-4444-555555555555"
    private fun command(action: String) = RemoteCommand("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", action, host, host,
        TaskInbox.hash(host), action, 1L)
    private fun event(c: RemoteCommand, status: String, error: String = "") = RemoteEvent("ffffffff-bbbb-cccc-dddd-eeeeeeeeeeee",
        c.id, host, host, c.conversation_id, status, 1, 1, error = error)

    @Test fun repliesMatchOnlyTheirOwnHostCommandAndStatus() {
        val tasks = command("tasks")
        assertTrue(RemoteAgentRules.matches(tasks, event(tasks, "tasks")))
        assertFalse(RemoteAgentRules.matches(tasks, event(tasks, "agents")))
        assertTrue(RemoteAgentRules.matches(tasks, event(tasks, "failed", "TASKS_DISABLED")))
        assertFalse(RemoteAgentRules.matches(tasks, event(tasks, "failed")))
        assertFalse(RemoteAgentRules.matches(tasks, event(command("tasks").copy(id = "bbbbbbbb-bbbb-cccc-dddd-eeeeeeeeeeee"), "tasks")))
        assertEquals("snapshot", RemoteAgentRules.replyStatus("agent_read"))
    }

    @Test fun eventsFromOlderAndNewerBridgesDecode() {
        val old = RemoteProtocol.json.decodeFromString<RemoteEvent>("""{"id":"x","request_id":"y","host_id":"h","thread_id":"h","conversation_id":"c","status":"presence","seq":1,"at":1}""")
        assertTrue(old.capabilities.isEmpty())
        val next = RemoteProtocol.json.decodeFromString<RemoteEvent>("""{"id":"x","request_id":"y","host_id":"h","thread_id":"h","conversation_id":"c","status":"tasks","seq":1,"at":1,
            "capabilities":["agents","tasks"],"tasks":[{"id":"t1","model":"google-antigravity/gemini-3.8-flash","status":"running","can_stop":true,"future_field":1}],
            "agents":[{"thread_id":"a","status":"running","turn_id":"t"}]}""")
        assertEquals(listOf("agents", "tasks"), next.capabilities)
        assertTrue(next.tasks.single().can_stop)
        assertTrue(RemoteAgentRules.stoppable(next.agents.single()))
    }

    @Test fun runningTasksSortFirstThenNewest() {
        val sorted = RemoteAgentRules.sortTasks(listOf(RemoteBackgroundTask("old", status = "succeeded", started_at = 3),
            RemoteBackgroundTask("run", status = "running", started_at = 1), RemoteBackgroundTask("new", status = "failed", started_at = 5)))
        assertEquals(listOf("run", "new", "old"), sorted.map { it.id })
        assertFalse(RemoteAgentRules.stoppable(RemoteAgent("a", status = "running")))
        assertFalse(RemoteAgentRules.stoppable(RemoteAgent("a", status = "completed", turn_id = "t")))
    }

    @Test fun formatting() {
        assertEquals("2:05", elapsedText(1_000, 126_000, 0))
        assertEquals("1:00:01", elapsedText(1_000, 0, 3_602_000))
        assertEquals("", elapsedText(0, 0, 10))
        assertEquals("gemini-3.8-flash", shortModel("google-antigravity/gemini-3.8-flash"))
        assertEquals("gpt-6.1-terra", shortModel("gpt-6.1-terra"))
    }
}
