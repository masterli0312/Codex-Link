package com.codex.quota

import com.codex.quota.notifications.task.*
import org.junit.Assert.*
import org.junit.Test

class ConversationIndexTest {
    @Test fun anAuthenticatedCompletedWatchReleasesTheSamePendingTurn() {
        val ref = RemoteThreadRef("host", "thread", "running-turn")
        val command = RemoteCommand("request", "send", "host", "thread", "chat", "previous", 1000, "continue")
        val running = RemoteEvent("event", "request", "host", "thread", "chat", "running", 4, 2000,
            reply = "partial", turn_id = ref.baseline_turn)
        val current = record("current").copy(remoteState = RemoteConversationState(command, running),
            snapshot = TaskConversationSnapshot(conversation_id = "chat", running = true, thread_ref = ref))
        val synced = current.copy(snapshot = current.snapshot.copy(running = false, remote_ref = ref,
            reply = "complete", messages = listOf(TaskConversationMessage("assistant", "complete", "running-turn:a")),
            completed_at = "1970-01-01T00:00:03Z"))
        val merged = ConversationIndex.mergeSynced(current, synced)
        assertFalse(ConversationIndex.hasPendingTurn(merged))
        assertFalse(merged.snapshot.running)
        assertEquals("complete", merged.snapshot.reply)
        assertEquals("completed", merged.remoteState?.event?.status)
        assertEquals("received", merged.remoteState?.transport)
        assertEquals(current, ConversationIndex.mergeSynced(current, synced.copy(snapshot = synced.snapshot.copy(remote_ref = ref.copy(baseline_turn = "previous")))))
        assertEquals(current, ConversationIndex.mergeSynced(current, synced.copy(snapshot = synced.snapshot.copy(remote_ref = ref.copy(host_id = "other")))))
        assertEquals(current, ConversationIndex.mergeSynced(current, synced.copy(snapshot = synced.snapshot.copy(running = true))))
        val unconfirmed = current.copy(remoteState = current.remoteState?.copy(event = null))
        assertEquals(unconfirmed, ConversationIndex.mergeSynced(unconfirmed, synced))
    }
    @Test fun openingAnUnreadComputerThreadKeepsItsRealIdentityWithoutEnablingSend() {
        val host = "11111111-2222-3333-4444-555555555555"
        val thread = RemoteThreadSummary("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", "actual title", updated_at = 5000)
        val computer = ComputerConnection("pair", host, "computer", "https://example.test/topic-a", "key", 0)
        val unread = ConversationIndex.unreadThread(computer, thread, false)
        assertEquals(thread.title, unread.snapshot.title)
        assertEquals(thread.thread_id, unread.snapshot.thread_ref?.thread_id)
        assertEquals(host, unread.snapshot.thread_ref?.host_id)
        assertNull(unread.snapshot.remote_ref)
        assertFalse(unread.hasFullSnapshot)
        assertEquals(5000L, ConversationIndex.timestamp(unread))
        assertEquals(unread.id, ConversationIndex.unreadThread(computer, thread, false).id)
        assertNotEquals(unread.id, ConversationIndex.unreadThread(computer.copy(endpoint = "https://example.test/topic-b"), thread, false).id)
        assertTrue(ConversationIndex.unreadThread(computer, thread, true).archived)
    }
    private fun record(id: String, conversation: String = "chat", pairing: String = "pair", at: Long = 1000) =
        TaskInboxRecord(id, at, id, pairing, TaskConversationSnapshot(conversation_id = conversation, title = id))

    @Test fun notificationBeforeNativeCompletionKeepsTheStreamingReplyAndHistory() {
        val command = RemoteCommand("request", "send", "host", "thread", "chat", "old-turn", 2000, "continue")
        val event = RemoteEvent("event", "request", "host", "thread", "chat", "running", 4, 4000,
            reply = "new streaming reply", turn_id = "new-turn")
        val oldMessage = TaskConversationMessage("assistant", "old greeting", "old-turn:assistant")
        val current = record("stable").copy(hasFullSnapshot = true, remoteState = RemoteConversationState(command, event),
            snapshot = TaskConversationSnapshot(conversation_id = "chat", messages = listOf(oldMessage),
                remote_ref = RemoteThreadRef("host", "thread", "old-turn")))
        val notification = record("notification").copy(snapshot = TaskConversationSnapshot(conversation_id = "chat",
            reply = "new final reply", remote_ref = RemoteThreadRef("host", "thread", "new-turn"),
            completed_at = "1970-01-01T00:00:05Z"), attachmentUrl = "https://example.test/legacy")
        val merged = ConversationIndex.mergeNotification(current, notification, true)
        assertEquals(current.remoteState, merged.remoteState)
        assertEquals(current.snapshot.messages, merged.snapshot.messages)
        assertEquals(current.snapshot.remote_ref, merged.snapshot.remote_ref)
        assertEquals(current.identity, merged.identity)
        assertTrue(merged.hasFullSnapshot)
        assertNull(merged.attachmentUrl)
        assertEquals(5000L, ConversationIndex.timestamp(merged))
        // An already in-flight legacy download cannot take over this native stream either.
        val downloaded = notification.copy(id = current.id, hasFullSnapshot = true,
            snapshot = notification.snapshot.copy(messages = listOf(TaskConversationMessage("assistant", "old greeting"))))
        assertEquals(merged, ConversationIndex.mergeDownloaded(merged, downloaded))
        val native = current.copy(snapshot = current.snapshot.copy(remote_ref = notification.snapshot.remote_ref,
            messages = listOf(TaskConversationMessage("assistant", "new final reply", "new-turn:assistant")),
            completed_at = notification.snapshot.completed_at),
            remoteState = RemoteConversationState(command, event.copy(status = "completed", reply = "new final reply")))
        assertEquals(native, ConversationIndex.mergeNotification(native, notification, true))
    }

    @Test fun aNotificationForALaterDesktopTurnStillUpdatesAnIdleNativeCache() {
        val command = RemoteCommand("request", "send", "host", "thread", "chat", "baseline", 1000, "continue")
        val event = RemoteEvent("event", "request", "host", "thread", "chat", "completed", 4, 1000,
            reply = "old reply", turn_id = "old-turn")
        val current = record("stable").copy(hasFullSnapshot = true,
            remoteState = RemoteConversationState(command, event),
            snapshot = TaskConversationSnapshot(conversation_id = "chat", remote_ref = RemoteThreadRef("host", "thread", "old-turn"),
                messages = listOf(TaskConversationMessage("assistant", "old reply", "old-turn:assistant")),
                completed_at = "1970-01-01T00:00:01Z"))
        val incoming = record("notification").copy(snapshot = TaskConversationSnapshot(conversation_id = "chat",
            remote_ref = RemoteThreadRef("host", "thread", "later-turn"), reply = "new desktop reply",
            completed_at = "1970-01-01T00:00:05Z"))
        val merged = ConversationIndex.mergeNotification(current, incoming, true)
        assertEquals("new desktop reply", merged.snapshot.reply)
        val full = merged.copy(hasFullSnapshot = true, snapshot = merged.snapshot.copy(
            messages = listOf(TaskConversationMessage("assistant", "new desktop reply"))))
        assertEquals(full.snapshot, ConversationIndex.mergeDownloaded(merged, full).snapshot)
    }

    @Test fun repeatedLiveTailsPreserveHistoryAndReplacePartialTextByIdentity() {
        val old = TaskConversationMessage("assistant", "previous turn", "old:a")
        val partial = TaskConversationMessage("assistant", "par", "live:a")
        val full = partial.copy(text = "partial completed")
        val current = record("current").copy(selectedModel = "model", selectedEffort = "high",
            snapshot = TaskConversationSnapshot(conversation_id = "chat", messages = listOf(old)))
        val first = current.copy(snapshot = current.snapshot.copy(messages = listOf(partial), running = true))
        val merged = ConversationIndex.mergeSynced(current, first)
        val completed = first.copy(snapshot = first.snapshot.copy(messages = listOf(full), running = false))
        val final = ConversationIndex.mergeSynced(merged, completed)
        val repeated = ConversationIndex.mergeSynced(final, completed)
        assertEquals(listOf(old, full), ConversationHistory.merge(repeated.historyMessages, repeated.snapshot.messages))
        assertEquals("model", repeated.selectedModel)
        assertEquals("high", repeated.selectedEffort)
        assertFalse(repeated.snapshot.running)
    }

    @Test fun aHiddenHistoricalDuplicateCannotHideTheCurrentComputerTitle() {
        val thread = RemoteThreadSummary("11111111-2222-3333-4444-555555555555", "Codex Usage activity test")
        val old = record("Codex Usage", conversation = TaskInbox.hash(thread.thread_id), at = 1000)
        val current = old.copy(id = "current", receivedAt = 2000, snapshot = old.snapshot.copy(title = "renamed"))
        assertEquals(listOf(thread), ConversationIndex.uncoveredThreads(listOf(thread), listOf(old, current), "pair", "Codex Usage", false))
    }

    @Test fun aNewSnapshotKeepsReadHistoryAndRestartsPagingFromTheCurrentTail() {
        val older = TaskConversationMessage("user", "old history", "1:u")
        val previous = TaskConversationMessage("assistant", "previous reply", "2:a")
        val newest = TaskConversationMessage("assistant", "new reply", "8:a")
        val current = record("current").copy(historyMessages = listOf(older), historyCursor = "old cursor",
            snapshot = TaskConversationSnapshot(conversation_id = "chat", messages = listOf(previous), completed_at = "1970-01-01T00:00:02Z"))
        val fresh = record("fresh").copy(snapshot = TaskConversationSnapshot(conversation_id = "chat", messages = listOf(newest),
            completed_at = "1970-01-01T00:00:08Z", history_cursor = "fresh cursor"))
        val merged = ConversationIndex.mergeSynced(current, fresh)
        assertEquals(listOf(older, previous, newest), ConversationHistory.merge(merged.historyMessages, merged.snapshot.messages))
        assertNull(merged.historyCursor)
        assertEquals("fresh cursor", merged.snapshot.history_cursor)
    }

    @Test fun aConfirmedLaterDesktopTurnCanReplaceAMobileReplyWithUnknownProviderTime() {
        val command = RemoteCommand("request", "send", "host", "thread", "chat", "baseline", 2000, "continue")
        val event = RemoteEvent("event", "request", "host", "thread", "chat", "completed", 1, 5000, turn_id = "mobile-done")
        val current = record("current").copy(remoteState = RemoteConversationState(command, event))
        val fresh = record("fresh").copy(snapshot = TaskConversationSnapshot(conversation_id = "chat", reply = "new desktop reply",
            completed_at = "1970-01-01T00:00:09Z", remote_ref = RemoteThreadRef("host", "thread", "desktop-done")))
        assertEquals("new desktop reply", ConversationIndex.mergeSynced(current, fresh).snapshot.reply)
        assertEquals(current, ConversationIndex.mergeSynced(current, fresh.copy(snapshot = fresh.snapshot.copy(completed_at = "1970-01-01T00:00:01Z"))))
    }

    @Test fun anOutdatedCachedTitleCannotHideARealComputerSearchMatch() {
        val thread = RemoteThreadSummary("11111111-2222-3333-4444-555555555555", "Codex Usage activity test", updated_at = 5000)
        val stale = record("Old title", conversation = TaskInbox.hash(thread.thread_id))
        assertFalse(ConversationIndex.matchesSearch(stale, "Codex Usage"))
        assertEquals(listOf(thread), ConversationIndex.uncoveredThreads(listOf(thread), listOf(stale), "pair", "Codex Usage", false))
        val visible = stale.copy(snapshot = stale.snapshot.copy(title = thread.title))
        assertTrue(ConversationIndex.uncoveredThreads(listOf(thread), listOf(visible), "pair", "codex usage", false).isEmpty())
    }

    @Test fun catalogDedupPreservesPairingIsolationAndArchivedResults() {
        val thread = RemoteThreadSummary("11111111-2222-3333-4444-555555555555", "Codex Usage", updated_at = 5000)
        val other = record("Codex Usage", conversation = TaskInbox.hash(thread.thread_id), pairing = "other-pair")
        assertEquals(listOf(thread), ConversationIndex.uncoveredThreads(listOf(thread), listOf(other), "pair", "", false))
        val same = other.copy(endpointHash = "pair")
        assertTrue(ConversationIndex.uncoveredThreads(listOf(thread), listOf(same), "pair", "", false).isEmpty())
        assertEquals(listOf(thread), ConversationIndex.uncoveredThreads(listOf(thread), listOf(same), "pair", "", true))
        assertEquals(listOf(thread), ConversationIndex.uncoveredThreads(listOf(thread), listOf(same.copy(archived = true)), "pair", "", false))
    }

    @Test fun separatePairingsAndUnnamedConversationsAreNeverMerged() {
        val records = listOf(record("a"), record("b", pairing = "other"), record("c", conversation = ""), record("d", conversation = ""))
        assertEquals(4, ConversationIndex.latest(records).size)
        assertFalse(ConversationIndex.sameConversation(records[0], records[1]))
    }

    @Test fun mobileReplyUpdatesRecencyAndReplacesTheOlderConversationEntry() {
        val old = record("old")
        val mobile = record("mobile").copy(snapshot = TaskConversationSnapshot(conversation_id = "chat", completed_at = "1970-01-01T00:00:05Z"))
        val another = record("other", conversation = "another", at = 3000).copy(snapshot = TaskConversationSnapshot(conversation_id = "another", completed_at = "1970-01-01T00:00:03Z"))
        val latest = ConversationIndex.latest(listOf(old, another, mobile))
        assertEquals(listOf("mobile", "other"), latest.map { it.id })
        assertTrue(ConversationIndex.sameConversation(old, mobile))
    }

    @Test fun pendingRemoteTurnStaysReachableUntilItsConfirmedCompletion() {
        val command = RemoteCommand("request", "send", "host", "thread", "chat", "baseline", 2000, "continue")
        val pending = record("pending").copy(remoteState = RemoteConversationState(command))
        val notification = record("notification", at = 4000)
        assertEquals("pending", ConversationIndex.latest(listOf(notification, pending)).single().id)
        val event = RemoteEvent("event", "request", "host", "thread", "chat", "completed", 1, 5000)
        val completed = pending.copy(snapshot = pending.snapshot.copy(completed_at = "1970-01-01T00:00:05Z"), remoteState = RemoteConversationState(command, event))
        assertEquals("pending", ConversationIndex.latest(listOf(notification, completed)).single().id)
        assertEquals(5000, ConversationIndex.timestamp(completed))
        assertFalse(ConversationIndex.hasPendingTurn(completed))
    }

    @Test fun lateSnapshotDownloadDoesNotEraseAMobileRequestOrItsReply() {
        val command = RemoteCommand("request", "send", "host", "thread", "chat", "baseline", 2000, "continue")
        val downloaded = record("same").copy(hasFullSnapshot = true)
        val pending = record("same").copy(remoteState = RemoteConversationState(command))
        assertEquals(pending.remoteState, ConversationIndex.mergeDownloaded(pending, downloaded).remoteState)
        assertTrue(ConversationIndex.mergeDownloaded(pending, downloaded).hasFullSnapshot)
        val event = RemoteEvent("event", "request", "host", "thread", "chat", "completed", 1, 5000, reply = "latest reply")
        val replied = pending.copy(snapshot = pending.snapshot.copy(reply = event.reply, completed_at = "1970-01-01T00:00:05Z"), remoteState = RemoteConversationState(command, event))
        assertEquals(replied, ConversationIndex.mergeDownloaded(replied, downloaded))
    }

    @Test fun syncCannotReplaceAPendingTurnOrANewerReply() {
        val command = RemoteCommand("request", "send", "host", "thread", "chat", "baseline", 2000, "continue")
        val pending = record("same").copy(remoteState = RemoteConversationState(command))
        val synced = record("cache").copy(snapshot = TaskConversationSnapshot(conversation_id = "chat", completed_at = "1970-01-01T00:00:01Z"), hasFullSnapshot = true)
        assertEquals(pending, ConversationIndex.mergeSynced(pending, synced))
        val event = RemoteEvent("event", "request", "host", "thread", "chat", "completed", 1, 5000, reply = "latest reply")
        val replied = pending.copy(snapshot = pending.snapshot.copy(reply = event.reply, completed_at = "1970-01-01T00:00:05Z"), remoteState = RemoteConversationState(command, event))
        assertEquals(replied, ConversationIndex.mergeSynced(replied, synced))
        assertEquals(synced, ConversationIndex.mergeSynced(null, synced))
        val newer = synced.copy(snapshot = synced.snapshot.copy(completed_at = "1970-01-01T00:00:09Z", reply = "new desktop reply"))
        assertEquals("same", ConversationIndex.mergeSynced(replied, newer).id)
        assertEquals("new desktop reply", ConversationIndex.mergeSynced(replied, newer).snapshot.reply)
    }

    @Test fun confirmedSameTurnSyncCanRefreshAProviderTimestampRoundedToSeconds() {
        val ref = RemoteThreadRef("host", "thread", "done")
        val command = RemoteCommand("request", "send", "host", "thread", "chat", "baseline", 2000, "continue")
        val event = RemoteEvent("event", "request", "host", "thread", "chat", "completed", 1, 5500, turn_id = "done")
        val existing = record("same").copy(remoteState = RemoteConversationState(command, event))
        val synced = record("cache").copy(snapshot = TaskConversationSnapshot(conversation_id = "chat", remote_ref = ref,
            completed_at = "1970-01-01T00:00:05Z", reply = "fresh visible projection"), hasFullSnapshot = true)
        assertEquals("fresh visible projection", ConversationIndex.mergeSynced(existing, synced).snapshot.reply)
        assertEquals(existing.remoteState, ConversationIndex.mergeSynced(existing, synced).remoteState)
    }

    @Test fun statusDeliveryTimeNeverChangesConversationActivityTime() {
        val command = RemoteCommand("request", "send", "host", "thread", "chat", "baseline", 2000, "continue")
        val event = RemoteEvent("event", "request", "host", "thread", "chat", "completed", 1, 500_000)
        val completed = record("chat").copy(snapshot = TaskConversationSnapshot(conversation_id = "chat", completed_at = "1970-01-01T00:00:05Z"),
            remoteState = RemoteConversationState(command, event))
        assertEquals(5000L, ConversationIndex.timestamp(completed))
        assertEquals(5000L, ConversationIndex.timestamp(completed.copy(remoteState = RemoteConversationState(command, event.copy(at = 999_000)))))
    }

    @Test fun missingActivityTimeNeverUsesArrivalOrSendTime() {
        val command = RemoteCommand("request", "send", "host", "thread", "chat", "baseline", 999_000, "continue")
        val missing = record("missing", at = 500_000).copy(remoteState = RemoteConversationState(command))
        assertEquals(0L, ConversationIndex.timestamp(missing))
        assertEquals(0L, ConversationIndex.timestamp(missing.copy(snapshot = missing.snapshot.copy(completed_at = "invalid"))))
        assertEquals(0L, ConversationIndex.timestamp(missing.copy(snapshot = missing.snapshot.copy(completed_at = "1970-01-01T00:00:00Z"))))
    }

    @Test fun completionNotificationPreservesTheConversationAndDoesNotResetItsChoices() {
        val ref = RemoteThreadRef("host", "thread", "new-turn")
        val command = RemoteCommand("request", "send", "host", "thread", "chat", "baseline", 2000, "continue")
        val running = RemoteEvent("event", "request", "host", "thread", "chat", "running", 1, 2500, turn_id = "new-turn")
        val current = record("stable").copy(remoteState = RemoteConversationState(command, running), selectedModel = "model", selectedEffort = "high")
        val completion = record("new-notification").copy(snapshot = TaskConversationSnapshot(conversation_id = "chat", remote_ref = ref,
            completed_at = "1970-01-01T00:00:05Z"))
        val merged = ConversationIndex.mergeNotification(current, completion, true)
        assertEquals("stable", merged.id)
        assertEquals("model", merged.selectedModel)
        assertEquals("high", merged.selectedEffort)
        assertEquals("completed", merged.remoteState?.event?.status)
        assertEquals(5000L, ConversationIndex.timestamp(merged))
        assertEquals("running", ConversationIndex.mergeNotification(current, completion.copy(snapshot = completion.snapshot.copy(
            remote_ref = ref.copy(baseline_turn = "different"))), true).remoteState?.event?.status)
    }
}
