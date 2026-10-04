package com.codex.quota

import com.codex.quota.notifications.task.*
import org.junit.Assert.*
import org.junit.Test

class ConversationActivityGroupsTest {
    private fun item(id: String, type: String = "mcpToolCall", title: String = "github / fetch", turn: String = "turn") =
        ConversationTimelineEntry.Activity(RemoteActivity("$turn:$id", turn, type, title = title, detail = "full detail $id"))

    @Test fun consecutiveCallsKeepEveryNativeItemAndAStableKey() {
        val first = item("one")
        val second = item("two").copy(value = item("two").value.copy(status = "inProgress"))
        val group = ConversationActivityGroups.present(listOf(first, second)).single() as ConversationTimelineEntry.ActivityGroup
        assertEquals(first.key, group.key)
        assertEquals(listOf(first.value, second.value), group.values)
        assertEquals("fetch · GitHub", ConversationActivityGroups.tool(first.value)?.display)
        val updated = ConversationActivityGroups.present(listOf(first, second.copy(value = second.value.copy(status = "completed"))))
        assertEquals(group.key, updated.single().key)
    }

    @Test fun differentToolsServersTurnsAndMessagesBreakGroups() {
        val message = ConversationTimelineEntry.Message(TaskConversationMessage("assistant", "comment"), "message")
        val entries = listOf(item("a"), message, item("b"), item("c", title = "github / fetch_file"),
            item("d", title = "other / fetch_file"), item("e", title = "other / fetch_file", turn = "next"))
        val result = ConversationActivityGroups.present(entries)
        assertEquals(entries.map { it.key }, result.map { it.key })
        assertEquals(message, result[1])
        assertEquals(5, result.filterIsInstance<ConversationTimelineEntry.ActivityGroup>().size)
    }

    @Test fun commandsAndFilesCollapseWithoutLosingTheirDetailsOrChangingOrder() {
        val entries = listOf(item("a", "commandExecution", "secret command"), item("b", "commandExecution", "other command"),
            item("c", "fileChange", ""), item("d", "fileChange", ""))
        val result = ConversationActivityGroups.present(entries).filterIsInstance<ConversationTimelineEntry.ActivityGroup>()
        assertEquals(2, result.size)
        assertEquals(entries.map { it.value }, result.flatMap { it.values })
        assertNull(ConversationActivityGroups.tool(entries.first().value))
    }

    @Test fun oldOrTruncatedToolLabelsAreNeverGuessed() {
        val malformed = item("one", title = "unknown")
        val truncated = item("two").let { it.copy(value = it.value.copy(truncated = true)) }
        val ambiguous = item("three", title = "server / tool / other")
        val original = listOf(malformed, truncated, ambiguous)
        assertEquals(original, ConversationActivityGroups.present(original))
    }
}
