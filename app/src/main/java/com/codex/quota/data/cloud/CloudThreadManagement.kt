package com.codex.quota.data.cloud

import kotlinx.serialization.json.*

/** Shared app-server protocol, including the official Cloud socket. Mutations are never retried. */
object CloudThreadManagement {
    fun request(action: String, threadId: String, name: String = ""): Pair<String, JsonObject> {
        require(DurableCloudWire.validId(threadId))
        require(action in setOf("rename", "archive", "unarchive"))
        val trimmed = name.trim()
        if (action == "rename") require(trimmed.isNotEmpty() && trimmed.toByteArray().size <= 240 &&
            trimmed.none { it == '\n' || it == '\r' || it == '\u0000' })
        return (when (action) { "rename" -> "thread/name/set"; "archive" -> "thread/archive"; else -> "thread/unarchive" }) to buildJsonObject {
            put("threadId", threadId)
            if (action == "rename") put("name", trimmed)
        }
    }
    /** Only apply after the provider acknowledges the write, within the captured identity. */
    fun apply(cache: CloudCache, action: String, threadId: String, name: String = ""): CloudCache = when (action) {
        "rename" -> cache.copy(page = cache.page.copy(items = cache.page.items.map {
            if (it.id == threadId) it.copy(title = name.trim()) else it }),
            archivedPage = cache.archivedPage.copy(items = cache.archivedPage.items.map { if (it.id == threadId) it.copy(title = name.trim()) else it }),
            details = cache.details.map { if (it.task.id == threadId) it.copy(task = it.task.copy(title = name.trim())) else it })
        "archive" -> cache.copy(page = cache.page.copy(items = cache.page.items.filterNot { it.id == threadId }),
            archivedPage = cache.archivedPage.copy(items = (cache.page.items.filter { it.id == threadId } + cache.archivedPage.items).distinctBy { it.id }),
            pinnedThreads = cache.pinnedThreads - threadId)
        "unarchive" -> cache.copy(archivedPage = cache.archivedPage.copy(items = cache.archivedPage.items.filterNot { it.id == threadId }),
            page = cache.page.copy(items = (cache.archivedPage.items.filter { it.id == threadId } + cache.page.items).distinctBy { it.id }))
        else -> cache
    }
}
