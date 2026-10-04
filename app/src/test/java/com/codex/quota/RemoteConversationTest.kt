package com.codex.quota

import com.codex.quota.notifications.task.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class RemoteConversationTest {
    @Test fun approvalsRequireThePersistedInteractionAndExactNativeTurn() {
        val c = RemoteProtocol.command(ref, TaskInbox.hash(thread), "continue")
        val approvalId = "33333333-2222-3333-4444-555555555555"
        val event = RemoteEvent("22222222-2222-3333-4444-555555555555", c.id, host, thread, c.conversation_id,
            "approval", 8, 2000, turn_id = "real-turn", approval_id = approvalId)
        val state = RemoteConversationState(c, event)
        assertEquals("real-turn", RemoteProtocol.approval(state, approvalId, false).expected_turn_id)
        assertTrue(RemoteProtocol.approval(state, approvalId, true).allow)
        assertThrows(IllegalArgumentException::class.java) { RemoteProtocol.approval(state, "old-request", true) }
        assertThrows(IllegalArgumentException::class.java) { RemoteProtocol.approval(state.copy(event = event.copy(status = "completed")), approvalId, true) }
        assertThrows(IllegalArgumentException::class.java) { RemoteProtocol.approval(state.copy(event = event.copy(host_id = "other-host")), approvalId, true) }
        assertThrows(IllegalArgumentException::class.java) { RemoteProtocol.approval(state.copy(event = event.copy(turn_id = "")), approvalId, true) }
        assertThrows(IllegalArgumentException::class.java) { RemoteProtocol.approval(state.copy(event = event.copy(attachment_pending = true)), approvalId, true) }
        assertThrows(IllegalArgumentException::class.java) { RemoteProtocol.approval(state.copy(event = event.copy(approval_can_allow = false)), approvalId, true) }
        assertFalse(RemoteProtocol.approval(state.copy(event = event.copy(approval_can_allow = false)), approvalId, false).allow)
    }
    @Test fun authenticatedBodyCanCompleteTheSamePendingHeaderFromAnotherSubscriber() {
        val c = RemoteProtocol.command(ref, TaskInbox.hash(thread), "continue")
        val header = RemoteEvent("22222222-2222-3333-4444-555555555555", c.id, host, thread, c.conversation_id,
            "completed", 8, 2000, turn_id = "real-turn", partial = true, attachment_pending = true)
        val body = header.copy(partial = false, attachment_pending = false, reply = "Complete response")
        val state = RemoteConversationState(c, header)
        assertTrue(RemoteProtocol.accepts(state, body))
        assertFalse(RemoteProtocol.accepts(state.copy(event = body), header))
        assertFalse(RemoteProtocol.accepts(state.copy(event = body), body))
        assertFalse(RemoteProtocol.accepts(state, body.copy(id = "33333333-2222-3333-4444-555555555555")))
        assertFalse(RemoteProtocol.accepts(state, body.copy(turn_id = "other-turn")))
        assertFalse(RemoteProtocol.accepts(state, body.copy(host_id = "other-host")))
        assertFalse(RemoteProtocol.accepts(state, body.copy(seq = 7)))
        assertFalse(RemoteProtocol.accepts(state, body.copy(status = "running")))
    }
    private val key = Base64.getEncoder().encodeToString(ByteArray(32) { 9 })
    private val host = "11111111-2222-3333-4444-555555555555"
    private val thread = "44444444-2222-3333-4444-555555555555"
    private val ref = RemoteThreadRef(host, thread, "finished-turn")

    @Test fun inputAnswersRequireTheExactCompleteServerQuestionSetAndStayEncrypted() {
        val c = RemoteProtocol.command(ref, TaskInbox.hash(thread), "continue")
        val input = RemoteInputRequest("33333333-2222-3333-4444-555555555555", "real-turn", true,
            listOf(RemoteInputQuestion("choice", "Choose", "Which?", options = listOf(RemoteInputOption("One"), RemoteInputOption("Two"))),
                RemoteInputQuestion("detail", "Detail", "Text?", is_secret = true)))
        val event = RemoteEvent("22222222-2222-3333-4444-555555555555", c.id, host, thread, c.conversation_id,
            "input_required", 10, 2000, turn_id = input.turn_id, user_input = input)
        val state = RemoteConversationState(c, event)
        val answers = mapOf("choice" to listOf("Two"), "detail" to listOf("PRIVATE_FIXTURE"))
        val answer = RemoteProtocol.answerInput(state, input.id, answers)
        assertEquals("answer_input", answer.action)
        assertEquals(c.id, answer.target_id)
        assertEquals(input.turn_id, answer.expected_turn_id)
        assertFalse(answer.toString().contains("PRIVATE_FIXTURE"))
        assertFalse(RemoteProtocol.wire("command", answer.id, RemoteProtocol.json.encodeToString(answer), key).toString().contains("PRIVATE_FIXTURE"))
        assertThrows(IllegalArgumentException::class.java) { RemoteProtocol.answerInput(state, "old-id", answers) }
        assertFalse(RemoteInputRules.accepts(input, answers - "detail"))
        assertFalse(RemoteInputRules.accepts(input, answers + ("choice" to listOf("Three"))))
        assertFalse(RemoteInputRules.accepts(input, answers + ("choice" to listOf("One", "Two"))))
        assertFalse(RemoteInputRules.accepts(input, answers + ("detail" to listOf("文".repeat(2000)))))
        assertThrows(IllegalArgumentException::class.java) { RemoteProtocol.answerInput(state.copy(event = event.copy(attachment_pending = true)), input.id, answers) }
        assertThrows(IllegalArgumentException::class.java) { RemoteProtocol.answerInput(state.copy(event = event.copy(host_id = "other-host")), input.id, answers) }
        assertThrows(IllegalArgumentException::class.java) { RemoteProtocol.answerInput(state.copy(event = event.copy(status = "completed")), input.id, answers) }
    }

    @Test fun partialInputHeadersCannotInventUsableQuestions() {
        val c = RemoteProtocol.command(ref, TaskInbox.hash(thread), "continue")
        val event = RemoteEvent("22222222-2222-3333-4444-555555555555", c.id, host, thread, c.conversation_id,
            "input_required", 10, 2000, turn_id = "real-turn")
        fun decode(e: RemoteEvent) = RemoteProtocol.event(RemoteProtocol.wire("event", e.id, RemoteProtocol.json.encodeToString(e), key), key)
        assertNull(decode(event))
        assertNotNull(decode(event.copy(attachment_pending = true)))
        val input = RemoteInputRequest("33333333-2222-3333-4444-555555555555", "different-turn", true,
            listOf(RemoteInputQuestion("q", "Q", "Question?")))
        assertNull(decode(event.copy(user_input = input)))
        assertFalse(RemoteInputRules.valid(input.copy(questions = input.questions + input.questions)))
    }
    @Test fun oldInputNotificationsCannotReappearAfterAnswerOrSupersession() {
        val c = RemoteProtocol.command(ref, TaskInbox.hash(thread), "continue")
        val input = RemoteInputRequest("33333333-2222-3333-4444-555555555555", "real-turn", true,
            listOf(RemoteInputQuestion("q", "Q", "Question?")))
        val event = RemoteEvent("22222222-2222-3333-4444-555555555555", c.id, host, thread, c.conversation_id,
            "input_required", 10, 2000, turn_id = input.turn_id, user_input = input)
        val state = RemoteConversationState(c, event)
        assertTrue(RemoteAttention.matches(state, event))
        assertEquals(RemoteAttention.identity(event), RemoteAttention.identity(event.copy(seq = 20, at = 3000)))
        assertFalse(RemoteAttention.matches(state.copy(event = event.copy(status = "running", user_input = null)), event))
        assertFalse(RemoteAttention.matches(state.copy(event = event.copy(user_input = input.copy(id = "44444444-2222-3333-4444-555555555555"))), event))
        assertFalse(RemoteAttention.matches(state, event.copy(host_id = "other-host")))
        assertFalse(RemoteAttention.matches(state, event.copy(attachment_pending = true)))
    }

    @Test fun hostTopicMatchesTheComputerAndStaysOnPairedHttpsOrigin() {
        assertEquals("https://ntfy.sh/cu-786f85180c2de52dcdc25fde8daecb3e8cc55522eca48f770dfc27eac050",
            RemoteProtocol.topic("https://ntfy.sh/paired", key, host, "commands"))
        assertNotEquals(RemoteProtocol.topic("https://ntfy.sh/paired", key, host, "commands"), RemoteProtocol.topic("https://ntfy.sh/paired", key, host, "events"))
        assertTrue(RemoteProtocol.validRef(ref, TaskInbox.hash(thread)))
        assertFalse(RemoteProtocol.validRef(ref.copy(thread_id = "../../auth.json"), TaskInbox.hash(thread)))
        assertFalse(RemoteProtocol.validRef(ref, TaskInbox.hash("another-thread")))
    }
    @Test fun realNodeCiphertextDecryptsWithChineseAndDirectionCannotBeReflected() {
        val fixture = """{"schema_version":"2.0","id":"22222222-2222-3333-4444-555555555555","encrypted":"Lm3Qg8r99MzqhhuZEpmouoMuXGaQqrKZq3QnFxMWH6W9FLJSCd+8/h4vUmdNUtCqXwKG7lAzgAZzTGEQCeed8MZx4rLIGq3I6B9Bqei1s2e5/F3zaTdevqrSOvbbGteb89Un4QGM56cEXXwVeYPxtWQ9UDk59aH4yfpq65C6PGIT+NioeEAuSL9TXXUJ4kLgLFKhnVxLwzdqSE2pzYoBz884naX2Tu0aa7iyC1LzoudNmg6vUh2JyTlQ7VQu/SMjg3cyVfG6X9fN6UNTzeCUZNad/WFBQsVy1atndxwto7vRJjGyxew7DE4iGYBpwnBN/ArHddLV4Ej17dmV7116r0w0Shqga81oQ2YH61PMJMT4XMgz4ysjRnamFhC7AJqrcZ62WFLW1oc9mgyl0Rg/DAQVZvr3ldrXY8/GavmmF0yB"}"""
        val wire = Json.parseToJsonElement(fixture).jsonObject
        assertEquals("中文回复 🔔", RemoteProtocol.event(wire, key)?.reply)
        val e = RemoteProtocol.event(wire, key)!!
        val reflected = RemoteProtocol.wire("command", e.id, RemoteProtocol.json.encodeToString(e), key)
        assertNull(RemoteProtocol.event(reflected, key))
        assertNull(RemoteProtocol.event(wire, Base64.getEncoder().encodeToString(ByteArray(32))))
    }
    @Test fun staleDuplicateAndOtherHostRepliesCannotMutateTheConversation() {
        val c = RemoteProtocol.command(ref, TaskInbox.hash(thread), "继续")
        val e = RemoteEvent("22222222-2222-3333-4444-555555555555", c.id, host, thread, c.conversation_id, "running", 20, 2000)
        val state = RemoteConversationState(c, e)
        assertFalse(RemoteProtocol.accepts(state, e))
        assertFalse(RemoteProtocol.accepts(state, e.copy(seq = 19)))
        assertFalse(RemoteProtocol.accepts(state, e.copy(seq = 21, request_id = "another-request")))
        assertFalse(RemoteProtocol.accepts(state, e.copy(seq = 21, host_id = "another-host")))
        assertFalse(RemoteProtocol.accepts(state.copy(event = e.copy(status = "completed")), e.copy(seq = 21)))
        assertTrue(RemoteProtocol.accepts(state, e.copy(seq = 21, status = "completed")))
    }
    @Test fun controlTargetsTheOriginalRequestAndDoesNotLeakOrRenewItsPrompt() {
        val c = RemoteProtocol.command(ref, TaskInbox.hash(thread), "a private prompt")
        val stop = RemoteProtocol.control(c, "stop")
        assertEquals(c.id, stop.target_id)
        assertNotEquals(c.id, stop.id)
        assertEquals("", stop.text)
        assertEquals(c.thread_id, stop.thread_id)
        val wire = RemoteProtocol.wire("command", c.id, RemoteProtocol.json.encodeToString(c), key)
        assertFalse(wire.toString().contains(c.text))
        assertThrows(IllegalArgumentException::class.java) { RemoteProtocol.command(ref, c.conversation_id, "文".repeat(6000)) }
    }
    @Test fun selectedModelAndEffortStayInTheEncryptedCommand() {
        val c = RemoteProtocol.command(ref, TaskInbox.hash(thread), "continue", "available-model", "high")
        assertEquals("available-model", c.model)
        assertEquals("high", c.effort)
        assertFalse(RemoteProtocol.wire("command", c.id, RemoteProtocol.json.encodeToString(c), key).toString().contains(c.model))
        assertThrows(IllegalArgumentException::class.java) { RemoteProtocol.command(ref, c.conversation_id, "continue", "bad\nmodel", "low") }
    }
    @Test fun followUpsTargetTheActualTurnAndCannotBecomeOrdinarySends() {
        val c = RemoteProtocol.command(ref, TaskInbox.hash(thread), "original")
        val running = RemoteEvent("22222222-2222-3333-4444-555555555555", c.id, host, thread, c.conversation_id, "running", 1, 2000, turn_id = "actual-turn")
        val state = RemoteConversationState(c, running)
        val q = RemoteProtocol.followUp(state, "queue", "later")
        assertEquals(c.id, q.target_id)
        assertEquals("actual-turn", q.expected_turn_id)
        assertEquals("queue", q.action)
        assertThrows(IllegalArgumentException::class.java) { RemoteProtocol.followUp(state.copy(event = running.copy(status = "completed")), "steer", "change") }
        assertThrows(IllegalArgumentException::class.java) { RemoteProtocol.followUp(state, "cancel_queue", queueId = "../other") }
        val acknowledgement = running.copy(request_id = q.id, status = "queued", parent_request_id = c.id)
        assertFalse(RemoteProtocol.accepts(state, acknowledgement))
        assertTrue(RemoteProtocol.accepts(RemoteConversationState(q), acknowledgement))
        assertTrue(FollowUpState.pending(RemoteConversationState(q, acknowledgement)))
        assertTrue(FollowUpState.recoverable(RemoteConversationState(q, acknowledgement.copy(status = "cancelled"))))
    }
    @Test fun completionNotificationCannotFinishAParentWithQueuedMessages() {
        val c = RemoteProtocol.command(ref, TaskInbox.hash(thread), "original")
        val running = RemoteEvent("22222222-2222-3333-4444-555555555555", c.id, host, thread, c.conversation_id, "running", 1, 2000, turn_id = "actual-turn")
        val state = RemoteConversationState(c, running)
        val q = RemoteProtocol.followUp(state, "queue", "later")
        val record = TaskInboxRecord("record", 1000, "notification", "paired", TaskConversationSnapshot(conversation_id = c.conversation_id),
            remoteState = state, followUps = listOf(RemoteConversationState(q)))
        val notification = record.copy(id = "incoming", snapshot = record.snapshot.copy(remote_ref = ref.copy(baseline_turn = running.turn_id)), remoteState = null)
        assertEquals("running", ConversationIndex.mergeNotification(record, notification, true).remoteState?.event?.status)
    }
    @Test fun newChatDefaultsUseTheSameComputerAndMostFrequentSelection() {
        fun record(id: String, computer: String, model: String, effort: String, endpoint: String = "pair") =
            TaskInboxRecord(id, 1000, id, endpoint, TaskConversationSnapshot(conversation_id = id, remote_ref = ref.copy(host_id = computer)),
                selectedModel = model, selectedEffort = effort)
        val records = listOf(record("a", host, "frequent", "low"), record("b", host, "frequent", "low"), record("c", host, "recent", "high"),
            record("d", "other-computer", "foreign", "high"), record("e", host, "foreign", "high", "other-pair"))
        assertEquals("frequent" to "low", ConversationDefaults.options(records, "pair", host))
        assertEquals("" to "", ConversationDefaults.options(records, "missing-pair", host))
    }
    @Test fun queuedInputComesFromTheAuthenticatedChildWhenOnlyAPartialHeaderArrives() {
        val c = RemoteProtocol.command(ref, TaskInbox.hash(thread), "original")
        val event = RemoteEvent("22222222-2222-3333-4444-555555555555", c.id, host, thread, c.conversation_id, "running", 1, 2000,
            turn_id = "queued-turn", active_input = "clipped", attachment_pending = true)
        val parent = RemoteConversationState(c, event)
        val q = c.copy(id = "33333333-2222-3333-4444-555555555555", action = "queue", text = "full queued input", target_id = c.id)
        val child = RemoteConversationState(q, event.copy(request_id = q.id, status = "running", parent_request_id = c.id))
        assertEquals(q.text, ConversationHistory.activeInput(parent, listOf(child)))
        assertEquals(c.text, ConversationHistory.activeInput(parent, listOf(child.copy(event = child.event!!.copy(parent_request_id = "other-root")))))
        assertEquals("actual complete input", ConversationHistory.activeInput(parent.copy(event = event.copy(attachment_pending = false,
            active_input = "actual complete input")), listOf(child)))
    }
}
