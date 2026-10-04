package com.codex.quota.notifications.task

/** Presentation only. Native IDs, order and full detail remain in the stored activities. */
object ConversationActivityGroups {
    data class Tool(val server: String, val name: String) {
        val display: String get() = name.replace('_', ' ') + " · " + when (server.lowercase()) {
            "github", "mcp__github", "github@openai-curated-remote" -> "GitHub"
            else -> server
        }
    }

    // remote-activity.cjs projects the actual public item.server / item.tool into title.
    // Old/truncated or unrecognised titles stay ungrouped rather than inventing a provider.
    fun tool(a: RemoteActivity): Tool? {
        if (a.type != "mcpToolCall" || a.truncated) return null
        val parts = a.title.split(" / ")
        return if (parts.size == 2 && parts.all { it.isNotBlank() }) Tool(parts[0], parts[1]) else null
    }

    fun present(entries: List<ConversationTimelineEntry>): List<ConversationTimelineEntry> {
        val result = mutableListOf<ConversationTimelineEntry>()
        var index = 0
        while (index < entries.size) {
            val first = (entries[index] as? ConversationTimelineEntry.Activity)?.value
            if (first == null || first.type !in setOf("commandExecution", "fileChange", "mcpToolCall") ||
                first.type == "mcpToolCall" && tool(first) == null) {
                result += entries[index++]
                continue
            }
            val group = mutableListOf(first)
            index++
            while (index < entries.size) {
                val next = (entries[index] as? ConversationTimelineEntry.Activity)?.value ?: break
                if (next.turn_id != first.turn_id || next.type != first.type ||
                    first.type == "mcpToolCall" && tool(next) != tool(first)) break
                group += next
                index++
            }
            result += if (group.size > 1 || first.type == "mcpToolCall")
                ConversationTimelineEntry.ActivityGroup(group) else ConversationTimelineEntry.Activity(first)
        }
        return result
    }
}
