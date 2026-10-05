package com.codex.quota.data.cloud

/** Keep already opened pages during live polling, with explicit bounds for a phone. */
object CloudHistory {
    fun messages(details: CloudDetails): List<CloudMessage> {
        val freshIds = details.messages.map { it.id }.filter { it.isNotBlank() }.toSet()
        return details.olderMessages.filterNot { it.id.isNotBlank() && it.id in freshIds } + details.messages
    }
    fun cursor(details: CloudDetails) = if (details.historyLimited) "" else details.olderCursor ?: details.historyCursor
    fun refreshed(previous: CloudDetails?, fresh: CloudDetails): CloudDetails {
        if (previous == null || previous.task.id != fresh.task.id || previous.olderCursor == null) return fresh
        val ids = fresh.messages.map { it.id }.toSet()
        return bounded(fresh.copy(olderMessages = messages(previous).filterNot { it.id in ids },
            olderCursor = previous.olderCursor, historyLimited = previous.historyLimited))
    }
    fun prepend(current: CloudDetails, older: CloudDetails, requestedCursor: String): CloudDetails {
        require(current.task.id == older.task.id && cursor(current) == requestedCursor && requestedCursor.isNotBlank())
        require(older.historyCursor != requestedCursor) // Broken pagination must not loop forever.
        val known = messages(current).map { it.id }.toSet()
        return bounded(current.copy(olderMessages = older.messages.filterNot { it.id in known } + current.olderMessages,
            olderCursor = older.historyCursor))
    }
    private fun bounded(details: CloudDetails): CloudDetails {
        var history = details.olderMessages
        var limited = details.historyLimited
        while (history.size > 300 || history.sumOf { it.text.toByteArray().size } > 192 * 1024) {
            history = history.drop(1); limited = true
        }
        return details.copy(olderMessages = history, historyLimited = limited)
    }
}
