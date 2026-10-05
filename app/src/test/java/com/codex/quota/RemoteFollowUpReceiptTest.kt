package com.codex.quota

import com.codex.quota.notifications.task.*
import java.io.IOException
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class RemoteFollowUpReceiptTest {
    private val command = RemoteCommand("steer", "steer", "host", "thread", "conversation", "turn", 1000,
        text = "Fixture steer", target_id = "watch", expected_turn_id = "turn")
    private val receipt = RemoteEvent("receipt", "steer", "host", "thread", "conversation", "steered", 2, 2000,
        turn_id = "turn", parent_request_id = "watch")

    @Test fun aReceiptIsConfirmedWithoutReadingOrDownloadingConversationContent() = runTest {
        assertEquals(receipt, RemoteFollowUpReceipt.await(command, flowOf(
            receipt.copy(request_id = "watch", status = "running", partial = true, attachment_pending = true),
            receipt.copy(host_id = "other"), receipt.copy(thread_id = "other"),
            receipt.copy(conversation_id = "other"), receipt.copy(request_id = "second-steer"), receipt)))
    }
    @Test fun aDifferentTurnOrParentAndUnconfirmedDeliveryNeverClearThePendingMessage() = runTest {
        for (event in listOf(receipt.copy(turn_id = "older-turn"), receipt.copy(parent_request_id = "old-watch"),
            receipt.copy(status = "failed"), receipt.copy(status = "unknown"), receipt.copy(partial = true),
            receipt.copy(attachment_pending = true), receipt.copy(error = "REJECTED"))) {
            try { RemoteFollowUpReceipt.await(command, flowOf(event)); fail("Must remain unconfirmed") }
            catch (expected: IOException) { assertEquals("REMOTE_UNCONFIRMED", expected.message) }
        }
    }
}
