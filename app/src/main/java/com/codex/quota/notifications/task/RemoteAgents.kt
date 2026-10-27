package com.codex.quota.notifications.task

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** See docs/remote-agents-protocol.md. Shared with remote-core.cjs; unknown fields are ignored. */
@Serializable
data class RemoteAgent(val thread_id: String, val parent_thread_id: String = "", val depth: Int = 1,
    val nickname: String = "", val role: String = "", val model: String = "", val effort: String = "",
    val status: String = "unknown", val turn_id: String = "", val started_at: Long = 0, val updated_at: Long = 0,
    val last_reply: String = "")

@Serializable
data class RemoteBackgroundTask(val id: String, val session_id: String = "", val title: String = "",
    val model: String = "", val effort: String = "", val provider_kind: String = "other",
    val worktree_name: String = "", val branch: String = "", val status: String = "unknown",
    val started_at: Long = 0, val ended_at: Long = 0, val runs: Int = 1, val resumed: Boolean = false,
    val can_stop: Boolean = false, val last_reply: String = "", val last_step: String = "", val tokens: Long = 0)

@Serializable
data class RemoteOcxAccount(val label: String = "", val plan: String = "", val selected: Boolean = false,
    val short_left_percent: Int = -1, val weekly_left_percent: Int = -1, val short_reset_at: Long = 0, val weekly_reset_at: Long = 0)

@Serializable
data class RemoteOcxRequests(val model: String = "", val provider: String = "", val inbound: String = "other",
    val count: Int = 0, val failures: Int = 0, val tokens: Long = 0, val last_at: Long = 0, val last_error: String = "")

@Serializable
data class RemoteOcxOverview(val accounts: List<RemoteOcxAccount> = emptyList(),
    val requests: List<RemoteOcxRequests> = emptyList(), val window_minutes: Int = 0)

object RemoteAgentRules {
    const val AGENTS = "agents"; const val TASKS = "tasks"; const val OCX = "ocx_overview"
    val running = setOf("running", "starting")
    private val replyStatus = mapOf("agents" to "agents", "agent_read" to "snapshot", "agent_stop" to "agent_stop",
        "tasks" to "tasks", "task_stop" to "task_stop", "ocx_overview" to "ocx_overview", "presence" to "presence")
    fun replyStatus(action: String) = requireNotNull(replyStatus[action])
    /** Same host-level addressing as presence: the request names the computer, not a conversation. */
    fun matches(command: RemoteCommand, event: RemoteEvent) = event.request_id == command.id &&
        event.host_id == command.host_id && event.thread_id == command.thread_id &&
        event.conversation_id == command.conversation_id &&
        (event.status == replyStatus(command.action) || event.status == "failed" && event.error.isNotBlank())
    fun runningAgents(agents: List<RemoteAgent>) = agents.count { it.status == "running" }
    fun sortTasks(tasks: List<RemoteBackgroundTask>) = tasks.sortedWith(
        compareByDescending<RemoteBackgroundTask> { it.status in running }.thenByDescending { it.started_at })
    fun stoppable(agent: RemoteAgent) = agent.status == "running" && agent.turn_id.isNotBlank()
}

class HostCommandException(val code: String) : Exception(code)

/** Host-level requests over the paired encrypted relay. Never addresses any other service. */
class HostCommandClient(context: Context) {
    private val appContext = context.applicationContext
    private val http = OkHttpClient.Builder().connectionPool(RelayConnectionPool.pool)
        .addInterceptor(RelayRouting.interceptor(appContext)).connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()
    private val download = http.newBuilder().readTimeout(15, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS).build()

    /** Capabilities come from the presence reply; an older bridge reports none. */
    suspend fun capabilities(computer: ComputerConnection, force: Boolean = false): Set<String> {
        val cached = capabilityCache[computer.hostId]
        if (!force && cached != null && System.currentTimeMillis() - cached.first < 60_000) return cached.second
        val event = request(computer, "presence", 20_000)
        return event.capabilities.toSet().also { capabilityCache[computer.hostId] = System.currentTimeMillis() to it }
    }

    suspend fun request(computer: ComputerConnection, action: String, timeoutMs: Long = 25_000,
        configure: (RemoteCommand) -> RemoteCommand = { it }): RemoteEvent = withContext(Dispatchers.IO) {
        val host = computer.hostId
        if (!host.matches(uuid) || computer.key.isBlank()) throw HostCommandException("PAIRING_INCOMPLETE")
        val command = configure(RemoteCommand(UUID.randomUUID().toString(), action, host, host,
            TaskInbox.hash(host), action, System.currentTimeMillis()))
        require(command.host_id == host && command.thread_id == host && command.action == action)
        val event = withTimeout(timeoutMs) {
            val wire = RemoteProtocol.wire("command", command.id, RemoteProtocol.json.encodeToString(command), computer.key)
            http.newCall(Request.Builder().url(RemoteProtocol.topic(computer.endpoint, computer.key, host, "commands"))
                .post(wire.toString().toRequestBody("application/json".toMediaType())).build()).execute().use {
                if (!it.isSuccessful) throw HostCommandException("PUBLISH_FAILED")
            }
            events(computer, command.issued_at / 1000 - 1).mapNotNull { row -> complete(computer, row) }
                .first { RemoteAgentRules.matches(command, it) }
        }
        if (event.error.isNotBlank()) throw HostCommandException(event.error.take(64))
        event
    }

    private class Row(val envelope: JsonObject, val wire: JsonObject, val event: RemoteEvent)

    /** Large replies arrive as a preview plus an encrypted attachment holding the full event. */
    private fun complete(computer: ComputerConnection, row: Row): RemoteEvent? {
        if (!row.event.attachment_pending) return row.event
        val attachment = row.envelope["attachment"]?.jsonObject ?: return null
        if (attachment["name"]?.jsonPrimitive?.content != "codex-remote.bin") return null
        val url = RelayRouting.attachmentUrl(appContext, computer.endpoint, attachment["url"]?.jsonPrimitive?.content ?: return null) ?: return null
        return runCatching {
            download.newCall(Request.Builder().url(url).build()).execute().use { res ->
                if (!res.isSuccessful) return null
                val out = java.io.ByteArrayOutputStream()
                res.body?.byteStream()?.use { input ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val n = input.read(buffer); if (n < 0) break
                        if (out.size() + n > TaskContentCipher.MAX_BYTES + 28) return null
                        out.write(buffer, 0, n)
                    }
                } ?: return null
                RemoteProtocol.event(JsonObject(row.wire + ("encrypted" to JsonPrimitive(Base64.getEncoder().encodeToString(out.toByteArray())))), computer.key)
            }
        }.getOrNull()?.takeIf { it.id == row.event.id && !it.attachment_pending }
    }

    private fun events(computer: ComputerConnection, since: Long) = callbackFlow<Row> {
        val url = RemoteProtocol.topic(computer.endpoint, computer.key, computer.hostId, "events") + "/json?since=$since"
        val call = http.newCall(Request.Builder().url(url).build())
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: java.io.IOException) { close(e) }
            override fun onResponse(call: Call, response: Response) { response.use {
                if (!it.isSuccessful) { close(HostCommandException("STREAM_FAILED")); return }
                val source = it.body?.source() ?: run { close(); return }
                try {
                    while (!source.exhausted()) {
                        val line = source.readUtf8LineStrict(64_000)
                        val envelope = runCatching { Json.parseToJsonElement(line).jsonObject }.getOrNull() ?: continue
                        if (envelope["event"]?.jsonPrimitive?.content != "message") continue
                        val wire = runCatching { Json.parseToJsonElement(envelope["message"]!!.jsonPrimitive.content).jsonObject }.getOrNull() ?: continue
                        val event = RemoteProtocol.event(wire, computer.key) ?: continue
                        trySend(Row(envelope, wire, event))
                    }
                    close()
                } catch (e: java.io.IOException) { close(e) }
            } }
        })
        awaitClose { call.cancel() }
    }

    companion object {
        private val uuid = Regex("[a-fA-F0-9]{8}(-[a-fA-F0-9]{4}){3}-[a-fA-F0-9]{12}")
        private val capabilityCache = ConcurrentHashMap<String, Pair<Long, Set<String>>>()
        fun clearCapabilities() = capabilityCache.clear()
    }
}
