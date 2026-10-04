package com.codex.quota.notifications.task

object ConversationDefaults {
    /** Resolve blank legacy choices to actual catalog values without erasing a saved choice. */
    fun explicit(models: List<RemoteModelOption>, currentModel: String, currentEffort: String,
        model: String, effort: String, mode: String, modes: List<String>): Triple<String, String, String> {
        val selectedModel = model.ifBlank { currentModel.takeIf { value -> models.any { it.model == value } }.orEmpty() }
        val option = models.firstOrNull { it.model == selectedModel }
        val selectedEffort = effort.ifBlank { option?.let { m ->
            currentEffort.takeIf { it in m.efforts } ?: m.default_effort.takeIf { it in m.efforts } ?: m.efforts.firstOrNull()
        }.orEmpty() }
        return Triple(selectedModel, selectedEffort, mode.ifBlank { if ("default" in modes) "default" else "" })
    }
    /** Use actual successful phone selections on the same computer, with recency as tie-breaker. */
    fun options(records: List<TaskInboxRecord>, endpoint: String, host: String): Pair<String, String> {
        val choices = records.filter { !it.libraryAnchor && it.endpointHash == endpoint && (it.snapshot.remote_ref?.host_id == host || it.remoteState?.command?.host_id == host) }
            .sortedByDescending(ConversationIndex::timestamp).mapNotNull { record ->
                val command = record.remoteState?.command
                val model = record.selectedModel.ifBlank { command?.model.orEmpty() }
                if (model.isBlank()) null else model to record.selectedEffort.ifBlank { command?.effort.orEmpty() }
            }
        return choices.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: ("" to "")
    }
}
