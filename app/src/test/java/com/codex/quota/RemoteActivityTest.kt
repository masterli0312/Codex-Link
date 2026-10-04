package com.codex.quota

import com.codex.quota.notifications.task.*
import org.junit.Assert.*
import org.junit.Test

class RemoteActivityTest {
    private val done = RemoteActivity("turn:cmd", "turn", "commandExecution", "completed",
        title = "gradle test", detail = "Tests passed", exit_code = 0, duration_ms = 32)

    @Test fun terminalProviderResultsSurviveDelayedRunningCache() {
        val stale = done.copy(status = "inProgress", detail = "", exit_code = null)
        assertEquals(listOf(done), RemoteActivityRules.merge(listOf(done), listOf(stale)))
        assertEquals(listOf(done), RemoteActivityRules.merge(listOf(stale), listOf(done)))
        val failed = done.copy(status = "failed", exit_code = 2)
        assertEquals(listOf(failed), RemoteActivityRules.merge(listOf(failed), listOf(stale)))
        assertEquals("More output", RemoteActivityRules.merge(listOf(done), listOf(done.copy(detail = "More output"))).single().detail)
        assertEquals(listOf(done), RemoteActivityRules.merge(listOf(done), listOf(done.copy(type = "plan"))))
    }

    @Test fun itemIdentityIncludesTurnAndRejectsMissingOrMismatchedIds() {
        val later = done.copy(id = "next:cmd", turn_id = "next", exit_code = 2)
        assertEquals(listOf(done, later), RemoteActivityRules.merge(listOf(done), listOf(later)))
        assertFalse(RemoteActivityRules.valid(listOf(done.copy(turn_id = "another"))))
        assertFalse(RemoteActivityRules.valid(listOf(done.copy(id = "turn:"))))
        val turn = "t".repeat(128)
        assertTrue(RemoteActivityRules.valid(listOf(done.copy(id = turn + ":" + "i".repeat(128), turn_id = turn))))
        assertFalse(RemoteActivityRules.valid(listOf(done, done)))
        assertFalse(RemoteActivityRules.valid(listOf(done.copy(type = "reasoning"))))
        assertFalse(RemoteActivityRules.valid(listOf(done.copy(duration_ms = -1))))
    }

    @Test fun cachedOutputIsBoundedAndNativeDiffRemainsPlainData() {
        val items = (0..79).map { done.copy(id = "turn:$it", detail = "x".repeat(8192)) }
        val merged = RemoteActivityRules.merge(emptyList(), items)
        assertTrue(merged.size <= 40)
        assertTrue(RemoteActivityRules.valid(merged))
        assertFalse(RemoteActivityRules.valid(listOf(done.copy(detail = "文".repeat(6000)))))
        val file = RemoteChangedFile("src/App.kt", "@@ -1 +1 @@\n-old\n+new", "update")
        val patch = done.copy(type = "fileChange", files = listOf(file), exit_code = null)
        assertTrue(RemoteActivityRules.valid(listOf(patch)))
        assertEquals(file.diff, RemoteActivityRules.merge(emptyList(), listOf(patch)).single().files.single().diff)
    }
}
