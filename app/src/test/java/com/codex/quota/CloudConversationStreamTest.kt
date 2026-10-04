package com.codex.quota

import com.codex.quota.data.cloud.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class CloudConversationStreamTest {
    private fun obj(s: String) = DurableCloudWire.objectOf(s)
    private fun event(method: String,params: String) = buildJsonObject { put("method",method);put("params",obj(params)) }
    private fun fixture() = CloudConversationStream(obj("""{"id":"thread","status":{"type":"idle"},"turns":[{"id":"old","status":"completed","items":[{"id":"a","type":"agentMessage","text":"Old reply"}]}]}"""))
    @Test fun liveDeltasArriveBeforeHttpPersistenceAndCompletionKeepsTheFinalText() {
        val stream = fixture()
        stream.apply(event("turn/started","""{"threadId":"thread","turn":{"id":"new","status":"inProgress","startedAt":1000}}"""))
        stream.apply(event("item/started","""{"threadId":"thread","turnId":"new","item":{"id":"u","type":"userMessage","content":[{"type":"text","text":"Question"}]}}"""))
        stream.apply(event("item/started","""{"threadId":"thread","turnId":"new","item":{"id":"a","type":"agentMessage","phase":"final_answer","text":""}}"""))
        stream.apply(event("item/agentMessage/delta","""{"threadId":"thread","turnId":"new","itemId":"a","delta":"Hello "}"""))
        val live = stream.apply(event("item/agentMessage/delta","""{"threadId":"thread","turnId":"new","itemId":"a","delta":"world"}"""))!!
        assertEquals("Hello world",live.messages.last().text)
        assertEquals("new:a",live.messages.last().id)
        assertEquals("new",live.activeTurnId)
        val completed = stream.apply(event("turn/completed","""{"threadId":"thread","turn":{"id":"new","status":"completed","completedAt":1010,"items":[]}}"""))!!
        assertEquals("Hello world",completed.messages.last().text)
        assertEquals("completed",completed.task.status)
        assertEquals(10000L,completed.turns.last().durationMs)
        assertTrue(completed.messages.last().copyable)
        assertNull(stream.apply(event("item/agentMessage/delta","""{"threadId":"thread","turnId":"new","itemId":"a","delta":" late"}""")))
    }
    @Test fun previousAndForeignTurnsCannotReplaceTheCurrentConversation() {
        val stream = fixture()
        assertNull(stream.apply(event("turn/started","""{"threadId":"other","turn":{"id":"new"}}""")))
        stream.apply(event("turn/started","""{"threadId":"thread","turn":{"id":"new"}}"""))
        assertNull(stream.apply(event("turn/started","""{"threadId":"thread","turn":{"id":"old"}}""")))
        assertNull(stream.apply(event("turn/completed","""{"threadId":"thread","turn":{"id":"old","status":"completed"}}""")))
        assertEquals("new",stream.details().activeTurnId)
    }
    @Test fun httpReconciliationKeepsNewerTextAndDoesNotRestoreACompletedTurnAsRunning() {
        val task = CloudTask("thread","Title","running")
        val old = CloudMessage("assistant","Earlier","old:a","old",position = 0)
        val latest = CloudMessage("assistant","Hello world","new:a","new",position = 0)
        val live = CloudDetails(task,listOf(old,latest),latestTurnId = "new",activeTurnId = "new")
        assertEquals(live,CloudConversationStream.merge(live,CloudDetails(task.copy(status = "completed"),listOf(old),latestTurnId = "old")))
        val delayed = live.copy(messages = listOf(old,latest.copy(text = "Hello")))
        assertEquals("Hello world",CloudConversationStream.merge(live,delayed).messages.last().text)
        assertEquals("completed",CloudConversationStream.merge(live.copy(task = task.copy(status = "completed")),delayed).task.status)
    }
    @Test fun publicActivitiesKeepNativeOrderWithoutReasoningOrMcpPayloads() {
        val thread = obj("""{"id":"thread","status":{"type":"idle"}}""")
        val page = obj("""{"data":[{"id":"turn","status":"completed","items":[
          {"id":"u","type":"userMessage","content":[{"type":"text","text":"Question"}]},
          {"id":"c","type":"agentMessage","text":"Checking","phase":"commentary"},
          {"id":"r","type":"reasoning","text":"PRIVATE"},
          {"id":"cmd","type":"commandExecution","command":"cat example.kt","commandActions":[{"type":"read"}],"aggregatedOutput":"result","exitCode":0},
          {"id":"mcp","type":"mcpToolCall","server":"github","tool":"fetch","arguments":"PRIVATE","result":"PRIVATE"},
          {"id":"a","type":"agentMessage","text":"Final answer","phase":"final_answer"}]}]}""")
        val details = DurableCloudWire.details(thread,page)
        assertFalse(details.messages[1].copyable)
        assertTrue(details.messages.last().copyable)
        assertEquals(listOf("fileRead","mcpToolCall"),details.activities.map { it.type })
        assertEquals(listOf(3,4),details.activities.map { it.position })
        assertFalse(details.toString().contains("PRIVATE"))
        assertNull(DurableCloudWire.turnDuration(obj("""{"startedAt":1000}""")))
    }
    @Test fun acknowledgedSteeringIsReplacedByItsNativeMessageInsteadOfDuplicated() {
        val task = CloudTask("thread","Title","running")
        val pending = CloudMessage("user","Guide","turn:queued-id","turn",position = Int.MAX_VALUE)
        val native = CloudMessage("user","Guide","turn:native","turn",position = 2)
        val previous = CloudDetails(task,listOf(pending),latestTurnId = "turn")
        val incoming = previous.copy(messages = listOf(native))
        assertEquals(listOf(native),CloudConversationStream.merge(previous,incoming).messages)
    }
    @Test fun partialResumeDoesNotBlockLiveMessagesAndCompletedTurnsCannotRestart() {
        val stream = CloudConversationStream(obj("""{"id":"thread","turns":[{"id":"turn","itemsView":"notLoaded","status":"inProgress"}]}"""))
        assertEquals("running",stream.details().task.status)
        val live = stream.apply(event("item/agentMessage/delta","""{"threadId":"thread","turnId":"turn","itemId":"a","delta":"Live text"}"""))!!
        assertEquals("Live text",live.messages.single().text)
        stream.apply(event("turn/completed","""{"threadId":"thread","turn":{"id":"turn","status":"completed","durationMs":0}}"""))
        assertNull(stream.apply(event("turn/started","""{"threadId":"thread","turn":{"id":"turn"}}""")))
        assertEquals(0L,stream.details().turns.single().durationMs)
    }

}
