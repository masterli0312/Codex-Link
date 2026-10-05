package com.codex.quota.notifications.task

data class ConversationListRow(val record: TaskInboxRecord? = null, val thread: RemoteThreadSummary? = null,
    val updatedAt: Long = 0) {
    val key: String get() = record?.id ?: requireNotNull(thread).thread_id
}

/** Build once on a worker dispatcher, never per clock tick, list row or keystroke in the composer. */
data class ConversationListPresentation(val rows: List<ConversationListRow> = emptyList(), val hasResults: Boolean = false) {
    companion object {
        fun build(records: List<TaskInboxRecord>, catalog: List<RemoteThreadSummary>, pairing: String?,
            query: String, archived: Boolean, computer: ComputerConnection?, computerOnly: Boolean,
            project: String): ConversationListPresentation {
            val latest = ConversationIndex.latestForDisplay(records.filterNot { it.libraryAnchor })
            val visible = latest.filter { it.archived == archived && ConversationIndex.matchesSearch(it, query) }
            val selectedRecords = if (computer == null) latest else latest.filter { computer.matches(it) }
            val covered = selectedRecords.filter { it.archived == archived && it.endpointHash == pairing && ConversationIndex.matchesSearch(it, query) }
                .map { it.snapshot.conversation_id }.toSet()
            val hiddenArchived = if (archived) emptySet() else selectedRecords.filter { it.endpointHash == pairing && it.archived }
                .map { it.snapshot.conversation_id }.toSet()
            val indexedCatalog = catalog.associateBy { TaskInbox.hash(it.thread_id) }
            val cachedRows = visible.map { record ->
                val thread = if (record.endpointHash == pairing && (computer == null || computer.matches(record)))
                    indexedCatalog[record.snapshot.conversation_id] else null
                ConversationListRow(record, thread, thread?.updated_at?.takeIf { it > 0 } ?: ConversationIndex.timestamp(record))
            }
            val remoteRows = indexedCatalog.filter { (id, thread) -> id !in covered && id !in hiddenArchived &&
                (query.isBlank() || thread.title.contains(query, true)) }.values.map { ConversationListRow(thread = it, updatedAt = it.updated_at) }
            val all = cachedRows + remoteRows
            val rows = all.filter { row ->
                (!computerOnly || row.thread != null || row.record?.let { computer?.matches(it) } == true) &&
                    (project.isBlank() || row.thread?.project_id == project)
            }.sortedWith(compareByDescending<ConversationListRow> { it.record?.pinned == true }.thenByDescending { it.updatedAt })
            return ConversationListPresentation(rows, all.isNotEmpty())
        }
    }
}
