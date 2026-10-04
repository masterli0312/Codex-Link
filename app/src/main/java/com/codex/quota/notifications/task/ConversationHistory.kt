package com.codex.quota.notifications.task

/** Server pages are oldest first; de-duplicate by item identity, never by identical message text. */
object ConversationHistory {
    fun activeInput(state: RemoteConversationState, children: List<RemoteConversationState>): String {
        val event = state.event
        if (event?.attachment_pending != true && !event?.active_input.isNullOrBlank()) return event!!.active_input
        val active = children.filter { it.command.action == "queue" && it.event?.parent_request_id == state.command.id &&
            it.event.status in setOf("accepted", "running") && (event?.status == "accepted" || it.event.turn_id == event?.turn_id) }
        return active.singleOrNull()?.command?.text ?: state.command.text
    }
    /** Keep confirmed interventions visible before the native transcript catches up. */
    fun withLiveInput(messages: List<TaskConversationMessage>,state: RemoteConversationState,children: List<RemoteConversationState>): List<TaskConversationMessage> {
        val turn = state.event?.turn_id?.ifBlank { null } ?: state.command.id
        val inputs = messages.filter { it.id.substringBefore(':', "") == turn && it.role == "user" }
        val prompt = activeInput(state,children)
        val root = if (inputs.any { ConversationDisplayText.userText(it.text) == ConversationDisplayText.userText(prompt) }) emptyList()
            else listOf(TaskConversationMessage("user",prompt,"$turn:phone-input",position = 0))
        val accepted = children.filter { it.event?.status == "steered" && it.event.turn_id == turn &&
            inputs.none { m -> ConversationDisplayText.userText(m.text) == it.command.text } }
            .map { TaskConversationMessage("user",it.command.text,"$turn:steer-${it.command.id}",position = 1_000_000) }
        val retained = messages.filterNot { it.id.substringBefore(':', "") == turn }
        val reply = state.event?.reply.orEmpty().takeIf { it.isNotBlank() }?.let {
            listOf(TaskConversationMessage("assistant",it,"$turn:phone-reply",position = 1_000_000,phase = "commentary"))
        }.orEmpty()
        return retained + root + inputs + accepted + reply
    }
    fun key(message: TaskConversationMessage, messages: List<TaskConversationMessage>): String =
        if (message.id.isNotBlank()) "message:" + message.id else
            "legacy:" + TaskInbox.hash(message.role + ":" + message.text) + ":" + messages.indexOfFirst { it === message }
    fun merge(older: List<TaskConversationMessage>, newer: List<TaskConversationMessage>): List<TaskConversationMessage> {
        val newIds = newer.map { it.id }.filter { it.isNotBlank() }.toSet()
        return older.filter { old ->
            (old.id.isBlank() || old.id !in newIds) && !(old.id.substringAfter(':') in setOf("phone-input","phone-reply") &&
                newer.any { it.id.isNotBlank() && it.id.substringBefore(':') == old.id.substringBefore(':') &&
                    it.id.substringAfter(':') !in setOf("phone-input","phone-reply") && it.role == old.role &&
                    ConversationDisplayText.userText(it.text) == ConversationDisplayText.userText(old.text) })
        } + newer
    }
}
