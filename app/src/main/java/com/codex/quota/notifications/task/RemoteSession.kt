package com.codex.quota.notifications.task

import kotlinx.serialization.Serializable

@Serializable
data class RemoteContextUsage(val used_tokens: Long, val window_tokens: Long)
@Serializable
data class RemoteLimitWindow(val used_percent: Double, val duration_mins: Int, val reset_at: Long? = null)
@Serializable
data class RemoteSkill(val id: String, val name: String, val description: String = "", val plugin: Boolean = false, val display_name: String = "")
@Serializable
data class RemoteAttachment(val id: String, val name: String, val mime: String, val url: String, val size: Long, val sha256: String) {
    override fun toString() = "RemoteAttachment(id=$id,size=$size)"
}
object RemoteSessionRules {
    val permissions = listOf("default", "review", "readonly", "full")
    fun valid(event: RemoteEvent): Boolean = event.context_usage?.let { it.used_tokens >= 0 && it.window_tokens > 0 } != false &&
        listOfNotNull(event.five_hour, event.weekly).all { it.used_percent.isFinite() && it.used_percent in 0.0..100.0 &&
            it.duration_mins > 0 && (it.reset_at == null || it.reset_at >= 0) } &&
        event.skills.size <= 100 && event.skills.map { it.id }.distinct().size == event.skills.size && event.skills.all {
            it.id.matches(Regex("[a-f0-9]{64}")) && it.name.isNotBlank() && it.name.toByteArray().size <= 160 && it.description.toByteArray().size <= 1600 && it.display_name.toByteArray().size <= 160
        }
    fun accepts(query: RemoteCommand?, previous: RemoteEvent?, event: RemoteEvent): Boolean = query != null &&
        event.request_id == query.id && event.host_id == query.host_id && event.thread_id == query.thread_id &&
        event.conversation_id == query.conversation_id && event.status == query.action && (event.seq > (previous?.seq ?: 0) ||
        previous?.attachment_pending == true && !event.attachment_pending && event.seq == previous.seq && event.id == previous.id && event.at == previous.at)
}

/** Cached display data is separate from the acknowledgement of the latest request. */
object RemoteSessionCache {
    fun usable(record: TaskInboxRecord, event: RemoteEvent?): Boolean {
        event ?: return false
        val ref = record.snapshot.thread_ref ?: record.snapshot.remote_ref ?: return false
        return event.status == "session_info" && event.host_id == ref.host_id && event.thread_id == ref.thread_id &&
            event.conversation_id == record.snapshot.conversation_id && !event.partial && !event.attachment_pending &&
            event.error.isBlank() && RemoteSessionRules.valid(event)
    }
    fun display(record: TaskInboxRecord): RemoteEvent? =
        listOfNotNull(record.sessionResult, record.lastSessionResult).filter { usable(record, it) }.maxByOrNull { it.at }

    fun shouldRefresh(record: TaskInboxRecord, now: Long): Boolean {
        val query = record.sessionRequest
        val pending = query != null && record.sessionResult?.request_id != query.id && now - query.issued_at in 0..39_999
        if (pending) return false
        val cached = display(record) ?: return true
        val ref = record.snapshot.thread_ref ?: record.snapshot.remote_ref
        return now - cached.at !in 0..59_999 || query?.baseline_turn != ref?.baseline_turn
    }
}
