package com.codex.quota.notifications.task

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Only active while viewing a conversation. Completion notifications keep their existing connection. */
class RemoteConversationClient(private val context: Context) {
    companion object {
        private val streamRecoveries = java.util.concurrent.ConcurrentHashMap<String, Long>()
        private val sessionRequests = Mutex()
    }
    private val inbox = TaskInbox(context)
    private val settings = TaskNotificationStore(context)
    private val computers = ComputerConnectionStore(context)
    private val computerClient = ComputerConnectionClient(context)
    private val http = OkHttpClient.Builder().addInterceptor(RelayRouting.interceptor(context.applicationContext)).connectTimeout(10, TimeUnit.SECONDS).readTimeout(75, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()
    private val upload = http.newBuilder().callTimeout(20, TimeUnit.SECONDS).build()

    private fun pairing(record: TaskInboxRecord): Pair<String, String> {
        val s = settings.read()
        require(s.enabled && s.remoteEnabled && s.contentEnabled)
        val connection = requireNotNull(computers.find(record))
        require(connection.key.isNotBlank())
        return connection.endpoint to connection.key
    }

    private fun reference(record: TaskInboxRecord): RemoteThreadRef? = record.snapshot.thread_ref ?: record.snapshot.remote_ref ?: if (record.libraryAnchor)
        RemoteThreadRef(record.computerHostId, record.computerHostId, "library") else null

    /** Separate bounded image transfer. It never replaces a watch, send or pending library request. */
    suspend fun image(recordId: String, imageId: String): Pair<ComputerConnection, RemoteImageDownload> = withContext(Dispatchers.IO) {
        val record = requireNotNull(inbox.read(recordId)); pairing(record)
        val computer = requireNotNull(computers.find(record))
        val ref = requireNotNull(reference(record))
        require(!record.libraryAnchor && RemoteProtocol.validRef(ref, record.snapshot.conversation_id) && imageId.matches(Regex("[a-f0-9]{64}")))
        val command = RemoteCommand(java.util.UUID.randomUUID().toString(), "image", ref.host_id, ref.thread_id,
            record.snapshot.conversation_id, ref.baseline_turn, System.currentTimeMillis(), image_id = imageId)
        val event = withTimeout(40_000) {
            coroutineScope {
                val result = async {
                    lines(RemoteProtocol.topic(computer.endpoint, computer.key, computer.hostId, "events") +
                        "/json?since=" + (System.currentTimeMillis() / 1000 - 60)).mapNotNull { line ->
                        val envelope = runCatching { Json.parseToJsonElement(line).jsonObject }.getOrNull() ?: return@mapNotNull null
                        if (envelope["event"]?.jsonPrimitive?.content != "message") return@mapNotNull null
                        val wire = runCatching { Json.parseToJsonElement(envelope["message"]!!.jsonPrimitive.content).jsonObject }.getOrNull() ?: return@mapNotNull null
                        RemoteProtocol.event(wire, computer.key)?.takeIf { e -> e.status == "image" && e.request_id == command.id &&
                            e.host_id == ref.host_id && e.thread_id == ref.thread_id && e.conversation_id == record.snapshot.conversation_id }
                    }.first()
                }
                publishCommand(recordId, command)
                result.await()
            }
        }
        if (event.error in setOf("IMAGE_BUSY", "IMAGE_TRANSFER")) throw IOException(event.error)
        require(event.error.isBlank() && event.image?.image_id == imageId) { "IMAGE_UNAVAILABLE" }
        require(computers.find(record)?.key == computer.key)
        computer to requireNotNull(event.image)
    }

    private suspend fun <T> userAction(recordId: String, operation: suspend () -> T): T {
        val record = requireNotNull(inbox.read(recordId))
        val host = reference(record)?.host_id ?: record.remoteState?.command?.host_id.orEmpty()
        val identity = record.endpointHash + ":" + host + ":" + record.snapshot.conversation_id.ifBlank { recordId }
        return ConversationActionGate.run(identity, operation)
    }

    suspend fun anchor(computer: ComputerConnection): String = withContext(Dispatchers.IO) {
        require(computer.hostId.isNotBlank())
        val id = TaskInbox.hash(computer.endpoint + ":library:" + computer.hostId)
        if (inbox.read(id) == null) inbox.save(TaskInboxRecord(id, computer.createdAtSeconds * 1000, "remote-anchor:" + computer.hostId,
            TaskInbox.hash(computer.endpoint), TaskConversationSnapshot(conversation_id = TaskInbox.hash(computer.hostId)),
            hasFullSnapshot = true, libraryAnchor = true, computerHostId = computer.hostId))
        id
    }
    /** Enter the real thread immediately; its watch supplies content without blocking navigation. */
    suspend fun openThread(computer: ComputerConnection, thread: RemoteThreadSummary, archived: Boolean): String = withContext(Dispatchers.IO) {
        val pending = ConversationIndex.unreadThread(computer, thread, archived)
        val existing = inbox.read(pending.id)?.let { inbox.latestFor(it.id) }
            ?.takeIf { ConversationIndex.matchesThread(it, computer, thread.thread_id) }
            ?: ConversationIndex.latestForDisplay(inbox.list().filter {
                ConversationIndex.matchesThread(it, computer, thread.thread_id)
            }).firstOrNull()
        if (existing != null) return@withContext existing.id
        inbox.save(pending)
        pending.id
    }
    suspend fun send(recordId: String, text: String, model: String = "", effort: String = "", mode: String = "", files: List<SelectedRemoteFile> = emptyList(), skills: List<String> = emptyList()) = withContext(Dispatchers.IO) {
        userAction(recordId) {
        val record = requireNotNull(inbox.read(recordId))
        require(!record.libraryAnchor)
        require(!record.snapshot.running)
        require(record.remoteState?.event?.status in setOf(null, "completed", "interrupted", "failed") && record.remoteState?.transport != "waiting")
        computerClient.ensure(requireNotNull(computers.find(record)))
        val ref = requireNotNull(record.snapshot.remote_ref)
        require(files.size <= 3 && skills.size <= 4)
        val computer = requireNotNull(computers.find(record))
        val uploads = files.map { RemoteAttachmentUpload.upload(context, upload, computer, ref.thread_id, it) }
        require(uploads.sumOf { it.size } <= 16 * 1024 * 1024)
        val command = RemoteProtocol.command(ref, record.snapshot.conversation_id, text, model, effort, mode).copy(permission = record.selectedPermission, attachments = uploads, skill_ids = skills)
        val updated = record.copy(remoteState = RemoteConversationState(command))
        pairing(updated)
        inbox.beginRemote(recordId, command) // Persist atomically before transport; retries use the same ID.
        publishCommand(recordId, command)
        }
    }

    suspend fun create(anchorId: String, projectId: String, text: String, model: String, effort: String, mode: String = "", permission: String = "default"): String = withContext(Dispatchers.IO) {
        userAction(anchorId) {
        val anchor = requireNotNull(inbox.read(anchorId)); pairing(anchor)
        computerClient.ensure(requireNotNull(computers.find(anchor)))
        val ref = requireNotNull(reference(anchor))
        require(RemoteProtocol.validRef(ref, anchor.snapshot.conversation_id) && ConversationEnvironmentRules.validProject(projectId) &&
            text.isNotBlank() && text.toByteArray().size <= 16_384)
        require(mode in setOf("", "plan", "default") && permission in RemoteSessionRules.permissions)
        val command = RemoteCommand(java.util.UUID.randomUUID().toString(), "create", ref.host_id, ref.thread_id,
            anchor.snapshot.conversation_id, ref.baseline_turn, System.currentTimeMillis(), text = text, project_id = projectId,
            model = model, effort = effort, mode = mode, permission = permission)
        val id = TaskInbox.hash(anchor.endpointHash + ":create:" + command.id)
        val snapshot = TaskConversationSnapshot(conversation_id = TaskInbox.hash(command.id), title = text.lineSequence().first().take(80))
        inbox.save(TaskInboxRecord(id, command.issued_at, "remote-create:" + command.id, anchor.endpointHash, snapshot,
            hasFullSnapshot = true, remoteState = RemoteConversationState(command), selectedModel = model, selectedEffort = effort, selectedMode = mode, selectedPermission = permission))
        // A transport timeout has an ambiguous result. Keep the same persisted request reachable.
        try { publishCommand(id, command) } catch (_: IOException) { }
        id
        }
    }
    suspend fun followUp(recordId: String, action: String, text: String = "", queueId: String = "") = withContext(Dispatchers.IO) {
        val record = requireNotNull(inbox.read(recordId)); pairing(record)
        computerClient.ensure(requireNotNull(computers.find(record)))
        val source = requireNotNull(RemoteFollowUpRules.source(record))
        require(source.command.action != "read" || action == "steer")
        val command = RemoteProtocol.followUp(source, action, text, queueId)
        inbox.requestFollowUp(recordId, command)
        publishCommand(recordId, command)
    }
    suspend fun steerQueued(recordId: String, queueId: String) = withContext(Dispatchers.IO) {
        userAction(recordId) {
            val record = requireNotNull(inbox.read(recordId))
            val entry = requireNotNull(record.followUps.singleOrNull { it.command.id == queueId && FollowUpState.queued(it) })
            followUp(recordId, "steer", entry.command.text, queueId)
        }
    }
    private suspend fun recoverFollowUps(recordId: String) {
        inbox.read(recordId)?.followUps?.filter { FollowUpState.pending(it) }?.forEach {
            runCatching { publishCommand(recordId, RemoteProtocol.control(it.command, "status")) }
        }
    }
    suspend fun session(recordId: String, action: String = "session_info", force: Boolean = false) = withContext(Dispatchers.IO) {
        require(action in setOf("session_info", "skills"))
        val command = sessionRequests.withLock {
        val record = requireNotNull(inbox.read(recordId)); pairing(record)
        if (action == "session_info" && !force && !RemoteSessionCache.shouldRefresh(record, System.currentTimeMillis())) return@withLock null
        val ref = requireNotNull(reference(record))
        require(RemoteProtocol.validRef(ref, record.snapshot.conversation_id))
        val command = RemoteCommand(java.util.UUID.randomUUID().toString(), action, ref.host_id, ref.thread_id,
            record.snapshot.conversation_id, ref.baseline_turn, System.currentTimeMillis())
        inbox.requestSession(recordId, command); command
        } ?: return@withContext
        publishCommand(recordId, command)
    }
    suspend fun models(recordId: String) = withContext(Dispatchers.IO) {
        val record = requireNotNull(inbox.read(recordId))
        pairing(record)
        val ref = requireNotNull(reference(record))
        require(RemoteProtocol.validRef(ref, record.snapshot.conversation_id))
        val command = RemoteCommand(java.util.UUID.randomUUID().toString(), "models", ref.host_id, ref.thread_id,
            record.snapshot.conversation_id, ref.baseline_turn, System.currentTimeMillis())
        inbox.requestModels(recordId, command)
        publishCommand(recordId, command)
    }
    suspend fun goal(recordId: String, action: String, objective: String = "", budget: Long? = null) = withContext(Dispatchers.IO) {
        userAction(recordId) {
        val record = requireNotNull(inbox.read(recordId)); pairing(record)
        val ref = requireNotNull(reference(record))
        val root = record.remoteState?.takeIf { it.command.action in RemoteGoalRules.rootActions }
        val confirmed = listOfNotNull(root?.event, record.goalResult).filter { !it.partial && !it.attachment_pending && it.error.isBlank() }
            .maxByOrNull { it.seq }
        require(action == "goal_read" || confirmed != null)
        if (action in RemoteGoalRules.rootActions) {
            require(!record.snapshot.running && (!ConversationIndex.hasPendingTurn(record) || root?.event?.status == "unknown"))
        }
        computerClient.ensure(requireNotNull(computers.find(record)))
        val command = RemoteGoalRules.command(ref, record.snapshot.conversation_id, action, confirmed?.goal, root?.command?.id.orEmpty(), objective, budget).copy(permission = record.selectedPermission)
        inbox.requestGoal(recordId, command)
        publishCommand(recordId, command)
        }
    }
    private suspend fun recoverGoal(recordId: String) {
        val record = inbox.read(recordId) ?: return
        record.goalRequest?.takeIf { it.action != "goal_read" && it.action !in RemoteGoalRules.rootActions &&
            (record.goalResult == null || record.goalResult.attachment_pending || record.goalResult.status == "unknown") }?.let {
            runCatching { publishCommand(recordId, RemoteProtocol.control(it, "status")) }
        }
    }

    suspend fun library(recordId: String, action: String, readThread: String = "", project: String = "", text: String = "", model: String = "", effort: String = "", cursor: String = "", archived: Boolean = false, search: String = "", watch: Boolean = false) = withContext(Dispatchers.IO) {
        require(action in setOf("threads", "read", "create", "history", "activities", "rename", "archive", "unarchive", "fork") && cursor.toByteArray().size <= 4096 && search.toByteArray().size <= 240)
        val record = requireNotNull(inbox.read(recordId)); pairing(record)
        val ref = requireNotNull(reference(record))
        require(RemoteProtocol.validRef(ref, record.snapshot.conversation_id))
        if (action == "fork") {
            require(record.snapshot.remote_ref != null && readThread == ref.thread_id && !record.snapshot.running && !ConversationIndex.hasPendingTurn(record))
            computerClient.ensure(requireNotNull(computers.find(record)))
        }
        val command = RemoteCommand(java.util.UUID.randomUUID().toString(), action, ref.host_id, ref.thread_id,
            record.snapshot.conversation_id, ref.baseline_turn, System.currentTimeMillis(), text = text, project_id = project, read_thread_id = readThread, model = model, effort = effort, cursor = cursor, archived = archived, search = search, watch = watch,
            expected_turn_id = if (action == "fork") ref.baseline_turn else "")
        inbox.requestLibrary(recordId, command)
        publishCommand(recordId, command)
        command.id
    }
    private suspend fun ensureConversationWatch(recordId: String, force: Boolean = false) {
        val record = inbox.read(recordId) ?: return
        if (record.libraryAnchor || record.archived) return
        val ref = reference(record) ?: return
        val query = record.libraryRequest
        val result = record.libraryResult?.takeIf { it.request_id == query?.id }
        if (query?.action == "fork" && (result == null || result.attachment_pending || result.status == "unknown")) return
        val age = System.currentTimeMillis() - (query?.issued_at ?: 0)
        // Manual history/management operations use the same authenticated result slot.
        // Do not replace their pending request or lose their acknowledgement.
        if (query != null && (result == null || result.attachment_pending) && age < 45_000 &&
            !(force && query.action == "read" && query.watch)) return
        if (!force && query?.action == "read" && query.watch && age < 480_000 && result?.error?.isBlank() == true && !result.partial && !result.attachment_pending) return
        library(recordId, "read", readThread = ref.thread_id, watch = true)
    }
    suspend fun checkFork(recordId: String) = withContext(Dispatchers.IO) {
        inbox.read(recordId)?.libraryRequest?.takeIf { it.action == "fork" }?.let {
            publishCommand(recordId, RemoteProtocol.control(it, "status"))
        }
    }
    suspend fun stopCreating(recordId: String) = withContext(Dispatchers.IO) {
        inbox.read(recordId)?.libraryRequest?.takeIf { it.action == "create" }?.let { publishCommand(recordId, RemoteProtocol.control(it, "stop")) }
    }

    suspend fun controlLibrary(recordId: String, action: String, approval: String = "", allow: Boolean = false) = withContext(Dispatchers.IO) {
        inbox.read(recordId)?.libraryRequest?.takeIf { it.action == "create" }?.let {
            publishCommand(recordId, RemoteProtocol.control(it, action, approval, allow))
        }
    }

    suspend fun control(recordId: String, action: String, approval: String = "", allow: Boolean = false) = withContext(Dispatchers.IO) {
        val state = inbox.read(recordId)?.remoteState ?: return@withContext
        publishCommand(recordId, RemoteProtocol.control(state.command, action, approval, allow))
    }
    suspend fun answerInput(recordId: String, inputId: String, answers: Map<String, List<String>>, library: Boolean = false) = withContext(Dispatchers.IO) {
        val record = requireNotNull(inbox.read(recordId))
        val state = if (library) RemoteConversationState(requireNotNull(record.libraryRequest), record.libraryResult)
            else requireNotNull(record.remoteState)
        // Re-read the persisted request immediately before transmission; never answer a stale UI card.
        publishCommand(recordId, RemoteProtocol.answerInput(state, inputId, answers))
    }
    suspend fun approve(recordId: String, approvalId: String, allow: Boolean, library: Boolean = false) = withContext(Dispatchers.IO) {
        val record = requireNotNull(inbox.read(recordId))
        val state = if (library) RemoteConversationState(requireNotNull(record.libraryRequest), record.libraryResult)
            else requireNotNull(record.remoteState)
        publishCommand(recordId, RemoteProtocol.approval(state, approvalId, allow))
    }
    suspend fun retry(recordId: String) = withContext(Dispatchers.IO) {
        val c = inbox.read(recordId)?.remoteState?.command ?: return@withContext
        if (System.currentTimeMillis() - c.issued_at <= 120_000) publishCommand(recordId, c)
        else control(recordId, "status") // Expired sends are never renewed automatically.
    }

    /** One background subscription per paired computer; no user messages or automatic approval. */
    suspend fun listenComputer(computer: ComputerConnection, onAttention: (TaskInboxRecord, RemoteEvent) -> Unit) = withContext(Dispatchers.IO) {
        val topic = RemoteProtocol.topic(computer.endpoint, computer.key, computer.hostId, "events")
        var retryDelay = 2000L
        val requestRecords = mutableMapOf<String, String>()
        var lastPresenceObservation = 0L
        var indexedAt = 0L
        fun indexRecords(): List<TaskInboxRecord> {
            val records = inbox.list().filter { computer.matches(it) }
            requestRecords.clear()
            records.forEach { record -> record.remoteState?.command?.id?.let { requestRecords[it] = record.id } }
            indexedAt = System.currentTimeMillis()
            return records
        }
        while (currentCoroutineContext().isActive) {
            if (computers.endpoint(TaskInbox.hash(computer.endpoint))?.key != computer.key) return@withContext
            var failure: Exception? = null
            try {
                lines(topic + "/json?since=" + (System.currentTimeMillis() / 1000 - 60).coerceAtLeast(0)).collect { line ->
                    val envelope = runCatching { Json.parseToJsonElement(line).jsonObject }.getOrNull() ?: return@collect
                    if (envelope["event"]?.jsonPrimitive?.content == "open") {
                        retryDelay = 2000L
                        indexRecords().filter { it.remoteState != null &&
                            (it.remoteState.event?.status !in setOf("completed", "interrupted", "failed") || it.remoteState.event?.attachment_pending == true)
                        }.take(4).forEach { record -> runCatching { control(record.id, "status") } }
                    }
                    if (envelope["event"]?.jsonPrimitive?.content != "message") return@collect
                    val wire = runCatching { Json.parseToJsonElement(envelope["message"]!!.jsonPrimitive.content).jsonObject }.getOrNull() ?: return@collect
                    var event = RemoteProtocol.event(wire, computer.key) ?: return@collect
                    if (event.host_id != computer.hostId) return@collect
                    // Every fresh authenticated host response proves presence, including
                    // catalog/watch replies that are handled by the foreground reader.
                    val observedAt = System.currentTimeMillis()
                    if (observedAt - lastPresenceObservation >= 15_000 && observedAt - event.at in 0..30_000) {
                        computers.observeHost(computer, event)
                        lastPresenceObservation = observedAt
                    }
                    // The foreground reader owns catalog/watch replies. Do not repeat
                    // encrypted pairing work for those frames in the task-only reader.
                    if (event.status !in setOf("accepted", "running", "approval", "input_required", "completed", "interrupted", "failed", "unknown")) return@collect
                    if (computers.endpoint(TaskInbox.hash(computer.endpoint))?.key != computer.key) return@collect
                    if (event.request_id !in requestRecords && System.currentTimeMillis() - indexedAt >= 1000) indexRecords()
                    val record = requestRecords[event.request_id]?.let(inbox::read)?.takeIf { computer.matches(it) && it.remoteState?.let { state ->
                        state.command.id == event.request_id && state.command.thread_id == event.thread_id &&
                            state.command.conversation_id == event.conversation_id
                    } == true } ?: return@collect
                    if (RemoteProtocol.accepts(record.remoteState!!, event)) {
                        if (event.attachment_pending) downloadEvent(computer.endpoint, envelope, wire, computer.key)?.let { event = it }
                        if (computers.endpoint(record.endpointHash)?.key != computer.key) return@collect
                        ingestRoot(record.id, event)
                    }
                    val latest = inbox.read(record.id) ?: return@collect
                    val current = latest.remoteState ?: return@collect
                    if (RemoteAttention.matches(current, event)) onAttention(latest, event)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (e: Exception) { failure = e /* No private URLs or server messages in diagnostics. */ }
            RelayConnection.retry(context, failure, retryDelay); retryDelay = (retryDelay * 2).coerceAtMost(30_000L)
        }
    }

    private suspend fun publishCommand(recordId: String, command: RemoteCommand) {
        val record = inbox.read(recordId) ?: return
        val (endpoint, key) = pairing(record)
        val wire = RemoteProtocol.wire("command", command.id, RemoteProtocol.json.encodeToString(command), key)
        val large = wire.toString().toByteArray().size > 3500
        val body: ByteArray
        val builder = Request.Builder().url(RemoteProtocol.topic(endpoint, key, command.host_id, "commands"))
        if (large) {
            val preview = RemoteProtocol.wire("command", command.id, buildJsonObject {
                put("id", command.id); put("attachment", true)
            }.toString(), key)
            builder.header("Filename", "codex-command.bin").header("Message", preview.toString())
            body = Base64.getDecoder().decode(wire["encrypted"]!!.jsonPrimitive.content)
        } else body = wire.toString().toByteArray()
        try {
            upload.newCall(builder.post(body.toRequestBody("application/octet-stream".toMediaType())).build()).execute().use {
                if (!it.isSuccessful) throw IOException("REMOTE_PUBLISH")
            }
        } catch (e: IOException) {
            if (command.action in setOf("queue", "steer", "cancel_queue")) inbox.updateFollowUp(recordId, command.id) { it.copy(transport = "unconfirmed") }
            if (command.action in setOf("send", "create")) inbox.updateRemote(recordId) { s -> if (s.command.id == command.id) s.copy(transport = "unconfirmed") else s }
            throw e
        }
    }

    // Includes synchronous OkHttp recovery publishes and encrypted cache access. Callers may
    // launch from a Compose effect, so the entire subscription must stay off the UI thread.
    suspend fun listen(recordId: String) = withContext(Dispatchers.IO) {
        launch { inbox.read(recordId)?.let { computers.find(it) }?.let { runCatching { computerClient.ensure(it) } } }
        // Recovery is automatic; the UI never requires users to poll transport state.
        val recovery = launch {
            while (isActive) {
                delay(15_000)
                val record = withContext(Dispatchers.IO) { inbox.read(recordId) } ?: break
                runCatching { ensureConversationWatch(recordId) }
                recoverGoal(recordId)
                val state = record.remoteState
                record.followUps.filter { FollowUpState.pending(it) &&
                    (it.event == null || it.event.status == "unknown" || state?.event?.status in setOf("completed", "interrupted", "failed") ||
                        System.currentTimeMillis() - it.event.at >= 120_000) }.forEach {
                    runCatching { publishCommand(recordId, RemoteProtocol.control(it.command, "status")) }
                }
                if (state != null && (state.event?.status !in setOf("completed", "interrupted", "failed") || state.event?.attachment_pending == true) &&
                    System.currentTimeMillis() - (state.event?.at ?: state.command.issued_at) >=
                        (if (state.event?.status in setOf("running", "approval", "input_required")) 60_000 else 30_000)) {
                    runCatching { control(recordId, "status") }
                }
                record.libraryRequest?.takeIf { it.action == "create" && record.libraryResult?.status !in setOf("completed", "interrupted", "failed") }?.let {
                    runCatching { publishCommand(recordId, RemoteProtocol.control(it, "status")) }
                }
            }
        }
        var startup: Job? = null
        try {
        var retryDelay = 2000L
        while (currentCoroutineContext().isActive) {
            val record = withContext(Dispatchers.IO) { inbox.read(recordId) } ?: break
            val ref = reference(record) ?: record.remoteState?.command?.takeIf { it.action == "create" }?.let {
                RemoteThreadRef(it.host_id, it.thread_id, it.baseline_turn)
            } ?: break
            val (endpoint, key) = try { pairing(record) } catch (_: Exception) { break }
            var failure: Exception? = null
            try {
                val url = RemoteProtocol.topic(endpoint, key, ref.host_id, "events") + "/json?since=" +
                    ((record.remoteState?.event?.at ?: System.currentTimeMillis()) / 1000 - 1).coerceAtLeast(0)
                lines(url).collect { line ->
                    val started = android.os.SystemClock.elapsedRealtime()
                    val envelope = runCatching { Json.parseToJsonElement(line).jsonObject }.getOrNull() ?: return@collect
                    if (envelope["event"]?.jsonPrimitive?.content == "open") {
                        retryDelay = 2000L
                        // Keep receiving the first native snapshot while request POSTs
                        // and old-task recovery wait for their own HTTP acknowledgements.
                        startup?.cancel()
                        startup = launch {
                            runCatching { ensureConversationWatch(recordId, force = true) }
                            launch { recoverFollowUps(recordId) }
                            launch { recoverGoal(recordId) }
                            launch {
                                val opened = inbox.read(recordId) ?: return@launch
                                if (opened.remoteState != null && ConversationIndex.hasPendingTurn(opened))
                                    runCatching { control(recordId, "status") }
                                opened.libraryRequest?.takeIf { it.action == "create" }?.let {
                                    runCatching { publishCommand(recordId, RemoteProtocol.control(it, "status")) }
                                }
                            }
                        }
                    }
                    if (envelope["event"]?.jsonPrimitive?.content != "message") return@collect
                    val wire = runCatching { Json.parseToJsonElement(envelope["message"]!!.jsonPrimitive.content).jsonObject }.getOrNull() ?: return@collect
                    var event = RemoteProtocol.event(wire, key) ?: return@collect
                    ConversationSyncTrace.record("receive", event.seq, context = context)
                    computers.find(record)?.takeIf { it.key == key && it.hostId == event.host_id }?.let { computers.observeHost(it, event) }
                    ConversationSyncTrace.record("host-observed", event.seq, android.os.SystemClock.elapsedRealtime() - started, context)
                    // Read routing state once per frame. Mutations below still re-read
                    // and validate the current request under the inbox lock.
                    val currentRecord = inbox.read(recordId) ?: return@collect
                    val goalQuery = currentRecord.goalRequest
                    if (goalQuery != null && RemoteGoalRules.accepts(goalQuery, currentRecord.goalResult, event)) {
                        if (event.partial) downloadEvent(endpoint, envelope, wire, key)?.let { event = it }
                        inbox.updateGoal(recordId, event)
                        if (goalQuery.action !in RemoteGoalRules.rootActions) return@collect
                    }
                    if (event.status in setOf("session_info", "skills")) {
                        val query = if (event.status == "skills") currentRecord.skillsRequest else currentRecord.sessionRequest
                        val previous = if (event.status == "skills") currentRecord.skillsResult else currentRecord.sessionResult
                        if (!RemoteSessionRules.accepts(query, previous, event)) return@collect
                        if (event.partial) downloadEvent(endpoint, envelope, wire, key)?.let { event = it }
                        inbox.updateSession(recordId, event); return@collect
                    }
                    if (event.status == "models") {
                        val query = currentRecord.modelRequest ?: return@collect
                        if (event.request_id != query.id || event.host_id != query.host_id || event.thread_id != query.thread_id ||
                            event.conversation_id != query.conversation_id) return@collect
                        if (event.partial) downloadEvent(endpoint, envelope, wire, key)?.let { event = it }
                        withContext(Dispatchers.IO) { inbox.updateModels(recordId, event) }
                        return@collect
                    }
                    val query = currentRecord.libraryRequest
                    if (query != null && event.request_id == query.id && event.host_id == query.host_id &&
                        event.thread_id == query.thread_id && event.conversation_id == query.conversation_id) {
                        if (event.partial) downloadEvent(endpoint, envelope, wire, key)?.let { event = it }
                        val libraryState = RemoteConversationState(query, currentRecord.libraryResult)
                        if ((event.reply_patch != null || event.reply_patch_pending) && RemoteProtocol.materialize(libraryState, event) == null) {
                            recoverStream(recordId, query); return@collect
                        }
                        ConversationSyncTrace.record("library-before", event.seq, android.os.SystemClock.elapsedRealtime() - started, context)
                        inbox.updateLibrary(recordId, event)
                        ConversationSyncTrace.record("library-applied", event.seq, android.os.SystemClock.elapsedRealtime() - started, context)
                        return@collect
                    }
                    val child = currentRecord.followUps.firstOrNull { RemoteProtocol.accepts(it, event) }
                    if (child != null) {
                        if (event.partial) downloadEvent(endpoint, envelope, wire, key)?.let { event = it }
                        var missingBase = false
                        inbox.updateFollowUp(recordId, child.command.id) { state ->
                            val full = RemoteProtocol.materialize(state, event)
                            if (full != null) state.copy(event = full, transport = "received") else {
                                missingBase = RemoteProtocol.accepts(state, event) && (event.reply_patch != null || event.reply_patch_pending); state
                            }
                        }
                        if (missingBase) recoverStream(recordId, child.command)
                        return@collect
                    }
                    val current = currentRecord.remoteState ?: return@collect
                    if (!RemoteProtocol.accepts(current, event)) return@collect
                    if (event.partial) downloadEvent(endpoint, envelope, wire, key)?.let { event = it }
                    ingestRoot(recordId, event)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (e: Exception) { failure = e /* No secret URLs or conversation content in logs. */ }
            RelayConnection.retry(context, failure, retryDelay); retryDelay = (retryDelay * 2).coerceAtMost(30_000L)
        }
        } finally { startup?.cancel(); recovery.cancel() }
    }
    private suspend fun ingestRoot(recordId: String, event: RemoteEvent) {
        var missingBase: RemoteCommand? = null
        inbox.updateRemote(recordId) { state ->
            val full = RemoteProtocol.materialize(state, event)
            if (full != null) state.copy(event = full, transport = "received") else {
                if (RemoteProtocol.accepts(state, event) && (event.reply_patch != null || event.reply_patch_pending)) missingBase = state.command
                state
            }
        }
        missingBase?.let { recoverStream(recordId, it) }
    }

    private suspend fun recoverStream(recordId: String, command: RemoteCommand) {
        val now = System.currentTimeMillis()
        var recover = false
        streamRecoveries.compute(recordId + ":" + command.id) { _, previous ->
            if (previous == null || now - previous >= 5000) { recover = true; now } else previous
        }
        if (streamRecoveries.size > 128) streamRecoveries.entries.removeIf { now - it.value > 60_000 }
        if (recover) runCatching { publishCommand(recordId, RemoteProtocol.control(command, "status")) }
    }

    private fun lines(url: String) = callbackFlow<String> {
        val call = http.newCall(Request.Builder().url(url).build())
        val network = RelayConnection.watch(context, call) { close(it) }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { close(e) }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!it.isSuccessful) { close(RelayHttpFailure(it.code, it.header("Retry-After"))); return }
                    val source = it.body?.source() ?: run { close(); return }
                    try {
                        while (!source.exhausted()) if (!trySend(source.readUtf8LineStrict(40_000)).isSuccess) {
                            close(IOException("REMOTE_QUEUE")); return
                        }
                        close()
                    } catch (e: IOException) { close(e) }
                }
            }
        })
        awaitClose { network.close(); call.cancel() }
    }
    private suspend fun downloadEvent(endpoint: String, envelope: JsonObject, wire: JsonObject, key: String): RemoteEvent? = withContext(Dispatchers.IO) {
        runCatching {
            val attachment = envelope["attachment"]?.jsonObject ?: return@withContext null
            if (attachment["name"]?.jsonPrimitive?.content != "codex-remote.bin") return@withContext null
            val url = RelayRouting.attachmentUrl(context, endpoint, attachment["url"]!!.jsonPrimitive.content) ?: return@withContext null
            upload.newCall(Request.Builder().url(url).build()).execute().use { res ->
                if (!res.isSuccessful) return@withContext null
                val out = java.io.ByteArrayOutputStream()
                val source = res.body?.byteStream() ?: return@withContext null
                source.use { input -> val bytes = ByteArray(8192); while (true) {
                    val n = input.read(bytes); if (n < 0) break
                    if (out.size() + n > TaskContentCipher.MAX_BYTES + 28) return@withContext null
                    out.write(bytes, 0, n)
                } }
                RemoteProtocol.event(JsonObject(wire + ("encrypted" to JsonPrimitive(Base64.getEncoder().encodeToString(out.toByteArray())))), key)
            }
        }.getOrNull()
    }
}
