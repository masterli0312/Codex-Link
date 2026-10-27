package com.codex.quota.notifications.task

import android.content.Context
import android.util.AtomicFile
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicLong

@Serializable
data class ConversationPreferences(val model: String = "", val effort: String = "", val draft: String = "", val pinned: Boolean = false, val mode: String = "", val permission: String = "default", val project: String = "default")

/** Stable pairing + conversation identity, independent of individual completion notification IDs. */
class ConversationPreferenceStore(context: Context) {
    private val directory = File(context.noBackupFilesDir, "conversation-preferences")
    private val keys = TaskContentKeys(context)
    private val json = Json { ignoreUnknownKeys = true }
    private fun file(pairing: String, conversation: String) = AtomicFile(File(directory, TaskInbox.hash("$pairing:$conversation") + ".json"))
    fun readOrNull(pairing: String, conversation: String): ConversationPreferences? = synchronized(lock) {
        val atomic = file(pairing, conversation)
        if (!StoredFileRead.existsOrUnknown(atomic.baseFile)) return@synchronized null
        runCatching { load(atomic, useCache = true) }.getOrNull()
    }
    private fun load(atomic: AtomicFile, useCache: Boolean): ConversationPreferences = StoredFileRead.forUpdate(
        StoredFileRead.existsOrUnknown(atomic.baseFile), { ConversationPreferences() }
    ) {
        if (useCache && !File(atomic.baseFile.path + ".bak").exists()) {
            cache.get(atomic.baseFile.absolutePath, atomic.baseFile.lastModified(), atomic.baseFile.length())?.let { return@forUpdate it }
        }
        val bytes = atomic.readFully()
        require(bytes.size <= 32_000)
        val plain = requireNotNull(keys.decryptLocal(String(bytes, Charsets.UTF_8)))
        json.decodeFromString<ConversationPreferences>(plain).also {
            cache.remember(atomic.baseFile.absolutePath, atomic.baseFile.lastModified(), atomic.baseFile.length(), it)
        }
    }
    fun read(pairing: String, conversation: String) = readOrNull(pairing, conversation) ?: ConversationPreferences()
    // Process-owned, ordered IO survives a screen leaving composition. Reads queue behind
    // pending writes so immediately reopening a conversation cannot restore an older draft.
    fun readAsync(pairing: String, conversation: String) = writer.submit { read(pairing, conversation) }
    fun updateAsync(pairing: String, conversation: String, change: (ConversationPreferences) -> ConversationPreferences): kotlinx.coroutines.Deferred<Unit> {
        val queuedGeneration = generation.get()
        return writer.submit {
            synchronized(lock) {
                // Clearing local data invalidates writes already queued by departed screens.
                if (queuedGeneration == generation.get()) update(pairing, conversation, change)
            }
        }
    }
    fun update(pairing: String, conversation: String, change: (ConversationPreferences) -> ConversationPreferences) = synchronized(lock) {
        val updated = change(load(file(pairing, conversation), useCache = false))
        require(updated.model.length <= 128 && updated.effort.length <= 32 && updated.draft.toByteArray().size <= 16_384)
        require(updated.mode in setOf("", "plan", "default") && updated.permission in RemoteSessionRules.permissions)
        require(ConversationEnvironmentRules.validProject(updated.project))
        directory.mkdirs()
        val atomic = file(pairing, conversation); val stream = atomic.startWrite()
        try { stream.write(keys.encryptLocal(json.encodeToString(updated)).toByteArray()); atomic.finishWrite(stream) }
        catch (e: Exception) { atomic.failWrite(stream); throw e }
        cache.remember(atomic.baseFile.absolutePath, atomic.baseFile.lastModified(), atomic.baseFile.length(), updated)
        directory.listFiles()?.filter { it.extension == "json" }?.sortedByDescending { it.lastModified() }?.drop(512)?.forEach { cache.forget(it.absolutePath); AtomicFile(it).delete() }
    }
    fun clear() = synchronized(lock) { generation.incrementAndGet(); cache.clear(); directory.listFiles()?.forEach { AtomicFile(it).delete() }; directory.delete(); Unit }
    companion object {
        private val lock = Any()
        private val generation = AtomicLong(0)
        private val writer = ConversationIoQueue()
        private val cache = ConversationFileCache<ConversationPreferences>(512, 2L * 1024 * 1024)
    }
}
