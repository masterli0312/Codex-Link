package com.codex.quota.data.cloud

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable data class CloudRepositoryRef(val repository_id: String, val ref: String)
@Serializable data class CloudGithubRepository(val id: String, val name: String, val branch: String)
@Serializable data class CloudPreparationChoice(val model: String = "", val effort: String = "")
@Serializable data class CloudPreparationModel(val model: String, val name: String,
    val efforts: List<String>, val defaultEffort: String, val isDefault: Boolean = false)
@Serializable data class CloudConfig(val id: String, val name: String, val versionId: String,
    val revision: Int, val latestReadyVersion: String = "", val status: String = "unknown",
    val repositories: List<CloudRepositoryRef> = emptyList(), val threadId: String = "",
    val raw: JsonObject, val draft: JsonObject? = null) {
    val published: Boolean get() = revision > 1 && latestReadyVersion.isNotBlank()
    val editable: JsonObject get() = draft ?: raw
}
@Serializable data class CloudSetupSession(val configId: String, val draftId: String = "",
    val threadId: String = "", val explicitDraft: Boolean = false, val operationId: String = "",
    val publicationKey: String = "", val previousVersion: String = "", val stage: String = "editing")

/** Endpoint-specific contracts. Do not substitute config IDs for runtime or legacy IDs. */
object DurableCloudWire {
    private val json = Json { ignoreUnknownKeys = true }
    fun objectOf(body: String): JsonObject = json.parseToJsonElement(body).jsonObject
    fun validId(id: String) = id.length in 1..512 && id.matches(Regex("[A-Za-z0-9_~.:-]+")) && id !in setOf(".", "..")
    fun models(page: JsonObject): List<CloudPreparationModel> = page.array("data").mapNotNull { row ->
        val o = row as? JsonObject ?: return@mapNotNull null
        if(o["hidden"]?.jsonPrimitive?.booleanOrNull == true) return@mapNotNull null
        val model = o.string("model")
        val efforts = o.array("supportedReasoningEfforts").mapNotNull { (it as? JsonObject)?.string("reasoningEffort") }
            .filter { it in setOf("none", "minimal", "low", "medium", "high", "xhigh", "max", "ultra") }.distinct()
        val default = o.string("defaultReasoningEffort")
        if(!validId(model) || efforts.isEmpty() || default !in efforts) return@mapNotNull null
        CloudPreparationModel(model,o.string("displayName").ifBlank { model },efforts,default,
            o["isDefault"]?.jsonPrimitive?.booleanOrNull == true)
    }.distinctBy { it.model }
    fun validChoice(choice: CloudPreparationChoice, models: List<CloudPreparationModel>) =
        models.any { it.model == choice.model && choice.effort in it.efforts }
    fun chooseModel(models: List<CloudPreparationModel>, saved: CloudPreparationChoice?): CloudPreparationChoice {
        if(saved != null && validChoice(saved,models)) return saved
        val m = models.firstOrNull { it.isDefault } ?: models.firstOrNull() ?: return CloudPreparationChoice()
        return CloudPreparationChoice(m.model,m.defaultEffort)
    }
    fun config(o: JsonObject): CloudConfig {
        val id = o.string("id"); require(validId(id))
        return CloudConfig(id, o.string("name").take(512), o.string("version_id"),
            o["version_revision"]?.jsonPrimitive?.intOrNull ?: 0, o.string("latest_ready_version_id"),
            o.string("status"), repositories(o), o.string("thread_id"), o, o["draft"] as? JsonObject)
    }
    fun repositories(o: JsonObject) = o.array("repositories").map {
        val r = it.jsonObject; CloudRepositoryRef(r.string("repository_id"), r.string("ref"))
    }
    fun environment(o: JsonObject): CloudEnvironment {
        val c = config(o)
        return CloudEnvironment(c.id, c.name, published = c.revision > 1,
            repositories = c.repositories, threadId = c.threadId)
    }
    fun task(o: JsonObject): CloudTask {
        val id = o.string("id"); require(validId(id))
        val status = (o["status"] as? JsonObject)?.string("type")
        val seconds = o["updatedAt"]?.jsonPrimitive?.longOrNull ?: 0
        return CloudTask(id, o.string("name").ifBlank { o.string("preview") }.take(512),
            when(status) { "active" -> "running"; "idle" -> "completed"; else -> "unknown" },
            seconds.takeIf { it in 1..Long.MAX_VALUE / 1000 }?.times(1000) ?: 0)
    }
    fun details(thread: JsonObject, turns: JsonObject): CloudDetails {
        val messages = mutableListOf<CloudMessage>()
        val rows = turns.array("data").reversed()
        for (row in rows) {
            val turn = row.jsonObject
            require(turn.string("itemsView") != "notLoaded")
            for (item in turn.array("items")) {
                val o = item.jsonObject
                when(o.string("type")) {
                    "userMessage" -> o.array("content").mapNotNull { (it as? JsonObject)?.string("text") }
                        .joinToString("\n").takeIf { it.isNotBlank() }?.let { messages += CloudMessage("user", it) }
                    "agentMessage" -> o.string("text").takeIf { it.isNotBlank() }?.let { messages += CloudMessage("assistant", it) }
                }
            }
        }
        val latest = turns.array("data").firstOrNull() as? JsonObject
        val status = when(latest?.string("status")) {
            "inProgress" -> "running"; "failed", "interrupted" -> "failed"; "completed" -> "completed"; else -> task(thread).status
        }
        return CloudDetails(task(thread).copy(status = status), messages, historyCursor = turns.string("nextCursor"),
            activeTurnId = if(status == "running") latest?.string("id").orEmpty() else "",latestTurnId = latest?.string("id").orEmpty())
    }
    fun createConfig(name: String, repos: List<CloudRepositoryRef>): JsonObject {
        require(name.isNotBlank() && name.length <= 120 && repos.isNotEmpty())
        require(repos.all { it.repository_id.matches(Regex("github-[0-9]+")) && CloudWire.validBranch(it.ref) })
        return buildJsonObject {
            put("name", name.trim()); put("share_settings", "private"); put("start_onboarding", false)
            putJsonArray("repositories") { repos.forEach { r -> add(buildJsonObject { put("repository_id", r.repository_id); put("ref", r.ref) }) } }
            putJsonObject("network_policy") { put("type", "restricted"); putJsonArray("presets") { add("package_managers") }; putJsonArray("egress_rules") {} }
        }
    }
    fun threadStart(configId: String, onboarding: Boolean) = buildJsonObject {
        require(validId(configId))
        put("serviceName", "codex_cloud"); put("threadSource", "user")
        putJsonArray("environments") { add(buildJsonObject { put(if(onboarding) "onboardingConfigId" else "environmentConfigId", configId) }) }
        put("deferredEnvironment", true); putJsonObject("pluginsMcp") { put("productSku", "codex") }
    }
    fun turnStart(threadId: String, prompt: String, choice: CloudPreparationChoice? = null): JsonObject {
        require(validId(threadId) && CloudWire.validPrompt(prompt))
        return buildJsonObject { put("threadId", threadId); putJsonArray("input") {
            add(buildJsonObject { put("type", "text"); put("text", prompt); putJsonArray("text_elements") {} })
        }; choice?.let { require(validId(it.model) && it.effort.isNotBlank()); put("model",it.model); put("effort",it.effort) } }
    }
    fun replyRequest(threadId: String, prompt: String, choice: CloudPreparationChoice, active: Boolean, expectedTurnId: String): Pair<String,JsonObject> {
        if(!active) return "turn/start" to turnStart(threadId,prompt,choice)
        require(validId(expectedTurnId))
        // Steering appends input to the running turn. Model changes apply only to a new turn.
        return "turn/steer" to buildJsonObject {
            put("threadId",threadId); put("expectedTurnId",expectedTurnId)
            put("input",turnStart(threadId,prompt).getValue("input"))
        }
    }
    fun replyTurnId(result: JsonObject): String = ((result["turn"] as? JsonObject)?.string("id").orEmpty()
        .ifBlank { result.string("turnId") }).takeIf { validId(it) } ?: throw CloudException(CloudErrorKind.UNCERTAIN)
    fun reconcileReply(details: CloudDetails, expectedTurnId: String, prompt: String): CloudDetails {
        if(expectedTurnId.isBlank() || details.latestTurnId == expectedTurnId) return details
        // A just-accepted turn can precede HTTP history persistence. Keep polling the expected turn.
        return details.copy(task = details.task.copy(status = "running"),activeTurnId = expectedTurnId,
            messages = if(prompt.isBlank() || details.messages.any { it.role == "user" && it.text == prompt }) details.messages
                else details.messages + CloudMessage("user",prompt))
    }
    fun draftPatch(config: CloudConfig, install: String, skill: String, network: String) = buildJsonObject {
        val draft = config.draft ?: error("Missing draft")
        require(install.toByteArray().size <= 65536 && skill.toByteArray().size <= 65536)
        require(network in setOf("restricted", "unrestricted", "disabled"))
        put("base_version_id", draft.string("base_version_id")); put("expected_revision", draft.getValue("revision"))
        put("install_script", install); put("start_skill", skill)
        // Keep provider domain rules and presets when retaining a restricted policy.
        put("network_policy", if(network == (draft["network_policy"] as? JsonObject)?.string("type")) draft.getValue("network_policy")
            else buildJsonObject { put("type", network); if(network == "restricted") {
                putJsonArray("presets") { add("package_managers") }; putJsonArray("egress_rules") {}
            } })
    }
    fun JsonObject.string(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
    fun JsonObject.array(key: String) = this[key] as? JsonArray ?: JsonArray(emptyList())
}
