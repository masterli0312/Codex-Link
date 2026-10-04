package com.codex.quota.notifications.task

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

data class TaskAttachment(val url: String, val size: Long, val expiresAt: Long)
data class TaskCompletionEvent(val id: String, val status: String, val timeEpochMs: Long, val deduplicationId: String,
    val encryptedContent: String? = null, val attachment: TaskAttachment? = null)

/** Legacy metadata stays compatible. Conversation content is accepted only as authenticated ciphertext. */
object TaskNotificationProtocol {
    private val statuses = setOf("turn_complete", "task_complete", "review_complete", "question", "plan_ready", "api_error", "api_error_overloaded", "session_limit_reached", "permission_request", "test")

    fun normalizeEndpoint(value: String): String? {
        val url = value.trim().toHttpUrlOrNull() ?: return null
        if (!url.isHttps || url.username.isNotEmpty() || url.password.isNotEmpty() ||
            url.query != null || url.fragment != null || url.pathSegments.size != 1) return null
        val topic = url.pathSegments.single()
        if (!topic.matches(Regex("[A-Za-z0-9_-]{1,128}"))) return null
        return url.toString()
    }

    fun decode(line: String): TaskCompletionEvent? = runCatching {
        if (line.length > 32_768) return null
        val envelope = Json.parseToJsonElement(line).jsonObject
        if (envelope["event"]?.jsonPrimitive?.content != "message") return null
        val id = envelope["id"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() && it.length <= 128 } ?: return null
        val time = envelope["time"]?.jsonPrimitive?.longOrNull?.takeIf { it > 0L && it <= Long.MAX_VALUE / 1000L } ?: return null
        val payload = Json.parseToJsonElement(envelope["message"]?.jsonPrimitive?.content ?: return null).jsonObject
        val version = payload["schema_version"]?.jsonPrimitive?.content
        if (version !in setOf("1.0", "1.1") || payload["agent_source"]?.jsonPrimitive?.content != "codex") return null
        val status = payload["status"]?.jsonPrimitive?.content?.takeIf { it in statuses } ?: return null
        val session = payload["session_id"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() && it.length <= 128 }
        val turn = payload["turn_id"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() && it.length <= 128 }
        val encrypted = if (version == "1.1" && session != null && turn != null) payload["encrypted_content"]?.jsonPrimitive?.content
            ?.takeIf { it.length <= 4096 && it.matches(Regex("[A-Za-z0-9+/]+={0,2}")) } else null
        val rawAttachment = if (encrypted != null) envelope["attachment"] as? JsonObject else null
        val attachment = rawAttachment?.takeIf { it["name"]?.jsonPrimitive?.content == "codex-usage.bin" }?.let {
            val size = it["size"]?.jsonPrimitive?.longOrNull ?: return@let null
            val expires = it["expires"]?.jsonPrimitive?.longOrNull ?: return@let null
            val url = it["url"]?.jsonPrimitive?.content ?: return@let null
            if (size !in 28L..TaskContentCipher.MAX_BYTES + 28L || expires <= 0 || expires > Long.MAX_VALUE / 1000L) null
            else TaskAttachment(url, size, expires * 1000L)
        }
        TaskCompletionEvent(id, status, time * 1000L, if (session != null && turn != null) "$session:$turn" else id, encrypted, attachment)
    }.getOrNull()

    /** Only the paired service's bounded file route is allowed; never follow arbitrary attachment links. */
    fun attachmentUrl(endpoint: String, value: String): String? {
        val base = normalizeEndpoint(endpoint)?.toHttpUrlOrNull() ?: return null
        val url = value.toHttpUrlOrNull() ?: return null
        return url.takeIf { it.scheme == base.scheme && it.host == base.host && it.port == base.port &&
            it.username.isEmpty() && it.password.isEmpty() && it.query == null && it.fragment == null &&
            it.encodedPath.matches(Regex("/file/[A-Za-z0-9_.-]{1,128}")) }?.toString()
    }
}
