package com.codex.quota

import com.codex.quota.notifications.task.*
import org.junit.Assert.*
import org.junit.Test

class ComputerConnectionTest {
    private val host = "11111111-2222-3333-4444-555555555555"
    private val thread = "44444444-2222-3333-4444-555555555555"
    @Test fun historicalBridgeIdsDoNotEraseTheVerifiedComputerPairing() {
        val remembered = ComputerConnection("legacy", host, "Desktop", "https://ntfy.sh/first", "key", 0,
            legacy = true, lastReachableAt = 1000, reachable = false)
        assertEquals(host, ComputerConnectionStore.legacyHost(listOf(host, "old-installation"), remembered))
        assertEquals(host, ComputerConnectionStore.legacyHost(listOf("old-installation"), remembered))
        assertEquals("", ComputerConnectionStore.legacyHost(listOf(host, "old-installation"), null))
        assertEquals(host, ComputerConnectionStore.legacyHost(listOf(host), null))
    }
    @Test fun sameThreadIdOnDifferentComputersCannotSelectTheWrongKeyOrEndpoint() {
        val first = ComputerConnection(host, host, "Desktop", "https://ntfy.sh/first", "private-key", 0)
        val record = TaskInboxRecord("r", 1, "n", TaskInbox.hash(first.endpoint),
            TaskConversationSnapshot(conversation_id = TaskInbox.hash(thread), remote_ref = RemoteThreadRef(host, thread, "t")))
        assertTrue(first.matches(record))
        val live = record.copy(snapshot = record.snapshot.copy(remote_ref = null,
            thread_ref = RemoteThreadRef(host, thread, "running"), running = true))
        assertTrue(first.matches(live))
        assertFalse(first.copy(hostId = "other-host").matches(live))
        assertFalse(first.copy(hostId = "other-host").matches(record))
        assertFalse(first.copy(endpoint = "https://ntfy.sh/second").matches(record))
        val anchor = record.copy(snapshot = TaskConversationSnapshot(), computerHostId = host, libraryAnchor = true)
        assertTrue(first.matches(anchor))
        assertFalse(first.copy(hostId = "other-host").matches(anchor))
        assertFalse(first.toString().contains(first.key))
        assertFalse(first.toString().contains(first.endpoint))
    }
    @Test fun relayConnectivityDoesNotProveComputerPresenceAndOldLeaseExpires() {
        val connection = ComputerConnection(host, host, "Desktop", "https://ntfy.sh/first", "key", 0)
        assertFalse(connection.recentlyReachable(1000))
        val verified = connection.copy(reachable = true, lastReachableAt = 1000)
        assertTrue(verified.recentlyReachable(2000))
        assertFalse(verified.recentlyReachable(62_000))
        assertFalse(verified.recentlyReachable(999))
    }
    @Test fun checkingDoesNotDisconnectAnActuallyReachableComputer() {
        val verified = ComputerConnection(host, host, "Desktop", "https://ntfy.sh/first", "key", 0,
            reachable = true, lastReachableAt = 1000)
        val checking = verified.startProbe("probe", 20_000)
        assertTrue(checking.checking)
        assertTrue(checking.recentlyReachable(20_000))
        val temporaryFailure = checking.endProbe("probe", null, 25_000)
        assertFalse(temporaryFailure.checking)
        assertTrue(temporaryFailure.recentlyReachable(25_000))
        assertFalse(checking.endProbe("probe", null, 62_000).recentlyReachable(62_000))
        assertEquals(checking, checking.endProbe("old-probe", "wrong-name", 25_000))
        assertTrue(checking.endProbe("probe", "Desktop", 80_000).recentlyReachable(80_000))
    }
    @Test fun clonedConversationOnAnotherHostKeepsAnIndependentCacheEntry() {
        val first = TaskInboxRecord("a", 1000, "n", "same-legacy-pair", TaskConversationSnapshot(conversation_id = "same-thread",
            remote_ref = RemoteThreadRef(host, thread, "turn")))
        val second = first.copy(id = "b", snapshot = first.snapshot.copy(remote_ref = first.snapshot.remote_ref!!.copy(host_id = "other-host")))
        assertFalse(ConversationIndex.sameConversation(first, second))
        assertEquals(2, ConversationIndex.latest(listOf(first, second)).size)
    }
    @Test fun freshAuthenticatedWatchResponseClearsOldFailedPresenceWithoutCrossingHosts() {
        val connection = ComputerConnection(host, host, "Desktop", "https://ntfy.sh/first", "key", 0,
            lastProbeAt = 1_000, reachable = false)
        val event = RemoteEvent("event", "watch", host, thread, "conversation", "snapshot", 1, 20_000)
        assertTrue(connection.confirmedUnreachable(20_000))
        val observed = connection.observe(event, 20_100)
        assertTrue(observed.recentlyReachable(20_100))
        assertFalse(observed.confirmedUnreachable(20_100))
        assertEquals(connection, connection.observe(event.copy(host_id = "other-host"), 20_100))
        assertEquals(connection, connection.observe(event.copy(at = 0), 40_000))
        assertEquals(connection, connection.observe(event.copy(at = 21_000), 20_100))
    }
    @Test fun failedProbeCannotOverrideAResponseReceivedDuringTheProbe() {
        val connection = ComputerConnection(host, host, "Desktop", "https://ntfy.sh/first", "key", 0)
            .startProbe("probe", 20_000)
        val event = RemoteEvent("event", "watch", host, thread, "conversation", "snapshot", 1, 25_000)
        val observed = connection.observe(event, 25_000).endProbe("probe", null, 26_000)
        assertTrue(observed.recentlyReachable(26_000))
        assertFalse(observed.confirmedUnreachable(26_000))
        assertFalse(observed.confirmedUnreachable(90_000))
    }
}
