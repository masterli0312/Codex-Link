package com.codex.quota.data.cloud

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.security.MessageDigest

@Serializable
data class CloudIdentity(val accountId: String, val workspaceId: String) {
    val key: String get() = digest("$accountId\u0000$workspaceId")
}
@Serializable data class CloudEnvironment(val id: String, val label: String = "", val is_pinned: Boolean = false,
    val published: Boolean = true, val repositories: List<CloudRepositoryRef> = emptyList(), val threadId: String = "")
@Serializable data class CloudTask(val id: String, val title: String, val status: String = "unknown", val updatedAt: Long = 0)
@Serializable data class CloudMessage(val role: String, val text: String, val id: String = "",
    val turnId: String = "", val phase: String = "", val position: Int = -1) {
    val copyable: Boolean get() = role == "assistant" && text.isNotBlank() && phase !in setOf("commentary", "analysis")
}
@Serializable data class CloudTurnInfo(val id: String,val status: String,val durationMs: Long? = null)
@Serializable data class CloudDetails(val task: CloudTask, val messages: List<CloudMessage>, val diff: String = "", val historyCursor: String = "",
    val activeTurnId: String = "", val latestTurnId: String = "", val activities: List<com.codex.quota.notifications.task.RemoteActivity> = emptyList(),
    val turns: List<CloudTurnInfo> = emptyList())
@Serializable data class CloudPage(val items: List<CloudTask>, val cursor: String = "")
@Serializable data class CloudSubmission(val fingerprint: String, val startedAt: Long, val taskId: String = "")

internal fun digest(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
    .joinToString("") { "%02x".format(it) }

/** Wire fields are backed by openai/codex cloud-tasks-client and backend-client, not local thread RPC. */
object CloudWire {
    private val json = Json { ignoreUnknownKeys = true }
    fun validId(id: String) = id.length in 1..256 && id.matches(Regex("[A-Za-z0-9_.:-]+")) && id !in setOf(".", "..")
    fun validBranch(branch: String) = branch.toByteArray().size in 1..255 && !branch.startsWith("-") &&
        !branch.endsWith("/") && !branch.endsWith(".") && !branch.contains("..") && !branch.contains("@{") &&
        !branch.contains("//") && branch.none { it.isWhitespace() || it.code < 32 || it in "~^:?*[\\" } &&
        branch.split('/').none { it.isEmpty() || it.startsWith('.') || it.endsWith(".lock") }
    fun validPrompt(prompt: String) = prompt.isNotBlank() && prompt.toByteArray().size <= 16_384 && '\u0000' !in prompt
    fun environments(body: String): List<CloudEnvironment> = (json.parseToJsonElement(body) as JsonArray).map {
        val o = it.jsonObject
        val id = o.string("id"); require(validId(id)) { "Invalid Cloud environment" }
        CloudEnvironment(id, o.string("label").take(512), o["is_pinned"]?.jsonPrimitive?.booleanOrNull == true)
    }.distinctBy { it.id }.sortedWith(compareByDescending<CloudEnvironment> { it.is_pinned }.thenBy { it.label.lowercase() })
    fun task(o: JsonObject): CloudTask {
        val id = o.string("id"); require(validId(id)) { "Invalid Cloud task" }
        val display = o["task_status_display"] as? JsonObject
        val turn = display?.get("latest_turn_status_display") as? JsonObject
        val raw = turn?.string("turn_status").orEmpty().ifBlank { display?.string("state").orEmpty() }
        val status = when(raw) {
            "completed", "ready", "applied" -> "completed"
            "failed", "cancelled", "error" -> "failed"
            "pending", "in_progress" -> "running"
            else -> "unknown"
        }
        val time = (o["updated_at"] as? JsonPrimitive)?.doubleOrNull ?: (o["created_at"] as? JsonPrimitive)?.doubleOrNull
        return CloudTask(id, o.string("title").take(512), status,
            time?.takeIf { it.isFinite() && it > 0 && it < Long.MAX_VALUE / 1000.0 }?.let { (it * 1000).toLong() } ?: 0)
    }
    fun page(body: String): CloudPage {
        val root = json.parseToJsonElement(body).jsonObject
        val rows = root["items"] as? JsonArray ?: error("Missing Cloud tasks")
        return CloudPage(rows.map { task(it.jsonObject) }.distinctBy { it.id }, root.string("cursor").take(4096))
    }
    fun details(body: String): CloudDetails {
        val root = json.parseToJsonElement(body).jsonObject
        val metadata = root["task"] as? JsonObject ?: error("Missing Cloud task metadata")
        val combined = if (metadata["task_status_display"] == null && root["task_status_display"] != null)
            JsonObject(metadata + ("task_status_display" to root.getValue("task_status_display"))) else metadata
        val messages = mutableListOf<CloudMessage>()
        val user = root["current_user_turn"] as? JsonObject
        user?.array("input_items")?.forEach { item ->
            val o = item as? JsonObject ?: return@forEach
            if (o.string("type") == "message" && o.string("role").ifBlank { "user" } == "user")
                text(o.array("content")).takeIf { it.isNotBlank() }?.let { messages += CloudMessage("user", it) }
        }
        var diff = ""
        listOf("current_diff_task_turn", "current_assistant_turn").forEach { name ->
            val turn = root[name] as? JsonObject ?: return@forEach
            turn.array("output_items").forEach itemLoop@ { item ->
                val o = item as? JsonObject ?: return@itemLoop
                if (o.string("type") == "message") text(o.array("content")).takeIf { it.isNotBlank() }
                    ?.let { messages += CloudMessage("assistant", it) }
                if (diff.isBlank()) diff = when (o.string("type")) {
                    "output_diff" -> o.string("diff")
                    "pr" -> (o["output_diff"] as? JsonObject)?.string("diff").orEmpty()
                    else -> ""
                }
            }
            val worklog = turn["worklog"] as? JsonObject
            worklog?.array("messages")?.forEach logLoop@ { entry ->
                val o = entry as? JsonObject ?: return@logLoop
                if ((o["author"] as? JsonObject)?.string("role") == "assistant") {
                    val value = text((o["content"] as? JsonObject)?.array("parts") ?: JsonArray(emptyList()))
                    if (value.isNotBlank()) messages += CloudMessage("assistant", value)
                }
            }
        }
        return CloudDetails(task(combined), messages, diff)
    }
    fun createBody(environment: String, branch: String, prompt: String): String {
        require(validId(environment) && validBranch(branch) && validPrompt(prompt))
        return buildJsonObject {
            putJsonObject("new_task") { put("environment_id", environment); put("branch", branch); put("run_environment_in_qa_mode", false) }
            putJsonArray("input_items") { add(buildJsonObject {
                put("type", "message"); put("role", "user")
                putJsonArray("content") { add(buildJsonObject { put("content_type", "text"); put("text", prompt) }) }
            }) }
        }.toString()
    }
    fun createdId(body: String): String {
        val root = json.parseToJsonElement(body).jsonObject
        val id = (root["task"] as? JsonObject)?.string("id").orEmpty().ifBlank { root.string("id") }
        require(validId(id)) { "Missing created Cloud task" }; return id
    }
    fun fingerprint(identity: CloudIdentity, environment: String, branch: String, prompt: String) =
        digest("${identity.key}\u0000$environment\u0000$branch\u0000$prompt")
    private fun JsonObject.string(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
    private fun JsonObject.array(key: String) = this[key] as? JsonArray ?: JsonArray(emptyList())
    private fun text(parts: JsonArray) = parts.mapNotNull {
        when (it) {
            is JsonPrimitive -> it.takeIf { part -> part.isString }?.content
            is JsonObject -> if (it.string("content_type") == "text") it.string("text") else null
            else -> null
        }
    }.filter { it.isNotBlank() }.joinToString("\n\n")
}
