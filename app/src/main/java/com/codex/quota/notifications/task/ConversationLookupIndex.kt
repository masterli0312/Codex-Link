package com.codex.quota.notifications.task

/** Process-local routing metadata only; full records remain encrypted on disk. */
internal class ConversationLookupIndex(private val capacity: Int = 100) {
    private data class Entry(val modified: Long, val bytes: Long, val identity: TaskInboxRecord)
    private val entries = LinkedHashMap<String, Entry>(capacity, 0.75f, true)

    fun remember(path: String, modified: Long, bytes: Long, record: TaskInboxRecord) {
        val host = record.snapshot.thread_ref?.host_id ?: record.snapshot.remote_ref?.host_id ?:
            record.remoteState?.command?.host_id ?: record.computerHostId
        val identity = TaskInboxRecord(record.id, 0, "", record.endpointHash,
            TaskConversationSnapshot(conversation_id = record.snapshot.conversation_id), computerHostId = host)
        entries[path] = Entry(modified, bytes, identity)
        while (entries.size > capacity) entries.remove(entries.keys.first())
    }

    /** null requires an authoritative read; false skips only a known unrelated record. */
    fun matches(path: String, modified: Long, bytes: Long, incoming: TaskInboxRecord): Boolean? {
        val entry = entries[path] ?: return null
        if (entry.modified != modified || entry.bytes != bytes) { entries.remove(path); return null }
        return ConversationIndex.sameConversation(entry.identity, incoming)
    }

    fun forget(path: String) { entries.remove(path) }
    fun clear() { entries.clear() }
}
