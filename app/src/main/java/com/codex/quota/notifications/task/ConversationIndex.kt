package com.codex.quota.notifications.task

import java.time.Instant

/** One visible conversation per pairing; event records retain their authenticated identities. */
object ConversationIndex {
    fun unreadThread(computer: ComputerConnection, thread: RemoteThreadSummary, archived: Boolean): TaskInboxRecord {
        val conversation = TaskInbox.hash(thread.thread_id)
        val ref = RemoteThreadRef(computer.hostId, thread.thread_id, "unread")
        require(RemoteProtocol.validRef(ref, conversation))
        val pairing = TaskInbox.hash(computer.endpoint)
        return TaskInboxRecord(TaskInbox.hash(pairing + ":host:" + computer.hostId + ":conversation:" + conversation), 0, "remote-sync:" + conversation, pairing,
            TaskConversationSnapshot(conversation_id = conversation, title = thread.title, thread_ref = ref, running = thread.running,
                completed_at = if (thread.updated_at > 0) Instant.ofEpochMilli(thread.updated_at).toString() else ""),
            archived = archived, historyCursor = "")
    }
    fun matchesThread(record: TaskInboxRecord, computer: ComputerConnection, threadId: String): Boolean {
        val ref = record.snapshot.thread_ref ?: record.snapshot.remote_ref ?: return false
        return !record.libraryAnchor && computer.matches(record) && ref.host_id == computer.hostId &&
            ref.thread_id == threadId && record.snapshot.conversation_id == TaskInbox.hash(threadId)
    }
    fun matchesSearch(record: TaskInboxRecord, query: String): Boolean = query.isBlank() ||
        record.snapshot.title.contains(query, true) || record.snapshot.reply.contains(query, true)

    fun uncoveredThreads(catalog: List<RemoteThreadSummary>, records: List<TaskInboxRecord>,
        pairing: String?, query: String, archived: Boolean): List<RemoteThreadSummary> {
        val covered = coveredConversations(latest(records.filterNot { it.libraryAnchor }), pairing, query, archived)
        return catalog.filter { thread ->
            (query.isBlank() || thread.title.contains(query, true)) && TaskInbox.hash(thread.thread_id) !in covered
        }
    }

    internal fun coveredConversations(latestRecords: List<TaskInboxRecord>, pairing: String?, query: String,
        archived: Boolean): Set<String> = if (archived) emptySet() else latestRecords.asSequence()
        .filter { !it.archived && it.endpointHash == pairing && matchesSearch(it, query) }
        .map { it.snapshot.conversation_id }.toSet()

    fun applyManagement(record: TaskInboxRecord, pairing: String, query: RemoteCommand, event: RemoteEvent): TaskInboxRecord {
        if (record.endpointHash != pairing || event.status != "managed" || event.partial || event.error.isNotBlank() ||
            event.request_id != query.id || event.host_id != query.host_id || event.thread_id != query.thread_id ||
            event.conversation_id != query.conversation_id || event.managed_thread_id != query.read_thread_id ||
            event.managed_action != query.action || query.action !in setOf("rename", "archive", "unarchive")) return record
        val target = record.snapshot.conversation_id == TaskInbox.hash(query.read_thread_id)
        val catalog = if (query.action == "rename") record.threadCatalog.map {
            if (it.thread_id == query.read_thread_id) it.copy(title = event.managed_name) else it
        } else record.threadCatalog.filter { it.thread_id != query.read_thread_id }
        return record.copy(threadCatalog = catalog,
            snapshot = if (target && query.action == "rename") record.snapshot.copy(title = event.managed_name) else record.snapshot,
            archived = if (target && query.action != "rename") query.action == "archive" else record.archived)
    }
    /** Zero means unknown; transport, receipt and phone clock times are never activity dates. */
    fun timestamp(record: TaskInboxRecord): Long = runCatching {
        Instant.parse(record.snapshot.completed_at).toEpochMilli().coerceAtLeast(0)
    }.getOrDefault(0L)

    private fun host(record: TaskInboxRecord): String = record.snapshot.thread_ref?.host_id ?: record.snapshot.remote_ref?.host_id ?: record.remoteState?.command?.host_id ?: record.computerHostId
    fun sameConversation(a: TaskInboxRecord, b: TaskInboxRecord): Boolean =
        a.endpointHash == b.endpointHash &&
            a.snapshot.conversation_id.ifBlank { a.id } == b.snapshot.conversation_id.ifBlank { b.id } &&
            (host(a).isBlank() || host(b).isBlank() || host(a) == host(b))

    fun hasPendingTurn(record: TaskInboxRecord): Boolean = record.remoteState?.let {
        it.event?.status !in setOf("completed", "interrupted", "failed")
    } ?: false

    /** Notification previews/log exports lack native item IDs. They cannot replace a
     * watched turn or the same turn's authoritative history, even if delivered first. */
    private fun keepsNativeContent(existing: TaskInboxRecord, incoming: TaskInboxRecord): Boolean {
        if (incoming.snapshot.messages.any { it.id.isNotBlank() }) return false
        val incomingTurn = incoming.snapshot.remote_ref?.baseline_turn?.takeIf { it.isNotBlank() }
        val event = existing.remoteState?.event
        val hasNativeContent = existing.snapshot.messages.any { it.id.isNotBlank() } ||
            event?.reply?.isNotBlank() == true && (hasPendingTurn(existing) || incomingTurn == event.turn_id)
        if (!hasNativeContent) return false
        val sameTurn = incomingTurn != null && (incomingTurn == existing.remoteState?.event?.turn_id ||
            incomingTurn == (existing.snapshot.thread_ref ?: existing.snapshot.remote_ref)?.baseline_turn)
        return hasPendingTurn(existing) || existing.snapshot.running || sameTurn
    }

    fun mergeNotification(existing: TaskInboxRecord?, incoming: TaskInboxRecord, completed: Boolean): TaskInboxRecord {
        if (existing == null) return incoming
        require(sameConversation(existing, incoming))
        val event = existing.remoteState?.event
        if (ConversationSnapshotRules.regresses(existing, incoming.snapshot)) return existing
        val ref = incoming.snapshot.remote_ref
        val confirmsTurn = existing.remoteState?.command?.action !in RemoteGoalRules.rootActions && existing.followUps.none { FollowUpState.pending(it) } &&
            event?.queued_entries.isNullOrEmpty() && completed && event?.turn_id?.isNotBlank() == true &&
            ref?.baseline_turn == event.turn_id && ref.host_id == event.host_id
        if (!confirmsTurn && timestamp(incoming) < timestamp(existing)) return existing
        if (keepsNativeContent(existing, incoming)) {
            // The alert is shown independently. Keep the native stream alive until its
            // terminal event/full snapshot arrives; a preview is not a stream base.
            return if (confirmsTurn && timestamp(incoming) > timestamp(existing)) existing.copy(
                snapshot = existing.snapshot.copy(completed_at = incoming.snapshot.completed_at)) else existing
        }
        if (existing.hasFullSnapshot && ref != null && ref == existing.snapshot.remote_ref) return existing
        return existing.copy(receivedAt = incoming.receivedAt, identity = incoming.identity, snapshot = incoming.snapshot,
            attachmentUrl = incoming.attachmentUrl, attachmentExpiresAt = incoming.attachmentExpiresAt,
            hasFullSnapshot = incoming.hasFullSnapshot,
            remoteState = if (confirmsTurn && event!!.status !in setOf("completed", "interrupted", "failed"))
                existing.remoteState?.copy(event = event.copy(status = "completed"), transport = "received") else existing.remoteState)
    }

    fun mergeDownloaded(existing: TaskInboxRecord, downloaded: TaskInboxRecord): TaskInboxRecord {
        require(existing.id == downloaded.id && existing.endpointHash == downloaded.endpointHash)
        if (ConversationSnapshotRules.regresses(existing, downloaded.snapshot)) return existing
        if (keepsNativeContent(existing, downloaded)) return existing
        // Downloading an older completion snapshot must not erase a mobile send or reply.
        val terminalReply = existing.remoteState?.event?.status in setOf("completed", "interrupted")
        val matchingFullDownload = !existing.hasFullSnapshot && downloaded.hasFullSnapshot && existing.snapshot.remote_ref != null &&
            existing.snapshot.remote_ref == downloaded.snapshot.remote_ref
        return if (terminalReply && !matchingFullDownload) existing else existing.copy(
            snapshot = downloaded.snapshot, hasFullSnapshot = downloaded.hasFullSnapshot)
    }

    fun mergeSynced(existing: TaskInboxRecord?, synced: TaskInboxRecord): TaskInboxRecord {
        if (existing == null) return synced
        require(sameConversation(existing, synced))
        if (ConversationSnapshotRules.regresses(existing, synced.snapshot, nativeRewrite = true)) return existing
        val confirmed = confirmedCompletion(existing, synced)
        val sameCompletedTurn = synced.snapshot.remote_ref?.baseline_turn?.let {
            it == existing.snapshot.remote_ref?.baseline_turn || it == existing.remoteState?.event?.turn_id
        } == true
        // Provider thread timestamps have second precision; a matching terminal turn is authoritative.
        val terminalWithoutTime = existing.remoteState?.event?.status in setOf("completed", "interrupted") && timestamp(existing) == 0L
        val knownLaterTurn = timestamp(synced) > 0 && timestamp(synced) + 1000 >= (existing.remoteState?.event?.at ?: Long.MAX_VALUE)
        val providerAdvanced = synced.snapshot.messages.any { it.id.isNotBlank() } &&
            timestamp(synced) > timestamp(existing) && existing.remoteState?.command?.issued_at?.let { timestamp(synced) > it + 1000 } == true &&
            ConversationSnapshotRules.turn(synced.snapshot) != ConversationSnapshotRules.turn(existing.snapshot)
        if (hasPendingTurn(existing) && confirmed == null && !providerAdvanced || !sameCompletedTurn && (timestamp(existing) > timestamp(synced) || terminalWithoutTime && !knownLaterTurn)) return existing
        // Preserve already read pages, but restart the cursor at the new tail: a desktop
        // session may have advanced several pages while the phone was closed.
        // Refresh known items in place: a previously regressed tail must not move an
        // older turn after a newer one and reverse the order used by rollback guards.
        val native = existing.snapshot.messages.filter { it.id.isNotBlank() }
        val replacements = native.associateBy { it.id }
        val knownIds = existing.historyMessages.map { it.id }.toSet()
        var history = existing.historyMessages.map { replacements[it.id] ?: it } + native.filter { it.id !in knownIds }
        var limited = existing.historyLimited
        while (history.size > 500 || history.sumOf { it.text.toByteArray().size } > 1_000_000) {
            history = history.drop(1); limited = true
        }
        return existing.copy(snapshot = synced.snapshot, hasFullSnapshot = true,
            attachmentUrl = null, attachmentExpiresAt = 0, historyMessages = history, historyCursor = null, historyLimited = limited,
            remoteState = confirmed ?: existing.remoteState)
    }

    /** Only an authenticated native terminal snapshot of the exact active turn can
     * release a missed completion event. Timeouts, old history and unknown sends cannot. */
    private fun confirmedCompletion(existing: TaskInboxRecord, synced: TaskInboxRecord): RemoteConversationState? {
        val state = existing.remoteState ?: return null
        val event = state.event ?: return null
        val ref = synced.snapshot.remote_ref ?: return null
        if (state.command.action !in setOf("send", "create") || synced.snapshot.running ||
            event.request_id != state.command.id || event.host_id != state.command.host_id ||
            event.thread_id != state.command.thread_id || event.conversation_id != state.command.conversation_id ||
            event.status !in setOf("running", "accepted", "approval", "input_required") || event.attachment_pending ||
            event.turn_id.isBlank() || event.turn_id != ref.baseline_turn || ref.host_id != state.command.host_id ||
            (state.command.action == "send" && ref.thread_id != state.command.thread_id) ||
            (state.command.action == "create" && ref.thread_id != existing.snapshot.thread_ref?.thread_id) ||
            existing.followUps.any { FollowUpState.pending(it) } || event.queued_entries.isNotEmpty()) return null
        return state.copy(event = event.copy(status = "completed", reply = synced.snapshot.reply,
            approval_id = "", approval_text = "", user_input = null, reply_patch = null), transport = "received")
    }

    private fun groups(records: List<TaskInboxRecord>) = records
        .groupBy { it.endpointHash to it.snapshot.conversation_id.ifBlank { it.id } }
        .values.flatMap { group ->
            if (group.map(::host).filter { it.isNotBlank() }.distinct().size <= 1) listOf(group) else
                group.groupBy { host(it).ifBlank { it.id } }.values
        }

    /** Recovery prioritizes pending requests; screen selection prioritizes current content. */
    fun latestForDisplay(records: List<TaskInboxRecord>): List<TaskInboxRecord> = groups(records).map { group ->
        val newest = group.maxWith(compareBy<TaskInboxRecord> { timestamp(it) }
            .thenBy { it.snapshot.messages.any { message -> message.id.isNotBlank() } }
            .thenBy { it.hasFullSnapshot }.thenBy { it.contentValidatedAt }.thenBy { it.receivedAt })
        val currentPending = group.filter { hasPendingTurn(it) &&
            it.remoteState!!.command.issued_at >= timestamp(newest) &&
                ConversationSnapshotRules.currentTask(newest, it.remoteState) }
            .maxByOrNull { it.remoteState!!.command.issued_at }
        currentPending ?: newest
    }.sortedByDescending(::timestamp)

    fun latest(records: List<TaskInboxRecord>): List<TaskInboxRecord> = groups(records).map { group ->
            // A completion notification can arrive before the remote terminal event.
            // Keep that pending request reachable until it has a confirmed outcome.
            group.maxWith(compareBy<TaskInboxRecord> { hasPendingTurn(it) }.thenBy { timestamp(it) }.thenBy { it.receivedAt })
        }.sortedByDescending(::timestamp)
}
