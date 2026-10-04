package com.codex.quota.notifications.task

/** Cache validity is scoped to the exact library anchor and query, never another computer. */
object RemoteCatalogRules {
    private const val REFRESH_AFTER = 60_000L
    private const val REQUEST_TIMEOUT = 45_000L

    fun shouldRefresh(record: TaskInboxRecord?, archived: Boolean, search: String, now: Long): Boolean {
        if (record == null || !record.libraryAnchor) return false
        val request = record.libraryRequest
        val result = record.libraryResult
        val confirmed = request != null && result?.request_id == request.id
        if (request?.action == "create" && (!confirmed || result?.status !in setOf("completed", "interrupted", "failed"))) return false
        if (request?.action == "threads" && request.archived == archived && request.search == search &&
            !confirmed && now - request.issued_at in 0 until REQUEST_TIMEOUT) return false
        return record.threadCatalogAt <= 0 || now - record.threadCatalogAt !in 0 until REFRESH_AFTER ||
            record.threadCatalogArchived != archived || record.threadCatalogSearch != search
    }

    fun shouldLoadModels(record: TaskInboxRecord?, now: Long): Boolean {
        if (record == null) return false
        val request = record.modelRequest
        val result = record.modelCatalog
        if (request != null && result?.request_id != request.id && now - request.issued_at in 0 until REQUEST_TIMEOUT) return false
        return result == null || result.error.isNotBlank() || result.partial || result.attachment_pending ||
            now - result.at !in 0 until 300_000
    }
}
