package com.codex.quota.notifications.task

import java.time.Instant

/** A task-status reply may acknowledge an old request without owning the current transcript. */
object ConversationSnapshotRules {
    fun turn(snapshot: TaskConversationSnapshot) = (snapshot.thread_ref ?: snapshot.remote_ref)?.baseline_turn.orEmpty()
    private fun time(snapshot: TaskConversationSnapshot) = runCatching { Instant.parse(snapshot.completed_at).toEpochMilli() }.getOrDefault(0)
    private fun previousTurn(current: TaskInboxRecord, candidate: String): Boolean {
        if (candidate.isBlank() || candidate == turn(current.snapshot)) return false
        val turns = (current.historyMessages + current.snapshot.messages).map { it.id.substringBefore(':', "") }
            .filter { it.isNotBlank() }.distinct()
        val candidateIndex = turns.indexOf(candidate)
        val currentIndex = turns.indexOf(turn(current.snapshot))
        return candidateIndex >= 0 && (currentIndex < 0 || candidateIndex < currentIndex)
    }

    fun regresses(current: TaskInboxRecord, incoming: TaskConversationSnapshot, nativeRewrite: Boolean = false): Boolean {
        if (current.snapshot.conversation_id != incoming.conversation_id) return true
        val oldRef = current.snapshot.thread_ref ?: current.snapshot.remote_ref
        val newRef = incoming.thread_ref ?: incoming.remote_ref
        if (oldRef != null && newRef != null && (oldRef.host_id != newRef.host_id || oldRef.thread_id != newRef.thread_id)) return true
        val incomingTurn = turn(incoming)
        if (previousTurn(current, incomingTurn)) return true
        if (incomingTurn != turn(current.snapshot) && time(incoming) > 0 && time(current.snapshot) > time(incoming)) return true
        val native = current.snapshot.messages.any { it.id.isNotBlank() }
        if (native && incomingTurn != turn(current.snapshot) && time(incoming) <= time(current.snapshot) &&
            incoming.messages.any { it.id.isNotBlank() } &&
            incoming.messages.none { it.id.substringBefore(':', "") == turn(current.snapshot) }) return true
        if (native && incoming.messages.none { it.id.isNotBlank() } &&
            (incomingTurn.isBlank() || incomingTurn == turn(current.snapshot))) return true
        if ((!nativeRewrite || time(incoming) <= time(current.snapshot)) && incomingTurn.isNotBlank() && incomingTurn == turn(current.snapshot) &&
            current.snapshot.reply.length > incoming.reply.length && current.snapshot.reply.startsWith(incoming.reply) &&
            (incoming.reply.isNotBlank() || native)) return true
        if (nativeRewrite && incomingTurn == turn(current.snapshot) && time(incoming) <= time(current.snapshot)) {
            val messages = incoming.messages.associateBy { it.id }
            if (current.snapshot.messages.filter { it.id.isNotBlank() && it.id.substringBefore(':') == incomingTurn }.any { old ->
                val replacement = messages[old.id]
                replacement == null || replacement.text.length < old.text.length && old.text.startsWith(replacement.text)
            }) return true
        }
        return false
    }

    fun currentTask(record: TaskInboxRecord, state: RemoteConversationState): Boolean {
        val candidate = state.event?.turn_id.orEmpty()
        if (previousTurn(record, candidate)) return false
        val current = turn(record.snapshot)
        if (candidate.isNotBlank() && candidate == current) return true
        return time(record.snapshot) <= 0 || state.command.issued_at + 1000 >= time(record.snapshot)
    }

    fun hasNativeReply(record: TaskInboxRecord, state: RemoteConversationState): Boolean {
        val currentTurn = state.event?.turn_id.orEmpty()
        return currentTurn.isNotBlank() && record.snapshot.messages.any {
            it.role == "assistant" && it.text.isNotBlank() && it.id.substringBefore(':', "") == currentTurn
        }
    }
}
