package com.codex.quota.notifications.task

/** Only project identities advertised by the selected paired computer can be selected. */
object ConversationEnvironmentRules {
    const val NO_PROJECT = "default"
    fun validProject(id: String): Boolean = id == NO_PROJECT || id.matches(Regex("[a-f0-9]{64}"))
    fun projects(anchor: TaskInboxRecord?, computer: ComputerConnection?): List<RemoteThreadSummary> =
        if (anchor == null || computer == null || !anchor.libraryAnchor || !computer.matches(anchor)) emptyList()
        else anchor.threadCatalog.filter { it.project_id.isNotBlank() }.distinctBy { it.project_id }
    fun resolveProject(selected: String, projects: List<RemoteThreadSummary>): String =
        if (selected == NO_PROJECT || projects.any { it.project_id == selected }) selected else NO_PROJECT
}
