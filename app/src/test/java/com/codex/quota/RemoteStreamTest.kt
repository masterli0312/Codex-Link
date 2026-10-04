package com.codex.quota

import com.codex.quota.notifications.task.*
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest
import java.util.Base64
import kotlinx.serialization.encodeToString

class RemoteStreamTest {
    private val ref = RemoteThreadRef("11111111-2222-3333-4444-555555555555", "44444444-2222-3333-4444-555555555555", "last")
    private val command = RemoteProtocol.command(ref, TaskInbox.hash(ref.thread_id), "fixture")
    private val event = RemoteEvent("22222222-2222-3333-4444-555555555555", command.id, ref.host_id, ref.thread_id,
        command.conversation_id, "running", 1, 1000, reply = "hello 😀", turn_id = "turn")
    private fun hash(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    private val next = event.copy(seq = 4, at = 2000, reply = "", reply_patch = RemoteReplyPatch(1, 6, "😁 tail", hash("hello 😁 tail")))

    @Test fun patchesReconstructAuthenticatedUnicodeReplyAndDoNotEraseTools() {
        val tool = RemoteActivity("turn:tool", "turn", "commandExecution", title = "fixture")
        val result = RemoteProtocol.materialize(RemoteConversationState(command, event.copy(activities = listOf(tool))), next)
        assertEquals("hello 😁 tail", result?.reply)
        assertNull(result?.reply_patch)
        assertEquals(listOf(tool), result?.activities)
        assertEquals(4L, result?.seq)
    }
    @Test fun aMissingBaseWrongHashOrDifferentScopeNeverOverwritesTheCachedReply() {
        val state = RemoteConversationState(command, event)
        assertNull(RemoteProtocol.materialize(state.copy(event = null), next))
        assertNull(RemoteProtocol.materialize(state.copy(event = event.copy(seq = 2)), next))
        assertNull(RemoteProtocol.materialize(state.copy(event = event.copy(turn_id = "other")), next))
        assertNull(RemoteProtocol.materialize(state.copy(event = event.copy(host_id = "other-host")), next))
        assertNull(RemoteProtocol.materialize(state, next.copy(host_id = "other")))
        assertNull(RemoteProtocol.materialize(state, next.copy(reply_patch = next.reply_patch!!.copy(sha256 = "0".repeat(64)))))
        assertNull(RemoteProtocol.materialize(state, next.copy(reply_patch = next.reply_patch!!.copy(prefix_length = 7))))
        assertNull(RemoteProtocol.materialize(state.copy(event = event.copy(attachment_pending = true)), next))
    }
    @Test fun partialPatchHeadersCannotBeSavedAndFullStatusReplayRecoversWithoutResending() {
        val state = RemoteConversationState(command, event)
        assertNull(RemoteProtocol.materialize(state, next.copy(reply_patch = null, reply_patch_pending = true, attachment_pending = true)))
        val full = next.copy(seq = 10, reply_patch = null, reply = "complete authoritative state")
        assertEquals(full, RemoteProtocol.materialize(state, full))
        assertEquals("status", RemoteProtocol.control(command, "status").action)
        assertFalse(RemoteProtocol.accepts(RemoteConversationState(command, full), next))
    }
    @Test fun anOversizeOrMalformedPatchIsRejected() {
        val state = RemoteConversationState(command, event)
        assertNull(RemoteProtocol.materialize(state, next.copy(reply_patch = next.reply_patch!!.copy(suffix = "文".repeat(30000)))))
        assertNull(RemoteProtocol.materialize(state, next.copy(reply_patch = next.reply_patch!!.copy(prefix_length = -1))))
        assertNull(RemoteProtocol.materialize(state, next.copy(status = "completed")))
    }
    @Test fun encryptedPatchWireIsValidatedBeforeReconstruction() {
        val key = Base64.getEncoder().encodeToString(ByteArray(32) { 3 })
        val decoded = RemoteProtocol.event(RemoteProtocol.wire("event", next.id, RemoteProtocol.json.encodeToString(next), key), key)
        assertNotNull(decoded)
        assertEquals("hello 😁 tail", RemoteProtocol.materialize(RemoteConversationState(command, event), decoded!!)?.reply)
        val invalid = next.copy(reply_patch = next.reply_patch!!.copy(base_seq = next.seq))
        assertNull(RemoteProtocol.event(RemoteProtocol.wire("event", invalid.id, RemoteProtocol.json.encodeToString(invalid), key), key))
    }
}
