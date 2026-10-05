package com.codex.quota.notifications.task

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@Serializable
data class RemoteThreadRef(val host_id: String, val thread_id: String, val baseline_turn: String)

@Serializable
data class RemoteCommand(
    val id: String, val action: String, val host_id: String, val thread_id: String,
    val conversation_id: String, val baseline_turn: String, val issued_at: Long,
    val text: String = "", val target_id: String = "", val approval_id: String = "", val allow: Boolean = false,
    val model: String = "", val effort: String = "", val project_id: String = "", val read_thread_id: String = "",
    val cursor: String = "", val archived: Boolean = false, val search: String = "",
    val expected_turn_id: String = "", val queue_id: String = "", val input_id: String = "",
    val answers: Map<String, List<String>> = emptyMap(), val watch: Boolean = false, val mode: String = "",
    val objective: String = "", val token_budget: Long? = null, val expected_goal_updated_at: Long = 0,
    val expected_goal_created_at: Long = 0, val expected_goal_hash: String = "", val permission: String = "default",
    val skill_ids: List<String> = emptyList(), val attachments: List<RemoteAttachment> = emptyList(), val stream_version: Int = 1, val image_id: String = "", val file_id: String = ""
) { override fun toString() = "RemoteCommand(action=$action,id=$id)" }

@Serializable
data class RemoteModelOption(val model: String, val name: String, val efforts: List<String> = emptyList(), val default_effort: String = "",
    val description: String = "", val is_default: Boolean = false)

@Serializable
data class RemoteThreadSummary(val thread_id: String, val title: String = "", val updated_at: Long = 0,
    val project_id: String = "", val project_name: String = "", val running: Boolean = false, val project_path: String = "")

@Serializable
data class RemoteQueuedEntry(val id: String, val text: String = "")

@Serializable
data class RemoteInputOption(val label: String, val description: String = "")
@Serializable
data class RemoteInputQuestion(val id: String, val header: String, val question: String,
    val is_other: Boolean = false, val is_secret: Boolean = false, val options: List<RemoteInputOption> = emptyList())
@Serializable
data class RemoteInputRequest(val id: String, val turn_id: String, val is_blocking: Boolean,
    val questions: List<RemoteInputQuestion>)

/** Validate the server's bounded question schema and require an explicit answer to every question. */
object RemoteInputRules {
    /** Snapshot attachments may still be loading; a complete authenticated question can be answered. */
    fun ready(event: RemoteEvent): Boolean = event.status == "input_required" && event.user_input?.let {
        valid(it) && it.turn_id == event.turn_id
    } == true
    fun valid(request: RemoteInputRequest): Boolean = request.id.matches(Regex("[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}")) &&
        request.turn_id.length in 1..128 && request.questions.size in 1..6 &&
        request.questions.map { it.id }.distinct().size == request.questions.size && request.questions.all { q ->
            q.id.isNotBlank() && q.id.toByteArray().size <= 128 && q.header.toByteArray().size <= 160 &&
                q.question.isNotBlank() && q.question.toByteArray().size <= 7200 && q.options.size <= 8 &&
                q.options.map { it.label }.distinct().size == q.options.size && q.options.all {
                    it.label.isNotBlank() && it.label.toByteArray().size <= 160 && it.description.toByteArray().size <= 1024
                }
        }
    fun accepts(request: RemoteInputRequest, answers: Map<String, List<String>>): Boolean = valid(request) &&
        answers.keys == request.questions.map { it.id }.toSet() && answers.values.all { values ->
            values.size == 1 && values[0].isNotBlank() && values[0].toByteArray().size <= 4096
        } && RemoteProtocol.json.encodeToString(answers).toByteArray().size <= 16384 && request.questions.all { q ->
            q.is_other || q.options.isEmpty() || q.options.any { it.label == answers[q.id]?.singleOrNull() }
        }
}

@Serializable
data class RemoteReplyPatch(val base_seq: Long, val prefix_length: Int, val suffix: String, val sha256: String)

@Serializable
data class RemoteFileDownload(val file_id: String, val name: String, val url: String, val mime: String, val size: Long, val sha256: String, val format: String = "")

@Serializable
data class RemoteImageDownload(val image_id: String, val url: String, val mime: String, val size: Long, val sha256: String)

@Serializable
data class RemoteEvent(
    val id: String, val request_id: String, val host_id: String, val thread_id: String,
    val conversation_id: String, val status: String, val seq: Long, val at: Long,
    val reply: String = "", val turn_id: String = "", val error: String = "",
    val approval_id: String = "", val approval_text: String = "", val partial: Boolean = false,
    val models: List<RemoteModelOption> = emptyList(), val model: String = "", val effort: String = "",
    val threads: List<RemoteThreadSummary> = emptyList(), val snapshot: TaskConversationSnapshot? = null,
    val messages: List<TaskConversationMessage> = emptyList(), val next_cursor: String = "", val history_truncated: Boolean = false,
    val managed_thread_id: String = "", val managed_action: String = "", val managed_name: String = "",
    val active_input: String = "", val queued_entries: List<RemoteQueuedEntry> = emptyList(), val parent_request_id: String = "", val host_name: String = "", val attachment_pending: Boolean = false,
    val user_input: RemoteInputRequest? = null, val activity_running: Boolean? = null, val activity_completed: Boolean = false,
    val approval_kind: String = "", val approval_item_id: String = "", val approval_can_allow: Boolean = true,
    val activities: List<RemoteActivity> = emptyList(), val modes: List<String> = emptyList(),
    val forked_from_thread_id: String = "", val goal: RemoteGoal? = null, val goal_waiting: Boolean = false,
    val context_usage: RemoteContextUsage? = null, val five_hour: RemoteLimitWindow? = null, val weekly: RemoteLimitWindow? = null,
    val quota_error: String = "", val skills: List<RemoteSkill> = emptyList(),
    val reply_patch: RemoteReplyPatch? = null, val reply_patch_pending: Boolean = false, val image: RemoteImageDownload? = null, val file: RemoteFileDownload? = null
)

@Serializable
data class RemoteConversationState(val command: RemoteCommand, val event: RemoteEvent? = null, val transport: String = "waiting")

/** Matches remote-core.cjs. Direction and identity are authenticated, not trusted from the relay. */
object RemoteProtocol {
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val uuid = Regex("[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}")
    fun validRef(ref: RemoteThreadRef, conversation: String): Boolean = uuid.matches(ref.host_id) &&
        uuid.matches(ref.thread_id) && ref.baseline_turn.isNotBlank() && ref.baseline_turn.length <= 128 && TaskInbox.hash(ref.thread_id) == conversation

    fun topic(endpoint: String, key: String, host: String, direction: String): String {
        require(uuid.matches(host) && direction in setOf("commands", "events"))
        require(TaskNotificationProtocol.normalizeEndpoint(endpoint) != null)
        val mac = Mac.getInstance("HmacSHA256")
        val bytes = Base64.getDecoder().decode(key).also { require(it.size == 32) }
        mac.init(SecretKeySpec(bytes, "HmacSHA256"))
        val suffix = mac.doFinal("CodexUsage:2.0:$direction:$host".toByteArray()).joinToString("") { "%02x".format(it) }
        // Same 240-bit HMAC suffix as the bridge, within ntfy's 64-character limit.
        return endpoint.toHttpUrl().newBuilder().encodedPath("/cu-${suffix.take(60)}").build().toString()
    }
    fun wire(direction: String, id: String, plain: String, key: String): JsonObject {
        require(uuid.matches(id) && direction in setOf("command", "event"))
        return buildJsonObject {
            put("schema_version", "2.0"); put("id", id)
            put("encrypted", TaskContentCipher.encrypt(plain, key, "CodexUsage:2.0:$direction:$id"))
        }
    }
    fun event(wire: JsonObject, key: String): RemoteEvent? = runCatching {
        val id = wire["id"]?.jsonPrimitive?.content ?: return null
        require(uuid.matches(id) && wire["schema_version"]?.jsonPrimitive?.content == "2.0")
        val plain = TaskContentCipher.decrypt(wire["encrypted"]!!.jsonPrimitive.content, key, "CodexUsage:2.0:event:$id") ?: return null
        json.decodeFromString<RemoteEvent>(plain).also {
            require(it.id == id && uuid.matches(it.request_id) && it.seq > 0 && it.at > 0 &&
                it.status in setOf("accepted", "running", "approval", "input_required", "completed", "interrupted", "failed", "unknown", "models", "threads", "snapshot", "history", "activities", "managed", "forked", "queued", "steered", "cancelled", "presence", "goal", "session_info", "skills", "image", "file", "thread_activity", "compacting", "compacted"))
            require(it.status != "thread_activity" || uuid.matches(it.host_id) && uuid.matches(it.thread_id) &&
                it.request_id == it.thread_id && it.conversation_id == TaskInbox.hash(it.thread_id) &&
                it.turn_id.length in 1..128 && it.activity_running != null && !(it.activity_running && it.activity_completed))
            it.file?.let { file -> require(it.status == "file" && ConversationFileRules.valid(listOf(ConversationFileRef(file.file_id, file.name))) &&
                file.size in 1..ConversationFileRules.MAX_BYTES.toLong() && file.sha256.matches(Regex("[a-f0-9]{64}")) &&
                file.mime == "application/octet-stream" && file.url.length <= 2048 && file.format in setOf("", "stream-v1")) }
            it.image?.let { image -> require(it.status == "image" && image.image_id.matches(Regex("[a-f0-9]{64}")) &&
                image.size in 1..ConversationImageRules.MAX_BYTES.toLong() && image.sha256.matches(Regex("[a-f0-9]{64}")) &&
                image.mime in setOf("image/png", "image/jpeg", "image/webp", "image/gif") && image.url.length <= 2048) }
            it.goal?.let { goal -> require(RemoteGoalRules.valid(goal, it.snapshot?.remote_ref?.thread_id ?: it.thread_id)) }
            require(!it.goal_waiting || it.goal?.status == "active" || it.attachment_pending)
            require(it.forked_from_thread_id.isBlank() || uuid.matches(it.forked_from_thread_id))
            require(it.status != "forked" || it.attachment_pending || it.snapshot?.remote_ref?.let { child ->
                it.forked_from_thread_id == it.thread_id && child.thread_id != it.thread_id &&
                    child.host_id == it.host_id && validRef(child, it.snapshot.conversation_id)
            } == true)
            require(RemoteActivityRules.valid(it.activities) && RemoteSessionRules.valid(it))
            it.user_input?.let { input -> require(RemoteInputRules.valid(input) && input.turn_id == it.turn_id) }
            require(it.status != "input_required" || it.attachment_pending || it.user_input != null)
            require(it.host_name.toByteArray().size <= 160)
            require(it.active_input.toByteArray().size <= 16_384 && it.queued_entries.size <= 4 &&
                it.queued_entries.all { q -> uuid.matches(q.id) && q.text.toByteArray().size <= 512 } &&
                (it.parent_request_id.isBlank() || uuid.matches(it.parent_request_id)))
            require(it.managed_thread_id.isBlank() || uuid.matches(it.managed_thread_id))
            require(it.managed_action.isBlank() || it.managed_action in setOf("rename", "archive", "unarchive"))
            require(it.managed_name.toByteArray().size <= 240 && !it.managed_name.contains('\u0000'))
            require(it.reply.toByteArray().size <= 65_536 && it.approval_text.toByteArray().size <= 7200)
            require(!it.reply_patch_pending || it.attachment_pending)
            it.reply_patch?.let { patch -> require(validPatch(it, patch)) }
            require(it.approval_kind in setOf("", "command", "fileChange") && it.approval_item_id.length <= 256)
            require(it.next_cursor.toByteArray().size <= 4096 && it.messages.size <= 60 && it.messages.all { m ->
                m.role in setOf("user", "assistant") && m.text.toByteArray().size <= 16_384 && m.id.length <= 256 && m.position in -1..1_000_000 && ConversationImageRules.valid(m) })
            require(it.models.size <= 100 && it.models.all { m -> m.model.length in 1..128 && m.name.toByteArray().size <= 160 &&
                m.efforts.size <= 16 && m.efforts.all { e -> e.length in 1..32 } && m.default_effort.length <= 32 && m.description.toByteArray().size <= 800 })
            require(it.modes.size <= 2 && it.modes.distinct().size == it.modes.size && it.modes.all { mode -> mode in setOf("plan", "default") })
            require(it.threads.size <= 100 && it.threads.all { t -> uuid.matches(t.thread_id) && t.title.toByteArray().size <= 240 &&
                t.project_name.toByteArray().size <= 160 && t.project_path.toByteArray().size <= 1024 && !t.project_path.contains('\u0000') &&
                (t.project_id.isBlank() || t.project_id.matches(Regex("[a-f0-9]{64}"))) })
            it.snapshot?.let { s -> require(s.conversation_id.matches(Regex("[a-f0-9]{64}")) && s.title.toByteArray().size <= 240 &&
                RemoteActivityRules.valid(s.activities) &&
                s.history_cursor.toByteArray().size <= 4096 && s.messages.size <= 60 && s.messages.all { m -> m.role in setOf("user", "assistant") && m.text.toByteArray().size <= 16_384 && m.id.length <= 256 && m.position in -1..1_000_000 && ConversationImageRules.valid(m) } &&
                (s.remote_ref == null || validRef(s.remote_ref, s.conversation_id) && s.remote_ref.host_id == it.host_id) &&
                (s.thread_ref == null || validRef(s.thread_ref, s.conversation_id) && s.thread_ref.host_id == it.host_id)) }
        }
    }.getOrNull()
    fun accepts(state: RemoteConversationState, event: RemoteEvent): Boolean = event.request_id == state.command.id &&
        event.host_id == state.command.host_id && event.thread_id == state.command.thread_id &&
        event.conversation_id == state.command.conversation_id && (event.seq > (state.event?.seq ?: 0L) ||
            state.event?.let { pending -> pending.attachment_pending && !event.attachment_pending &&
                event.seq == pending.seq && event.id == pending.id && event.status == pending.status &&
                event.turn_id == pending.turn_id && event.at == pending.at } == true) &&
        !(state.event?.status in setOf("completed", "interrupted", "failed", "cancelled", "steered") && event.status !in setOf("completed", "interrupted", "failed", "cancelled", "steered"))

    private fun validPatch(event: RemoteEvent, patch: RemoteReplyPatch) = event.status == "running" &&
        event.reply.isEmpty() && !event.attachment_pending && patch.base_seq in 1 until event.seq &&
        patch.prefix_length in 0..65_536 && patch.suffix.toByteArray().size <= 65_536 && patch.sha256.matches(Regex("[a-f0-9]{64}"))

    /** A missing base leaves the cache intact; callers request a full status, never repeat a send. */
    fun materialize(state: RemoteConversationState, event: RemoteEvent): RemoteEvent? {
        if (!accepts(state, event) || event.reply_patch_pending) return null
        val patch = event.reply_patch ?: return event
        if (!validPatch(event, patch)) return null
        val previous = state.event ?: return null
        if (previous.seq != patch.base_seq || previous.status != "running" || previous.attachment_pending ||
            previous.request_id != event.request_id || previous.host_id != event.host_id || previous.thread_id != event.thread_id ||
            previous.conversation_id != event.conversation_id ||
            previous.reply_patch != null || previous.turn_id != event.turn_id || event.turn_id.isBlank() ||
            patch.prefix_length > previous.reply.length) return null
        val prefix = patch.prefix_length
        if (prefix > 0 && prefix < previous.reply.length && previous.reply[prefix - 1].isHighSurrogate() && previous.reply[prefix].isLowSurrogate()) return null
        val reply = previous.reply.take(prefix) + patch.suffix
        if (reply.toByteArray().size > 65_536) return null
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(reply.toByteArray()).joinToString("") { "%02x".format(it) }
        if (hash != patch.sha256) return null
        return event.copy(reply = reply, reply_patch = null, activities = RemoteActivityRules.merge(previous.activities, event.activities))
    }

    fun command(ref: RemoteThreadRef, conversation: String, text: String, model: String = "", effort: String = "", mode: String = ""): RemoteCommand {
        require(validRef(ref, conversation) && text.isNotBlank() && text.toByteArray().size <= 16_384)
        require(model.length <= 128 && effort.length <= 32 && !model.contains('\n') && !effort.contains('\n'))
        require(mode in setOf("", "plan", "default"))
        return RemoteCommand(UUID.randomUUID().toString(), "send", ref.host_id, ref.thread_id, conversation, ref.baseline_turn, System.currentTimeMillis(), text, model = model, effort = effort, mode = mode)
    }
    fun approval(state: RemoteConversationState, approvalId: String, allow: Boolean): RemoteCommand {
        val event = requireNotNull(state.event)
        require(event.request_id == state.command.id && event.host_id == state.command.host_id && event.thread_id == state.command.thread_id &&
            event.conversation_id == state.command.conversation_id && event.status == "approval" && !event.attachment_pending &&
            uuid.matches(approvalId) && event.approval_id == approvalId && event.turn_id.isNotBlank() && (!allow || event.approval_can_allow))
        return control(state.command, "approve", approvalId, allow).copy(expected_turn_id = event.turn_id)
    }
    fun followUp(state: RemoteConversationState, action: String, text: String = "", queueId: String = ""): RemoteCommand {
        val event = requireNotNull(state.event)
        require(action in setOf("queue", "steer", "cancel_queue"))
        require(event.status in setOf("running", "approval", "input_required") && event.turn_id.isNotBlank())
        require(action == "cancel_queue" || text.isNotBlank() && text.toByteArray().size <= 16_384)
        require(action != "cancel_queue" || uuid.matches(queueId))
        require(action != "steer" || queueId.isBlank() || uuid.matches(queueId))
        return control(state.command, action).copy(text = text, expected_turn_id = event.turn_id, queue_id = queueId)
    }
    fun answerInput(state: RemoteConversationState, inputId: String, answers: Map<String, List<String>>): RemoteCommand {
        val event = requireNotNull(state.event)
        val input = requireNotNull(event.user_input)
        require(event.request_id == state.command.id && event.host_id == state.command.host_id && event.thread_id == state.command.thread_id &&
            event.conversation_id == state.command.conversation_id && RemoteInputRules.ready(event) &&
            input.id == inputId && input.turn_id == event.turn_id && RemoteInputRules.accepts(input, answers))
        return control(state.command, "answer_input").copy(input_id = input.id, expected_turn_id = input.turn_id, answers = answers)
    }
    fun control(c: RemoteCommand, action: String, approval: String = "", allow: Boolean = false) =
        c.copy(id = UUID.randomUUID().toString(), action = action, issued_at = System.currentTimeMillis(), text = "", target_id = c.id, approval_id = approval, allow = allow, attachments = emptyList(), skill_ids = emptyList(), watch = false)
}

/** A branch is a new native identity. It must never overwrite or masquerade as its source. */
object RemoteForkRules {
    fun confirmed(query: RemoteCommand, event: RemoteEvent): Boolean = query.action == "fork" &&
        event.status == "forked" && !event.partial && !event.attachment_pending && event.error.isBlank() &&
        event.request_id == query.id && event.host_id == query.host_id && event.thread_id == query.thread_id &&
        event.conversation_id == query.conversation_id && event.forked_from_thread_id == query.read_thread_id &&
        event.snapshot?.remote_ref?.let { child -> child.host_id == query.host_id && child.thread_id != query.read_thread_id &&
            RemoteProtocol.validRef(child, event.snapshot.conversation_id) } == true
}
