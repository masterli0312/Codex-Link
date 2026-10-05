package com.codex.quota

import com.codex.quota.notifications.task.*
import java.io.IOException
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ConversationCompactionResponseTest {
    private val command = RemoteCommand("request", "compact", "host", "thread", "conversation", "turn", 1000)
    private val event = RemoteEvent("event", "request", "host", "thread", "conversation", "compacted", 2, 2000)

    @Test fun onlyActualCompletionOfTheSameCommandIsAccepted() = runTest {
        assertEquals(event, ConversationCompactionResponse.await(command, flowOf(
            event.copy(status = "compacting"), event.copy(request_id = "watch"), event.copy(host_id = "other"),
            event.copy(thread_id = "other"), event.copy(conversation_id = "other"), event)))
        assertFalse(ConversationCompactionResponse.matches(command.copy(action = "read"), event))
    }
    @Test fun incompleteOrFailedResultsNeverBecomeSuccess() = runTest {
        for (reply in listOf(event.copy(status = "unknown"), event.copy(status = "failed", error = "BUSY"),
            event.copy(partial = true), event.copy(attachment_pending = true))) {
            try { ConversationCompactionResponse.await(command, flowOf(reply)); fail("Must not claim success") }
            catch (expected: IOException) { assertEquals(if (reply.error.isBlank()) "COMPACTION_UNCONFIRMED" else reply.error, expected.message) }
        }
    }
}
