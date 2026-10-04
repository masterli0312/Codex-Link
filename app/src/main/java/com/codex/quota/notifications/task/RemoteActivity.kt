package com.codex.quota.notifications.task

import kotlinx.serialization.Serializable

@Serializable
data class RemoteChangedFile(val path: String, val diff: String = "", val kind: String = "unknown")

@Serializable
data class RemoteActivity(val id: String, val turn_id: String, val type: String, val status: String = "completed",
    val title: String = "", val detail: String = "", val files: List<RemoteChangedFile> = emptyList(),
    val exit_code: Int? = null, val duration_ms: Long? = null, val truncated: Boolean = false, val position: Int = -1)

/** Bounded provider projections; a tool result is never reinterpreted as a command to execute. */
object RemoteActivityRules {
    private fun bytes(value: String) = value.toByteArray().size
    private fun size(a: RemoteActivity) = bytes(a.title) + bytes(a.detail) + a.files.sumOf { bytes(it.path) + bytes(it.diff) }
    fun valid(activities: List<RemoteActivity>): Boolean = activities.size <= 40 && activities.map { it.id }.distinct().size == activities.size &&
        activities.sumOf(::size) <= 65_536 && activities.all { a ->
            a.turn_id.length in 1..128 && a.id.length in 1..257 && a.id.startsWith(a.turn_id + ":") &&
                a.position in -1..1_000_000 &&
                a.id.removePrefix(a.turn_id + ":").length in 1..128 &&
                a.type in setOf("commandExecution", "fileChange", "mcpToolCall", "plan", "diff", "compaction") &&
                a.status in setOf("inProgress", "completed", "failed", "declined") && bytes(a.title) <= 512 && bytes(a.detail) <= 16_384 &&
                (a.duration_ms == null || a.duration_ms >= 0) && a.files.size <= 12 && a.files.all { f ->
                    f.path.isNotBlank() && bytes(f.path) <= 1024 && bytes(f.diff) <= 8192 && f.kind in setOf("add", "delete", "update", "unknown")
                }
        }
    fun merge(older: List<RemoteActivity>, newer: List<RemoteActivity>): List<RemoteActivity> {
        val values = linkedMapOf<String, RemoteActivity>()
        (older + newer).forEach { activity ->
            if (valid(listOf(activity))) {
                val previous = values[activity.id]
                if (previous == null || previous.type == activity.type && previous.turn_id == activity.turn_id &&
                    (previous.status == "inProgress" || previous.status == activity.status)) {
                values[activity.id] = if (activity.position < 0 && previous != null) activity.copy(position = previous.position) else activity
            }
            }
        }
        val bounded = values.values.toList().takeLast(40).toMutableList()
        while (bounded.sumOf(::size) > 65_536) bounded.removeAt(0)
        return bounded
    }
}
