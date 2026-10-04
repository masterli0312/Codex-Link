package com.codex.quota

import com.codex.quota.notifications.task.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class RemoteModeTest {
    private val host = "11111111-2222-3333-4444-555555555555"
    private val thread = "22222222-2222-3333-4444-555555555555"
    private val ref = RemoteThreadRef(host, thread, "turn")
    private val key = Base64.getEncoder().encodeToString(ByteArray(32) { 9 })

    @Test fun modeIsClosedAndPersistsInCommandsAndQueuedTurns() {
        val command = RemoteProtocol.command(ref, TaskInbox.hash(thread), "continue", mode = "plan")
        assertEquals("plan", RemoteProtocol.json.decodeFromString<RemoteCommand>(RemoteProtocol.json.encodeToString(command)).mode)
        val running = RemoteConversationState(command, RemoteEvent(thread, command.id, host, thread, command.conversation_id, "running", 1, 1000, turn_id = "turn"))
        assertEquals("plan", RemoteProtocol.followUp(running, "queue", "next").mode)
        assertThrows(IllegalArgumentException::class.java) { RemoteProtocol.command(ref, TaskInbox.hash(thread), "continue", mode = "arbitrary") }
        assertEquals("", Json.decodeFromString<ConversationPreferences>("{}").mode)
        assertEquals("plan", Json.decodeFromString<ConversationPreferences>(Json.encodeToString(ConversationPreferences(mode = "plan"))).mode)
    }

    @Test fun authenticatedModeCatalogRejectsUnknownAndDuplicateValues() {
        val event = RemoteEvent(thread, thread, host, thread, TaskInbox.hash(thread), "models", 1, 1000, modes = listOf("default", "plan"))
        fun decode(value: RemoteEvent) = RemoteProtocol.event(RemoteProtocol.wire("event", value.id, RemoteProtocol.json.encodeToString(value), key), key)
        assertEquals(event.modes, decode(event)?.modes)
        assertNull(decode(event.copy(modes = listOf("future"))))
        assertNull(decode(event.copy(modes = listOf("plan", "plan"))))
    }
}
