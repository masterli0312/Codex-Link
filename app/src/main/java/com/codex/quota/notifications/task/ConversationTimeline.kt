package com.codex.quota.notifications.task

sealed interface ConversationTimelineEntry {
    val key: String
    data class Message(val value: TaskConversationMessage, override val key: String) : ConversationTimelineEntry
    data class Activity(val value: RemoteActivity) : ConversationTimelineEntry {
        override val key: String get() = "activity:" + value.id
    }
    data class ActivityGroup(val values: List<RemoteActivity>) : ConversationTimelineEntry {
        override val key: String get() = "activity:" + values.first().id
    }
}

/** Use native within-turn positions, never receipt times, to interleave visible items. */
object ConversationTimeline {
    fun turn(entry: ConversationTimelineEntry): String = when(entry) {
        is ConversationTimelineEntry.Message -> entry.value.id.substringBefore(':', "")
        is ConversationTimelineEntry.Activity -> entry.value.turn_id
        is ConversationTimelineEntry.ActivityGroup -> entry.values.first().turn_id
    }
    fun turnGroups(entries: List<ConversationTimelineEntry>): List<List<ConversationTimelineEntry>> =
        entries.groupBy { turn(it) }.values.toList()
    /** Accepted interventions join the latest turn's input; its execution follows below. */
    fun userInputsFirst(entries: List<ConversationTimelineEntry>, turn: String): List<ConversationTimelineEntry> {
        if (turn.isBlank()) return entries
        val input = entries.filter { it is ConversationTimelineEntry.Message && it.value.role == "user" && it.value.id.substringBefore(':', "") == turn }
        if (input.size < 2) return entries
        val first = entries.indexOfFirst { it in input }
        return entries.take(first) + input + entries.drop(first).filterNot { it in input }
    }
    /** A terminal phone task is not the status of a later desktop turn. Keep active interactions and downloads. */
    fun showsTaskStatus(snapshot: TaskConversationSnapshot, state: RemoteConversationState?): Boolean {
        state ?: return false
        val event = state.event ?: return true
        if (event.attachment_pending || event.status !in setOf("completed", "failed", "interrupted")) return true
        if (snapshot.running) return false
        val latestTurn = (snapshot.remote_ref ?: snapshot.thread_ref)?.baseline_turn.orEmpty()
        val taskTurn = event.turn_id.ifBlank { state.command.baseline_turn }
        return latestTurn.isBlank() || taskTurn.isBlank() || latestTurn == taskTurn
    }

    fun liveUserKey(turn: String): String = "current-user:$turn"
    fun liveReplyKey(turn: String): String = "current-reply:$turn"

    fun build(messages: List<TaskConversationMessage>, activities: List<RemoteActivity>, continuityTurn: String = ""): List<ConversationTimelineEntry> {
        val currentMessages = messages.filter { continuityTurn.isNotBlank() && it.id.substringBefore(':', "") == continuityTurn }
        val currentUser = currentMessages.firstOrNull { it.role == "user" }
        val currentReply = currentMessages.lastOrNull { it.role == "assistant" }
        val remaining = activities.distinctBy { it.id }.groupBy { it.turn_id }.toMutableMap()
        val result = mutableListOf<ConversationTimelineEntry>()
        var index = 0
        while (index < messages.size) {
            val turn = messages[index].id.substringBefore(':', "")
            if (turn.isBlank()) {
                result += ConversationTimelineEntry.Message(messages[index], "legacy-message:$index")
                index++
                continue
            }
            val group = mutableListOf<ConversationTimelineEntry>()
            while (index < messages.size && messages[index].id.substringBefore(':', "") == turn) {
                val message = messages[index]
                val key = when {
                    message === currentUser -> liveUserKey(continuityTurn)
                    message === currentReply -> liveReplyKey(continuityTurn)
                    else -> "message:" + message.id
                }
                group += ConversationTimelineEntry.Message(message, key)
                index++
            }
            val tools = remaining.remove(turn).orEmpty()
            // Old caches lack a native position. Keep those items after their turn rather
            // than inventing a position before a reply or placing them in another turn.
            if (group.all { (it as ConversationTimelineEntry.Message).value.position >= 0 }) {
                val ordered = group + tools.filter { it.position >= 0 }.map { ConversationTimelineEntry.Activity(it) }
                result += ordered.sortedBy {
                    when (it) {
                        is ConversationTimelineEntry.Message -> it.value.position
                        is ConversationTimelineEntry.Activity -> it.value.position
                        is ConversationTimelineEntry.ActivityGroup -> it.values.first().position
                    }
                }
                result += tools.filter { it.position < 0 }.map { ConversationTimelineEntry.Activity(it) }
            } else {
                result += group
                result += tools.map { ConversationTimelineEntry.Activity(it) }
            }
        }
        // Live tool items can precede the first persisted agent message.
        remaining.values.forEach { group ->
            result += group.sortedWith(compareBy { if (it.position < 0) Int.MAX_VALUE else it.position })
                .map { ConversationTimelineEntry.Activity(it) }
        }
        return result
    }
}
