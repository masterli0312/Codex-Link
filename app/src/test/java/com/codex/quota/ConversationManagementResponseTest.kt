package com.codex.quota

import com.codex.quota.notifications.task.*
import java.io.IOException
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ConversationManagementResponseTest {
    private val command = RemoteCommand("request", "archive", "host", "thread", "conversation", "turn", 1000, read_thread_id = "target")
    private val event = RemoteEvent("event", "request", "host", "thread", "conversation", "managed", 2, 2000,
        managed_thread_id = "target", managed_action = "archive")

    @Test fun rowReceivesItsOwnAcknowledgementWithoutACatalogOrOpenConversationConsumer() = runTest {
        val response = ConversationManagementResponse.await(command, flowOf(
            event.copy(request_id = "catalog", status = "threads"),
            event.copy(host_id = "other-host"), event.copy(thread_id = "other-thread"),
            event.copy(conversation_id = "other-conversation"), event))
        assertEquals(event, response)
    }
    @Test fun rejectionAndAmbiguousRepliesDoNotBecomeSuccessfulArchives() = runTest {
        for (reply in listOf(event.copy(error = "BUSY"), event.copy(status = "unknown"),
            event.copy(partial = true), event.copy(attachment_pending = true),
            event.copy(managed_thread_id = "other"), event.copy(managed_action = "rename"))) {
            try { ConversationManagementResponse.await(command, flowOf(reply)); fail("Must not apply archive") }
            catch (expected: IOException) { assertEquals(if (reply.error.isBlank()) "MANAGEMENT_UNCONFIRMED" else reply.error, expected.message) }
        }
    }
}
