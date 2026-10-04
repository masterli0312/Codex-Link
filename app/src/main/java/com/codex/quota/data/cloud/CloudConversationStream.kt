package com.codex.quota.data.cloud

import com.codex.quota.data.cloud.DurableCloudWire.array
import com.codex.quota.data.cloud.DurableCloudWire.string
import kotlinx.serialization.json.*

/** Display-only projection of the subscribed thread. Never starts or replays a turn. */
class CloudConversationStream(private var thread: JsonObject) {
    private val turns = thread.array("turns").mapNotNull { it as? JsonObject }.takeLast(20).map { JsonObject(it - "itemsView") }.toMutableList()
    fun details(): CloudDetails = DurableCloudWire.details(thread, buildJsonObject {
        put("data", JsonArray(turns.reversed()))
    })

    fun apply(event: JsonObject): CloudDetails? {
        val params = event["params"] as? JsonObject ?: return null
        if (params.string("threadId") != thread.string("id")) return null
        val method = event.string("method")
        val turn = params["turn"] as? JsonObject
        val turnId = turn?.string("id") ?: params.string("turnId")
        if (turnId.isBlank()) return null
        var index = turns.indexOfFirst { it.string("id") == turnId }
        if (method == "turn/started") {
            if (index >= 0 && (index < turns.lastIndex || turns[index].string("status") != "inProgress")) return null
            if (index < 0) {
                turns += JsonObject(turn.orEmpty() + mapOf("id" to JsonPrimitive(turnId), "status" to JsonPrimitive("inProgress"),
                    "startedAt" to (turn?.get("startedAt") ?: JsonPrimitive(System.currentTimeMillis()))))
                while (turns.size > 20) turns.removeAt(0)
                index = turns.lastIndex
            }
        } else if (index < 0 || index != turns.lastIndex) return null
        val old = turns[index]
        val items = old.array("items").mapNotNull { it as? JsonObject }.toMutableList()
        fun item(value: JsonObject) {
            val id = value.string("id")
            if (id.isBlank()) return
            val existing = items.indexOfFirst { it.string("id") == id }
            if (existing >= 0) items[existing] = JsonObject(items[existing] + value) else items += value
        }
        val status = when (method) {
            "turn/started" -> "inProgress"
            "turn/completed" -> {
                turn?.array("items")?.mapNotNull { it as? JsonObject }?.forEach(::item)
                turn?.string("status")?.ifBlank { "completed" } ?: "completed"
            }
            "item/started", "item/completed" -> {
                if (old.string("status") != "inProgress") return null
                item(params["item"] as? JsonObject ?: return null)
                "inProgress"
            }
            "item/agentMessage/delta" -> {
                if (old.string("status") != "inProgress") return null
                val id = params.string("itemId")
                if (id.isBlank()) return null
                val previous = items.firstOrNull { it.string("id") == id }
                item(buildJsonObject {
                    put("id", id); put("type", "agentMessage")
                    put("text", (previous?.string("text").orEmpty() + params.string("delta")).take(100_000))
                })
                "inProgress"
            }
            "item/commandExecution/outputDelta" -> {
                val id = params.string("itemId")
                val previous = items.firstOrNull { it.string("id") == id } ?: return null
                item(JsonObject(previous + ("aggregatedOutput" to JsonPrimitive((previous.string("aggregatedOutput") + params.string("delta")).take(8192)))))
                old.string("status")
            }
            else -> return null
        }
        turns[index] = JsonObject(old + turn.orEmpty() + mapOf("status" to JsonPrimitive(status), "items" to JsonArray(items.takeLast(200))) +
            if(method == "turn/completed") mapOf("completedAt" to (turn?.get("completedAt") ?: JsonPrimitive(System.currentTimeMillis()))) else emptyMap())
        thread = JsonObject(thread + ("status" to buildJsonObject { put("type", if (status == "inProgress") "active" else "idle") }))
        return details()
    }

    companion object {
        /** HTTP persistence may trail the live socket. Keep confirmed native text and turn identity. */
        fun merge(previous: CloudDetails?, incoming: CloudDetails): CloudDetails {
            if (previous == null || previous.task.id != incoming.task.id) return incoming
            if (previous.latestTurnId.isNotBlank() && incoming.latestTurnId != previous.latestTurnId &&
                (incoming.latestTurnId.isBlank() || previous.messages.any { it.turnId == incoming.latestTurnId })) return previous
            val messages = incoming.messages.toMutableList()
            previous.messages.filter { it.id.isNotBlank() }.forEach { old ->
                if (old.id.contains(":queued-") && incoming.messages.any { it.role == "user" && it.turnId == old.turnId && com.codex.quota.notifications.task.ConversationDisplayText.userText(it.text) == com.codex.quota.notifications.task.ConversationDisplayText.userText(old.text) && !it.id.contains(":queued-") }) return@forEach
                val n = messages.indexOfFirst { it.id == old.id }
                if (n < 0) messages += old
                else if (old.text.length > messages[n].text.length && old.text.startsWith(messages[n].text)) messages[n] = old
            }
            val turnOrder = (previous.messages + incoming.messages).map { it.turnId }.filter { it.isNotBlank() }.distinct()
            val status = if (incoming.latestTurnId == previous.latestTurnId && incoming.task.status == "running" &&
                previous.task.status in setOf("completed", "failed")) previous else incoming
            return status.copy(messages = messages.sortedWith(compareBy<CloudMessage> { turnOrder.indexOf(it.turnId) }.thenBy { it.position }).takeLast(600),
                activities = com.codex.quota.notifications.task.RemoteActivityRules.merge(previous.activities,incoming.activities),
                turns = (previous.turns + incoming.turns).groupBy { it.id }.values.map { values -> values.last().let { it.copy(durationMs = it.durationMs ?: values.firstNotNullOfOrNull { t -> t.durationMs }) } })
        }
    }
}
