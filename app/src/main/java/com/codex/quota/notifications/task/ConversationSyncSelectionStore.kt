package com.codex.quota.notifications.task

import android.content.Context
import android.util.AtomicFile
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class SyncedConversation(val thread: RemoteThreadSummary, val archived: Boolean = false, val stateChangedAt: Long = 0,
    val activityAt: Long = 0, val completedAt: Long = 0, val completedTurn: String = "", val seenCompletionAt: Long = 0) {
    val unread: Boolean get() = completedAt > seenCompletionAt
}

@Serializable
data class ConversationSyncSelection(val conversations: List<SyncedConversation> = emptyList()) {
    @kotlinx.serialization.Transient val ids: Set<String> = conversations.map { it.thread.thread_id }.toSet()
}

object ConversationSyncRules {
    fun activity(entry: SyncedConversation, turn: String, running: Boolean, at: Long, completed: Boolean): SyncedConversation {
        if (at <= entry.activityAt || turn.isBlank() || completed && turn == entry.completedTurn) return entry
        val newCompletion = completed && turn != entry.completedTurn
        return entry.copy(thread = entry.thread.copy(running = running, updated_at = maxOf(entry.thread.updated_at, at)), activityAt = at,
            completedAt = if (newCompletion) at else entry.completedAt,
            completedTurn = if (newCompletion) turn else entry.completedTurn)
    }
    private val uuid = Regex("[a-fA-F0-9]{8}(-[a-fA-F0-9]{4}){3}-[a-fA-F0-9]{12}")
    fun validId(id: String) = id.matches(uuid)
    fun scope(computer: ComputerConnection) = TaskInbox.hash(TaskInbox.hash(computer.endpoint) + ":" + computer.hostId)
    fun allows(record: TaskInboxRecord, computer: ComputerConnection, selection: ConversationSyncSelection): Boolean {
        if (pendingCreation(record, computer)) return true
        val ref = record.snapshot.thread_ref ?: record.snapshot.remote_ref ?: return false
        return !record.libraryAnchor && computer.matches(record) && ref.thread_id in selection.ids
    }
    fun pendingCreation(record: TaskInboxRecord, computer: ComputerConnection): Boolean {
        val command = record.remoteState?.command ?: return false
        return record.pendingSyncCreation && !record.libraryAnchor && computer.matches(record) &&
            command.action == "create" && record.identity == "remote-create:" + command.id &&
            record.snapshot.thread_ref == null && record.snapshot.remote_ref == null &&
            record.snapshot.conversation_id == TaskInbox.hash(command.id)
    }
    fun caches(snapshot: TaskConversationSnapshot, computer: ComputerConnection, selection: ConversationSyncSelection): Boolean {
        val ref = snapshot.thread_ref ?: snapshot.remote_ref ?: return false
        return ref.host_id == computer.hostId && ref.thread_id in selection.ids &&
            snapshot.conversation_id == TaskInbox.hash(ref.thread_id)
    }
    fun replace(old: ConversationSyncSelection, ids: Set<String>, available: List<RemoteThreadSummary>): ConversationSyncSelection {
        require(ids.size <= 1000 && ids.all(::validId))
        val previous = old.conversations.associateBy { it.thread.thread_id }
        val catalog = available.associateBy { it.thread_id }
        return ConversationSyncSelection(ids.map { id ->
            val existing = previous[id]
            val summary = catalog[id] ?: existing?.thread ?: throw IllegalArgumentException("SYNC_THREAD_UNAVAILABLE")
            existing?.copy(thread = summary) ?: SyncedConversation(summary)
        })
    }
}

/** Explicit opt-in, encrypted, and isolated by relay endpoint AND computer identity. No legacy auto-selection. */
class ConversationSyncSelectionStore(context: Context) {
    private val directory = File(context.applicationContext.noBackupFilesDir, "conversation-sync-selection")
    private val keys = TaskContentKeys(context.applicationContext)
    private val json = Json { ignoreUnknownKeys = true }
    private fun file(computer: ComputerConnection) = AtomicFile(File(directory, ConversationSyncRules.scope(computer) + ".json"))
    fun read(computer: ComputerConnection): ConversationSyncSelection = synchronized(lock) {
        val atomic = file(computer)
        cache.get(atomic.baseFile.absolutePath, atomic.baseFile.lastModified(), atomic.baseFile.length())?.let { return@synchronized it }
        runCatching {
            require(atomic.baseFile.length() <= 2_500_000)
            val plain = keys.decryptLocal(String(atomic.readFully(), Charsets.UTF_8)) ?: return@synchronized ConversationSyncSelection()
            json.decodeFromString<ConversationSyncSelection>(plain).takeIf { it.conversations.size <= 1000 && it.ids.all(ConversationSyncRules::validId) }
                ?.also { cache.remember(atomic.baseFile.absolutePath, atomic.baseFile.lastModified(), atomic.baseFile.length(), it) }
        }.getOrNull() ?: ConversationSyncSelection()
    }
    fun isSelected(computer: ComputerConnection, id: String) = id in read(computer).ids
    fun markActivity(computer: ComputerConnection, id: String, turn: String, running: Boolean, at: Long, completed: Boolean = !running) = synchronized(lock) {
        val previous = read(computer)
        val updated = previous.copy(conversations = previous.conversations.map {
            if (it.thread.thread_id == id) ConversationSyncRules.activity(it, turn, running, at, completed) else it
        })
        if (updated != previous) write(computer, updated)
    }
    fun markSeen(computer: ComputerConnection, id: String) = synchronized(lock) {
        val previous = read(computer)
        val updated = previous.copy(conversations = previous.conversations.map {
            if (it.thread.thread_id == id && it.unread) it.copy(seenCompletionAt = it.completedAt) else it
        })
        if (updated != previous) write(computer, updated)
    }
    fun replace(computer: ComputerConnection, ids: Set<String>, available: List<RemoteThreadSummary>) = synchronized(lock) {
        write(computer, ConversationSyncRules.replace(read(computer), ids, available))
    }
    fun add(computer: ComputerConnection, thread: RemoteThreadSummary, archived: Boolean = false) = synchronized(lock) {
        require(ConversationSyncRules.validId(thread.thread_id))
        val previous = read(computer)
        if (thread.thread_id !in previous.ids) write(computer,
            previous.copy(conversations = previous.conversations + SyncedConversation(thread, archived)))
    }
    fun confirmArchive(computer: ComputerConnection, id: String, archived: Boolean, at: Long) = synchronized(lock) {
        val previous = read(computer)
        val updated = previous.copy(conversations = previous.conversations.map {
            if (it.thread.thread_id == id) it.copy(archived = archived, stateChangedAt = at) else it
        })
        if (updated != previous) write(computer, updated)
    }
    fun confirmName(computer: ComputerConnection, id: String, name: String, at: Long) = synchronized(lock) {
        val previous = read(computer)
        val updated = previous.copy(conversations = previous.conversations.map {
            if (it.thread.thread_id == id) it.copy(thread = it.thread.copy(title = name), stateChangedAt = at) else it
        })
        if (updated != previous) write(computer, updated)
    }
    fun catalog(computer: ComputerConnection, query: RemoteCommand, event: RemoteEvent) = synchronized(lock) {
        if (query.action != "threads" || event.status != "threads" || query.host_id != computer.hostId || event.host_id != computer.hostId ||
            event.request_id != query.id || event.thread_id != query.thread_id || event.conversation_id != query.conversation_id ||
            event.partial || event.attachment_pending || event.error.isNotBlank()) return@synchronized
        val previous = read(computer)
        val byId = event.threads.associateBy { it.thread_id }
        val updated = previous.copy(conversations = previous.conversations.map { entry ->
            val thread = byId[entry.thread.thread_id]
            if (thread != null && query.issued_at > entry.stateChangedAt) entry.copy(thread =
                if (query.issued_at <= entry.activityAt) thread.copy(running = entry.thread.running) else thread, archived = query.archived,
                stateChangedAt = if (entry.archived != query.archived) query.issued_at else entry.stateChangedAt) else entry
        })
        if (updated != previous) write(computer, updated)
    }
    private fun write(computer: ComputerConnection, selection: ConversationSyncSelection) {
        require(selection.conversations.size <= 1000)
        directory.mkdirs()
        val atomic = file(computer); val stream = atomic.startWrite()
        try { stream.write(keys.encryptLocal(json.encodeToString(selection)).toByteArray(Charsets.UTF_8)); atomic.finishWrite(stream) }
        catch (e: Exception) { atomic.failWrite(stream); throw e }
        cache.remember(atomic.baseFile.absolutePath, atomic.baseFile.lastModified(), atomic.baseFile.length(), selection)
        changes.update { it + 1 }
    }
    companion object {
        private val lock = Any()
        private val cache = ConversationFileCache<ConversationSyncSelection>(32, 8L * 1024 * 1024)
        val changes = MutableStateFlow(0L)
    }
}
