package com.codex.quota.data.cloud

import com.codex.quota.data.cloud.DurableCloudWire.array
import com.codex.quota.data.cloud.DurableCloudWire.string
import com.codex.quota.notifications.task.*
import kotlinx.serialization.json.*

/** Only typed public tool summaries. Reasoning and arbitrary MCP results stay private. */
internal object CloudActivityProjection {
    private fun bounded(s: String, bytes: Int) = s.toByteArray().take(bytes).toByteArray().toString(Charsets.UTF_8).trimEnd('\uFFFD')
    fun item(turn: String, item: JsonObject, position: Int): RemoteActivity? {
        val id = item.string("id")
        if (id.isBlank()) return null
        val type = item.string("type")
        val status = item.string("status").takeIf { it in setOf("inProgress","completed","failed","declined") } ?: "completed"
        val activity = when (type) {
            "commandExecution" -> RemoteActivity("$turn:$id",turn,
                if(item.array("commandActions").isNotEmpty() && item.array("commandActions").all { (it as? JsonObject)?.string("type") == "read" }) "fileRead" else type,status,
                title = bounded(item.string("command"),512),detail = bounded(item.string("aggregatedOutput"),8192),
                exit_code = (item["exitCode"] as? JsonPrimitive)?.intOrNull,
                duration_ms = (item["durationMs"] as? JsonPrimitive)?.longOrNull?.takeIf { it >= 0 },position = position)
            "fileChange" -> RemoteActivity("$turn:$id",turn,type,status,files = item.array("changes").take(8).mapNotNull { row ->
                val file = row as? JsonObject ?: return@mapNotNull null
                val path = file.string("path").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val kind = (file["kind"] as? JsonObject)?.string("type") ?: file.string("kind")
                RemoteChangedFile(bounded(path,1024),bounded(file.string("diff"),4096),kind.takeIf { it in setOf("add","delete","update") } ?: "unknown")
            },position = position)
            "mcpToolCall" -> RemoteActivity("$turn:$id",turn,type,status,
                title = bounded(item.string("server") + " / " + item.string("tool"),512),position = position)
            "plan" -> RemoteActivity("$turn:$id",turn,type,status,detail = bounded(item.string("text"),8192),position = position)
            else -> return null
        }
        return activity.takeIf { RemoteActivityRules.valid(listOf(it)) }
    }
}
