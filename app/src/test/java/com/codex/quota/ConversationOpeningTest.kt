package com.codex.quota

import com.codex.quota.notifications.task.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class ConversationOpeningTest {
    private val host = "11111111-2222-3333-4444-555555555555"
    private val thread = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
    private val computer = ComputerConnection("pair", host, "Desktop", "https://example.test/pair", "key", 0)
    private fun record(id: String, at: Long) = ConversationIndex.unreadThread(computer,
        RemoteThreadSummary(thread, "same title", updated_at = at), false).copy(id = id, receivedAt = at,
        snapshot = TaskConversationSnapshot(conversation_id = TaskInbox.hash(thread), title = "same title",
            remote_ref = RemoteThreadRef(host, thread, "turn-$at"), reply = "reply-$at",
            completed_at = Instant.ofEpochMilli(at).toString()))
    private fun pending(id: String, at: Long) = record(id, at).copy(remoteState = RemoteConversationState(
        RemoteCommand("request", "send", host, thread, TaskInbox.hash(thread), "turn-$at", at)))

    @Test fun oldUnconfirmedTaskCannotReplaceNewerConversationOnOpen() {
        val old = pending("old", 1000)
        val fresh = record("fresh", 8000)
        assertEquals("old", ConversationIndex.latest(listOf(old, fresh)).single().id)
        assertEquals("fresh", ConversationIndex.latestForDisplay(listOf(old, fresh)).single().id)
        assertTrue(ConversationIndex.hasPendingTurn(old)) // Recovery is still available.
    }
    @Test fun genuinelyNewPendingMessageRemainsTheCurrentConversation() {
        val previous = record("previous", 8000)
        val active = pending("active", 9000)
        assertEquals("active", ConversationIndex.latestForDisplay(listOf(previous, active)).single().id)
    }
    @Test fun matchingTitleDoesNotAllowAnotherThreadOrComputer() {
        val current = record("current", 8000)
        assertTrue(ConversationIndex.matchesThread(current, computer, thread))
        assertFalse(ConversationIndex.matchesThread(current, computer, "another-thread"))
        assertFalse(ConversationIndex.matchesThread(current, computer.copy(hostId = "another-host"), thread))
        assertFalse(ConversationIndex.matchesThread(current, computer.copy(endpoint = "https://example.test/other"), thread))
        assertFalse(ConversationIndex.matchesThread(current.copy(snapshot = current.snapshot.copy(conversation_id = "wrong-id")), computer, thread))
        assertNotEquals(ConversationIndex.unreadThread(computer, RemoteThreadSummary(thread, "same title"), false).id,
            ConversationIndex.unreadThread(computer.copy(hostId = "22222222-2222-3333-4444-555555555555"), RemoteThreadSummary(thread, "same title"), false).id)
    }
    @Test fun displaySelectionStillSeparatesIdenticalThreadIdsAcrossComputers() {
        val first = record("first", 8000)
        val second = first.copy(id = "second", snapshot = first.snapshot.copy(remote_ref = first.snapshot.remote_ref!!.copy(host_id = "another-host")))
        assertEquals(2, ConversationIndex.latestForDisplay(listOf(first, second)).size)
    }
    @Test fun roundedProviderTimeDoesNotGiveAnOldTaskDisplayPriority() {
        val old = pending("old", 8000).copy(remoteState = pending("old", 8000).remoteState!!.copy(
            event = RemoteEvent("event", "request", host, thread, TaskInbox.hash(thread), "running", 1, 9000, turn_id = "turn-8000")))
        val fresh = record("fresh", 8000).copy(snapshot = record("fresh", 8000).snapshot.copy(remote_ref = RemoteThreadRef(host, thread, "latest")),
            historyMessages = listOf(TaskConversationMessage("assistant", "old", "turn-8000:a")),
            hasFullSnapshot = true)
        assertEquals("fresh", ConversationIndex.latestForDisplay(listOf(old, fresh)).single().id)
    }
    @Test fun onlineOpeningWaitsForFreshNativeContentButOfflineCacheRemainsReadable() {
        val cached = record("cached", 8000)
        assertFalse(ConversationOpeningRules.contentReady(cached, 100_000, true))
        assertTrue(ConversationOpeningRules.contentReady(cached, 100_000, false))
        assertTrue(ConversationOpeningRules.contentReady(cached.copy(contentValidatedAt = 99_000), 100_000, true))
        assertFalse(ConversationOpeningRules.contentReady(null, 100_000, false))
        assertTrue(ConversationOpeningRules.contentReady(pending("create", 99_000), 100_000, true))
    }
    @Test fun recentNotificationReceiptCannotWinOverValidatedNativeCacheAtTheSameProviderTime() {
        val native = record("native", 8000).copy(hasFullSnapshot = true, contentValidatedAt = 10_000)
        val delayed = native.copy(id = "delayed", receivedAt = 20_000, contentValidatedAt = 0)
        assertEquals("native", ConversationIndex.latestForDisplay(listOf(native, delayed)).single().id)
    }
    @Test fun switchingCacheRecordsCannotRepaintAnEarlierTurnAfterTheScreenIsCurrent() {
        val current = record("new", 8000)
        val delayed = record("old", 1000).copy(receivedAt = 20_000)
        assertEquals(current, ConversationOpeningRules.display(current, delayed))
        assertEquals(current, ConversationOpeningRules.display(current, null))
        val newer = record("next", 10_000)
        assertEquals(newer, ConversationOpeningRules.display(current, newer))
    }
}
