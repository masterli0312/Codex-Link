package com.codex.quota.notifications.task

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class TaskInboxRecord(
    val id: String,
    val receivedAt: Long,
    val identity: String,
    val endpointHash: String,
    val snapshot: TaskConversationSnapshot,
    val attachmentUrl: String? = null,
    val attachmentExpiresAt: Long = 0,
    val hasFullSnapshot: Boolean = false,
    val remoteState: RemoteConversationState? = null,
    val modelRequest: RemoteCommand? = null,
    val modelCatalog: RemoteEvent? = null,
    val selectedModel: String = "",
    val selectedEffort: String = "",
    val libraryRequest: RemoteCommand? = null,
    val libraryResult: RemoteEvent? = null,
    val threadCatalog: List<RemoteThreadSummary> = emptyList(),
    val threadCatalogAt: Long = 0,
    val contentValidatedAt: Long = 0,
    val threadCursor: String = "",
    val historyMessages: List<TaskConversationMessage> = emptyList(),
    val historyCursor: String? = null,
    val historyLimited: Boolean = false,
    val archived: Boolean = false,
    val threadCatalogArchived: Boolean = false,
    val threadCatalogSearch: String = "",
    val pinned: Boolean = false,
    val followUps: List<RemoteConversationState> = emptyList(),
    val libraryAnchor: Boolean = false, val computerHostId: String = "",
    val activities: List<RemoteActivity> = emptyList(), val activityCursor: String = "", val activityLoaded: Boolean = false,
    val selectedMode: String = "", val goalRequest: RemoteCommand? = null, val goalResult: RemoteEvent? = null,
    val selectedPermission: String = "default", val sessionRequest: RemoteCommand? = null, val sessionResult: RemoteEvent? = null,
    val skillsRequest: RemoteCommand? = null, val skillsResult: RemoteEvent? = null,
    val lastSessionResult: RemoteEvent? = null
)

/** Bounded, encrypted, backup-excluded cache. One record per event and pairing endpoint. */
class TaskInbox(context: Context) {
    private val appContext = context.applicationContext
    private val directory = File(context.noBackupFilesDir, "task-inbox")
    private val keys = TaskContentKeys(context)
    private val preferences = ConversationPreferenceStore(context)
    private val json = Json { ignoreUnknownKeys = true }
    fun recordId(endpoint: String, identity: String): String = hash("$endpoint:$identity")
    fun save(record: TaskInboxRecord) = synchronized(lock) {
        val started = android.os.SystemClock.elapsedRealtime()
        require(record.id.matches(Regex("[a-f0-9]{64}")))
        directory.mkdirs()
        val file = AtomicFile(File(directory, record.id + ".json"))
        val stream = file.startWrite()
        try {
            stream.write(keys.encryptLocal(json.encodeToString(record)).toByteArray(Charsets.UTF_8))
            ConversationSyncTrace.record("cache-encrypted", record.libraryResult?.seq ?: 0, android.os.SystemClock.elapsedRealtime() - started, appContext)
            file.finishWrite(stream)
            cache.remember(file.baseFile.absolutePath, file.baseFile.lastModified(), file.baseFile.length(), record)
            lookup.remember(file.baseFile.absolutePath, file.baseFile.lastModified(), file.baseFile.length(), record)
            ConversationSyncTrace.record("cache-written", record.libraryResult?.seq ?: 0, android.os.SystemClock.elapsedRealtime() - started, appContext)
            ConversationSyncTrace.record(if (record.snapshot.running) "cache-running" else "cache-idle", record.libraryResult?.seq ?: 0, context = appContext)
        } catch (e: Exception) { file.failWrite(stream); throw e }
        directory.listFiles()?.filter { it.extension == "json" }?.sortedByDescending { it.lastModified() }
            ?.drop(50)?.forEach { lookup.forget(it.absolutePath); cache.forget(it.absolutePath); AtomicFile(it).delete() }
        changes.update { it + 1 }
    }
    fun read(id: String): TaskInboxRecord? = synchronized(lock) {
        if (!id.matches(Regex("[a-f0-9]{64}"))) return null
        runCatching {
            val file = File(directory, "$id.json")
            if (file.length() > 2_500_000) return null
            val cached = cache.get(file.absolutePath, file.lastModified(), file.length())
            val raw = cached ?: run {
                val plain = keys.decryptLocal(String(AtomicFile(file).readFully(), Charsets.UTF_8)) ?: return null
                json.decodeFromString<TaskInboxRecord>(plain).takeIf { it.id == id }?.also {
                    cache.remember(file.absolutePath, file.lastModified(), file.length(), it)
                }
            }
            raw?.let { record ->
                lookup.remember(file.absolutePath, file.lastModified(), file.length(), record)
                val options = preferences.readOrNull(record.endpointHash, record.snapshot.conversation_id.ifBlank { record.id })
                if (options == null) record else record.copy(selectedModel = options.model, selectedEffort = options.effort, selectedMode = options.mode, pinned = options.pinned, selectedPermission = options.permission)
            }
        }.getOrNull()
    }
    fun replaceExisting(record: TaskInboxRecord) = synchronized(lock) {
        val existing = read(record.id) ?: return@synchronized
        save(ConversationIndex.mergeDownloaded(existing, record))
    }
    fun saveNotification(incoming: TaskInboxRecord, completed: Boolean): TaskInboxRecord = synchronized(lock) {
        // Cold or externally changed files are still read authoritatively. Once
        // indexed, only matching conversations need decryption for a notification.
        val candidates = directory.listFiles()?.filter { it.extension == "json" }?.mapNotNull { file ->
            val matches = lookup.matches(file.absolutePath, file.lastModified(), file.length(), incoming)
            if (matches == false) null else read(file.nameWithoutExtension)?.takeIf { ConversationIndex.sameConversation(it, incoming) }
        }.orEmpty()
        val existing = ConversationIndex.latestForDisplay(candidates).firstOrNull()
        val canonical = ConversationIndex.mergeNotification(existing, incoming, completed)
        save(canonical)
        canonical
    }
    fun updateRemote(id: String, update: (RemoteConversationState) -> RemoteConversationState) = synchronized(lock) {
        val record = read(id) ?: return@synchronized
        val old = record.remoteState ?: return@synchronized
        val state = update(old)
        if (state == old) return@synchronized
        val event = state.event
        var snapshot = if (event?.snapshot != null && !event.attachment_pending && (event.snapshot.conversation_id == record.snapshot.conversation_id ||
            state.command.action == "create" && event.snapshot.remote_ref?.host_id == state.command.host_id)) {
            val incoming = event!!.snapshot!!
            if (incoming.conversation_id != record.snapshot.conversation_id && state.command.action == "create" ||
                !ConversationSnapshotRules.regresses(record, incoming)) incoming else record.snapshot
        } else if (event != null && ConversationSnapshotRules.currentTask(record, state) && !ConversationSnapshotRules.hasNativeReply(record, state) && !event.attachment_pending && event.status in setOf("completed", "interrupted") && event.turn_id.isNotBlank() && old.event?.status !in setOf("completed", "interrupted") && state.command.action !in RemoteGoalRules.rootActions) {
            record.snapshot.copy(remote_ref = record.snapshot.remote_ref?.copy(baseline_turn = event.turn_id),
                reply = event.reply, completed_at = "",
                messages = (record.snapshot.messages + listOf(TaskConversationMessage("user", ConversationHistory.activeInput(state, record.followUps)), TaskConversationMessage("assistant", boundedText(event.reply, 16_384)))).takeLast(60),
                truncated = record.snapshot.truncated || event.partial || event.reply.toByteArray().size > 16_384)
        } else if (event?.status == "failed" && event.turn_id.isNotBlank() && ConversationSnapshotRules.currentTask(record, state)) {
            record.snapshot.copy(remote_ref = record.snapshot.remote_ref?.copy(baseline_turn = event.turn_id))
        } else record.snapshot
        while (json.encodeToString(snapshot).toByteArray().size > TaskContentCipher.MAX_BYTES && snapshot.messages.isNotEmpty()) {
            snapshot = snapshot.copy(messages = snapshot.messages.drop(1), truncated = true)
        }
        if (snapshot.conversation_id != record.snapshot.conversation_id) preferences.update(record.endpointHash, snapshot.conversation_id) {
            it.copy(model = record.selectedModel, effort = record.selectedEffort, mode = record.selectedMode)
        }
        // The canonical snapshot is stored once, not duplicated inside the transport state.
        save(record.copy(snapshot = snapshot, hasFullSnapshot = record.hasFullSnapshot || event?.snapshot != null && !event.attachment_pending,
            activities = RemoteActivityRules.merge(record.activities, event?.activities.orEmpty() + snapshot.activities),
            remoteState = state.copy(event = event?.copy(snapshot = null))))
    }
    fun requestFollowUp(id: String, command: RemoteCommand) = synchronized(lock) {
        val record = requireNotNull(read(id))
        val root = requireNotNull(RemoteFollowUpRules.source(record))
        require(root.command.id == command.target_id && root.command.thread_id == command.thread_id &&
            root.command.host_id == command.host_id && root.command.conversation_id == command.conversation_id)
        val pending = record.followUps.filter { FollowUpState.pending(it) }
        require(pending.size < 8)
        save(record.copy(followUps = (pending + record.followUps.filterNot { FollowUpState.pending(it) }.takeLast(7) +
            RemoteConversationState(command)).takeLast(16)))
    }
    fun updateFollowUp(id: String, requestId: String, update: (RemoteConversationState) -> RemoteConversationState) = synchronized(lock) {
        val record = read(id) ?: return@synchronized
        save(record.copy(followUps = record.followUps.map { if (it.command.id == requestId) update(it) else it }))
    }
    fun replaceDownloaded(record: TaskInboxRecord) = synchronized(lock) {
        val existing = read(record.id) ?: return@synchronized
        save(ConversationIndex.mergeDownloaded(existing, record))
    }
    fun requestModels(id: String, command: RemoteCommand) = synchronized(lock) {
        val record = read(id) ?: return@synchronized
        save(record.copy(modelRequest = command))
    }
    fun updateModels(id: String, event: RemoteEvent) = synchronized(lock) {
        val record = read(id) ?: return@synchronized
        val command = record.modelRequest ?: return@synchronized
        if (event.status != "models" || event.request_id != command.id || event.host_id != command.host_id ||
            event.thread_id != command.thread_id || event.conversation_id != command.conversation_id) return@synchronized
        save(record.copy(modelCatalog = if (event.attachment_pending) event.copy(error = "MODEL_LIST_INCOMPLETE") else event))
    }
    fun setOptions(id: String, model: String, effort: String, mode: String? = null) = synchronized(lock) {
        val record = read(id) ?: return@synchronized
        require(model.length <= 128 && effort.length <= 32)
        val selectedMode = mode ?: record.selectedMode
        require(selectedMode in setOf("", "plan", "default"))
        preferences.update(record.endpointHash, record.snapshot.conversation_id.ifBlank { record.id }) { it.copy(model = model, effort = effort, mode = selectedMode) }
        save(record.copy(selectedModel = model, selectedEffort = effort, selectedMode = selectedMode))
    }
    fun requestLibrary(id: String, command: RemoteCommand) = synchronized(lock) {
        val record = read(id) ?: return@synchronized
        save(record.copy(libraryRequest = command))
    }
    fun setPermission(id: String, permission: String) = synchronized(lock) {
        require(permission in RemoteSessionRules.permissions)
        val record = read(id) ?: return@synchronized
        preferences.update(record.endpointHash, record.snapshot.conversation_id.ifBlank { id }) { it.copy(permission = permission) }
        save(record.copy(selectedPermission = permission))
    }
    fun requestSession(id: String, command: RemoteCommand) = synchronized(lock) {
        val record = read(id) ?: return@synchronized
        save(if (command.action == "skills") record.copy(skillsRequest = command, skillsResult = null) else record.copy(
            sessionRequest = command, sessionResult = null, lastSessionResult = RemoteSessionCache.display(record)))
    }
    fun updateSession(id: String, event: RemoteEvent) = synchronized(lock) {
        val record = read(id) ?: return@synchronized
        val skills = event.status == "skills"
        if (RemoteSessionRules.accepts(if (skills) record.skillsRequest else record.sessionRequest, if (skills) record.skillsResult else record.sessionResult, event))
            save(if (skills) record.copy(skillsResult = event) else record.copy(sessionResult = event,
                lastSessionResult = if (RemoteSessionCache.usable(record, event)) event else record.lastSessionResult))
    }
    fun requestGoal(id: String, command: RemoteCommand) = synchronized(lock) {
        val record = read(id) ?: return@synchronized
        require(command.action in RemoteGoalRules.actions)
        save(record.copy(goalRequest = command, goalResult = null,
            remoteState = if (command.action in RemoteGoalRules.rootActions) RemoteConversationState(command) else record.remoteState))
    }
    /** Compare the persisted state, not the older copy held while preparing attachments. */
    fun beginRemote(id: String, command: RemoteCommand) = synchronized(lock) {
        val record = requireNotNull(read(id))
        save(RemoteSendRules.begin(record, command))
    }
    fun updateGoal(id: String, event: RemoteEvent) = synchronized(lock) {
        val record = read(id) ?: return@synchronized
        val command = record.goalRequest ?: return@synchronized
        if (!RemoteGoalRules.accepts(command, record.goalResult, event)) return@synchronized
        save(record.copy(goalResult = event.copy(snapshot = null, messages = emptyList(), activities = emptyList())))
    }
    fun setPinned(id: String, pinned: Boolean) = synchronized(lock) {
        val record = read(id) ?: return@synchronized
        preferences.update(record.endpointHash, record.snapshot.conversation_id.ifBlank { record.id }) { it.copy(pinned = pinned) }
        save(record.copy(pinned = pinned))
    }
    fun consumeLibrarySnapshot(id: String, eventId: String) = synchronized(lock) {
        val record = read(id) ?: return@synchronized
        val event = record.libraryResult?.takeIf { it.id == eventId } ?: return@synchronized
        save(record.copy(libraryResult = event.copy(snapshot = null)))
    }
    fun updateLibrary(id: String, incoming: RemoteEvent) = synchronized(lock) {
        val record = read(id) ?: return@synchronized
        val query = record.libraryRequest ?: return@synchronized
        val event = if (incoming.reply_patch != null || incoming.reply_patch_pending)
            RemoteProtocol.materialize(RemoteConversationState(query, record.libraryResult), incoming) ?: return@synchronized else incoming
        if (event.request_id != query.id || event.host_id != query.host_id || event.thread_id != query.thread_id ||
            event.conversation_id != query.conversation_id || (record.libraryResult?.request_id == query.id && event.seq <= record.libraryResult.seq &&
                !(record.libraryResult.attachment_pending && !event.attachment_pending && event.id == record.libraryResult.id &&
                    event.seq == record.libraryResult.seq && event.status == record.libraryResult.status && event.at == record.libraryResult.at))) return@synchronized
        if (event.status == "forked" && !event.attachment_pending && !RemoteForkRules.confirmed(query, event)) return@synchronized
        // Keep only routing metadata here; the full snapshot has its own cache record.
        val result = event.copy(snapshot = event.snapshot?.copy(messages = emptyList(), reply = ""), messages = emptyList())
        val success = event.error.isBlank() && !event.partial
        var updated = record.copy(libraryResult = result)
        if (query.action == "read" && success && !event.attachment_pending && event.snapshot != null && !record.libraryAnchor) {
            val synced = record.copy(snapshot = event.snapshot, hasFullSnapshot = true)
            if (ConversationIndex.sameConversation(record, synced)) {
                // One atomic encrypted write, not an intermediate record plus a
                // second write of the same record. Preserve options and pending tasks.
                val merged = ConversationIndex.mergeSynced(updated, synced)
                save(if (ConversationSnapshotRules.regresses(record, event.snapshot, nativeRewrite = true)) merged
                    else merged.copy(contentValidatedAt = System.currentTimeMillis()))
                return@synchronized
            }
        }
        if (event.status == "activities" && success) updated = updated.copy(
            activities = RemoteActivityRules.merge(record.activities, event.activities), activityCursor = event.next_cursor, activityLoaded = true)
        if (event.status == "threads" && success) {
            val catalog = if (query.cursor.isBlank()) event.threads else (record.threadCatalog + event.threads).distinctBy { it.thread_id }
            updated = updated.copy(threadCatalog = catalog.take(1000), threadCursor = if (catalog.size < 1000) event.next_cursor else "",
                threadCatalogArchived = query.archived, threadCatalogSearch = query.search,
                threadCatalogAt = System.currentTimeMillis())
        }
        if (event.status == "history" && success) {
            var history = ConversationHistory.merge(event.messages, record.historyMessages)
            val limited = history.size > 500 || history.sumOf { it.text.toByteArray().size } > 1_000_000
            while (history.size > 500 || history.sumOf { it.text.toByteArray().size } > 1_000_000) history = history.drop(1)
            updated = updated.copy(historyMessages = history, activities = RemoteActivityRules.merge(record.activities, event.activities), historyCursor = if (limited) "" else event.next_cursor,
                historyLimited = record.historyLimited || limited || event.history_truncated)
        }
        save(updated)
        if (event.status == "managed" && success && event.managed_thread_id == query.read_thread_id && event.managed_action == query.action) {
            list().filter { it.endpointHash == record.endpointHash }.forEach { cached ->
                save(ConversationIndex.applyManagement(cached, record.endpointHash, query, event))
            }
        }
        event.snapshot?.takeIf { success && !event.attachment_pending }?.let { snapshot ->
            val cachedId = hash(record.endpointHash + ":conversation:" + snapshot.conversation_id)
            val synced = TaskInboxRecord(cachedId, event.at, "remote-sync:" + snapshot.conversation_id, record.endpointHash, snapshot, hasFullSnapshot = true,
                archived = query.archived,
                selectedModel = if (query.action == "fork") record.selectedModel else "",
                selectedEffort = if (query.action == "fork") record.selectedEffort else "",
                selectedMode = if (query.action == "fork") record.selectedMode else "")
            // The viewed record already owns this host/conversation. Do not decrypt
            // every other history file for each streaming frame.
            val existing = if (!updated.libraryAnchor && ConversationIndex.sameConversation(updated, synced)) updated
                else ConversationIndex.latestForDisplay(list()).firstOrNull { ConversationIndex.sameConversation(it, synced) }
            val merged = ConversationIndex.mergeSynced(existing, synced)
            save(merged.copy(archived = query.archived, contentValidatedAt =
                if (existing == null || !ConversationSnapshotRules.regresses(existing, synced.snapshot, nativeRewrite = true)) System.currentTimeMillis()
                else merged.contentValidatedAt))
        }
    }
    fun list(): List<TaskInboxRecord> = synchronized(lock) {
        directory.listFiles()?.filter { it.extension == "json" }?.mapNotNull { read(it.nameWithoutExtension) }
            ?.sortedByDescending { it.receivedAt }.orEmpty()
    }
    /** Resolve old notification IDs without decrypting unrelated cached conversations. */
    fun latestFor(id: String): TaskInboxRecord? = synchronized(lock) {
        val opened = read(id) ?: return@synchronized null
        val candidates = directory.listFiles()?.filter { it.extension == "json" }?.mapNotNull { file ->
            if (lookup.matches(file.absolutePath, file.lastModified(), file.length(), opened) == false) null
            else read(file.nameWithoutExtension)?.takeIf { ConversationIndex.sameConversation(opened, it) }
        }.orEmpty()
        ConversationIndex.latestForDisplay(candidates).firstOrNull() ?: opened
    }
    fun clear() = synchronized(lock) {
        lookup.clear()
        cache.clear()
        preferences.clear()
        directory.listFiles()?.forEach { AtomicFile(it).delete() }
        directory.delete()
        changes.update { it + 1 }
    }
    companion object {
        private val lock = Any()
        private val lookup = ConversationLookupIndex()
        private val cache = ConversationFileCache<TaskInboxRecord>(50, 8L * 1024 * 1024)
        val changes = MutableStateFlow(0L)
        fun hash(value: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            val hex = "0123456789abcdef"
            return String(CharArray(digest.size * 2) { index ->
                val byte = digest[index / 2].toInt() and 0xff
                hex[if (index % 2 == 0) byte ushr 4 else byte and 0xf]
            })
        }
        private fun boundedText(text: String, bytes: Int) = String(text.toByteArray().take(bytes).toByteArray(), Charsets.UTF_8).trimEnd('\uFFFD')
    }
}
