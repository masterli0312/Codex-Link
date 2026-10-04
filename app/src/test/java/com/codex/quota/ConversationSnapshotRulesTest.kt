package com.codex.quota

import com.codex.quota.notifications.task.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class ConversationSnapshotRulesTest {
    private fun snapshot(turn: String, at: Long, reply: String) = TaskConversationSnapshot(conversation_id = "chat", reply = reply,
        completed_at = Instant.ofEpochMilli(at).toString(), remote_ref = RemoteThreadRef("host", "thread", turn),
        messages = listOf(TaskConversationMessage("assistant", reply, "$turn:assistant")))
    private val old = snapshot("old", 1000, "old reply")
    private val latest = snapshot("new", 10_000, "new complete reply")
    private fun record(snapshot: TaskConversationSnapshot) = TaskInboxRecord("record", 1000, "identity", "pair", snapshot,
        historyMessages = old.messages, hasFullSnapshot = true)
    private fun oldState() = RemoteConversationState(RemoteCommand("request", "send", "host", "thread", "chat", "old", 2000),
        RemoteEvent("event", "request", "host", "thread", "chat", "running", 20, 20_000, reply = "old reply", turn_id = "old"))

    @Test fun delayedStatusCannotRepaintACompletedEarlierTurn() {
        val current = record(latest)
        assertTrue(ConversationSnapshotRules.regresses(current, old))
        assertFalse(ConversationSnapshotRules.currentTask(current, oldState()))
        assertTrue(ConversationSnapshotRules.regresses(current, old.copy(completed_at = latest.completed_at)))
    }
    @Test fun alternatingNativeAndOldDownloadRepliesNeverRollBackTheTranscript() {
        val initial = record(old).copy(remoteState = oldState())
        val watched = record(latest)
        var current = ConversationIndex.mergeSynced(initial, watched)
        assertEquals("new complete reply", current.snapshot.reply)
        assertEquals(initial.remoteState, current.remoteState)
        repeat(3) {
            current = ConversationIndex.mergeDownloaded(current, record(old).copy(receivedAt = 30_000))
            current = ConversationIndex.mergeNotification(current, record(old).copy(receivedAt = 40_000), true)
            current = ConversationIndex.mergeSynced(current, watched)
            assertEquals("new complete reply", current.snapshot.reply)
        }
    }
    @Test fun aRealNextTurnStillUpdatesAndKeepsItsControls() {
        val current = record(latest)
        val next = snapshot("next", 15_000, "next reply")
        assertFalse(ConversationSnapshotRules.regresses(current, next))
        val state = oldState().copy(command = oldState().command.copy(issued_at = 14_000),
            event = oldState().event!!.copy(turn_id = "next"))
        assertTrue(ConversationSnapshotRules.currentTask(current, state))
    }
    @Test fun nativeReplyDoesNotAlternateWithShorterTaskStatusPreview() {
        val current = record(latest)
        val state = oldState().copy(event = oldState().event!!.copy(turn_id = "new", reply = "new"))
        assertTrue(ConversationSnapshotRules.hasNativeReply(current, state))
        assertTrue(ConversationSnapshotRules.regresses(current, latest.copy(reply = "new", messages = emptyList())))
        assertTrue(ConversationSnapshotRules.regresses(current, latest.copy(reply = "new",
            messages = listOf(TaskConversationMessage("assistant", "new", "new:assistant"))), nativeRewrite = true))
        assertFalse(ConversationSnapshotRules.regresses(current, latest.copy(reply = "new", completed_at = Instant.ofEpochMilli(11_000).toString(),
            messages = listOf(TaskConversationMessage("assistant", "new", "new:assistant"))), nativeRewrite = true))
    }
    @Test fun differentThreadsAndComputersCannotOverwriteTheCurrentSnapshot() {
        val current = record(latest)
        assertTrue(ConversationSnapshotRules.regresses(current, latest.copy(remote_ref = latest.remote_ref!!.copy(thread_id = "other"))))
        assertTrue(ConversationSnapshotRules.regresses(current, latest.copy(remote_ref = latest.remote_ref!!.copy(host_id = "other"))))
    }
    @Test fun newerKnownTurnCanRestoreAPreviouslyRegressedCache() {
        val regressed = record(old).copy(historyMessages = old.messages + latest.messages)
        assertFalse(ConversationSnapshotRules.regresses(regressed, latest, nativeRewrite = true))
        val restored = ConversationIndex.mergeSynced(regressed, record(latest))
        assertEquals("new", ConversationSnapshotRules.turn(restored.snapshot))
        assertTrue(ConversationSnapshotRules.regresses(restored, old))
        assertTrue(ConversationSnapshotRules.regresses(restored, old.copy(completed_at = latest.completed_at)))
    }
    @Test fun reopeningTheSameTurnCannotLoseItsLatestNativeItems() {
        val current = record(latest.copy(messages = latest.messages + TaskConversationMessage("assistant", "latest section", "new:last")))
        assertTrue(ConversationSnapshotRules.regresses(current, latest, nativeRewrite = true))
        val advanced = latest.copy(messages = current.snapshot.messages + TaskConversationMessage("assistant", "more", "new:next"))
        assertFalse(ConversationSnapshotRules.regresses(current, advanced, nativeRewrite = true))
    }
    @Test fun equalTimestampUnknownOldTurnNeedsEvidenceOfProgression() {
        val unknown = snapshot("unknown", 10_000, "older reply outside cached history")
        assertTrue(ConversationSnapshotRules.regresses(record(latest), unknown, nativeRewrite = true))
        assertFalse(ConversationSnapshotRules.regresses(record(latest), unknown.copy(messages = latest.messages + unknown.messages), nativeRewrite = true))
    }
}
