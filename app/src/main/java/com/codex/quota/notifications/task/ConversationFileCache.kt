package com.codex.quota.notifications.task

/** Process-only cache. Owners hold their store lock; changed files always require a new read. */
internal class ConversationFileCache<T>(private val capacity: Int, private val maxBytes: Long) {
    private data class Entry<T>(val modified: Long, val bytes: Long, val value: T)
    private val entries = LinkedHashMap<String, Entry<T>>(capacity, 0.75f, true)
    private var bytes = 0L

    fun get(path: String, modified: Long, size: Long): T? {
        val entry = entries[path] ?: return null
        if (entry.modified != modified || entry.bytes != size) { forget(path); return null }
        return entry.value
    }

    fun remember(path: String, modified: Long, size: Long, value: T) {
        forget(path)
        if (size <= 0 || size > maxBytes) return
        entries[path] = Entry(modified, size, value)
        bytes += size
        while (entries.size > capacity || bytes > maxBytes) forget(entries.keys.first())
    }

    fun forget(path: String) { entries.remove(path)?.let { bytes -= it.bytes } }
    fun clear() { entries.clear(); bytes = 0 }
}
