package com.codex.quota.notifications.task

/** Presentation only: native model and skill identities remain unchanged. */
object ComposerCatalog {
    fun commonModels(models: List<RemoteModelOption>, selected: String): List<RemoteModelOption> {
        val unique = models.distinctBy { it.model }
        val first = unique.firstOrNull { it.is_default }
        val current = unique.firstOrNull { it.model == selected }
        return (listOfNotNull(first, current) + unique).distinctBy { it.model }.take(6)
    }

    private val categories = listOf(
        listOf("documents:documents", "documents", "docx"),
        listOf("pdf:pdf", "pdf"),
        listOf("spreadsheets:spreadsheets", "spreadsheets", "xlsx"),
        listOf("presentations:presentations", "presentations", "pptx"),
        listOf("computer-use:computer-use", "computer-use")
    )
    fun skills(available: List<RemoteSkill>): List<RemoteSkill> = categories.mapNotNull { names ->
        available.filter { it.name.lowercase() in names }.minWithOrNull(
            compareBy<RemoteSkill> { names.indexOf(it.name.lowercase()) }.thenByDescending { it.plugin }.thenBy { it.id })
    }.distinctBy { it.id }
}
