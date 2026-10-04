package com.codex.quota.data.cloud

import com.codex.quota.auth.AccountAccessTokenProvider
import com.codex.quota.auth.JwtTokenParser
import com.codex.quota.domain.model.isApiKeyPlan
import com.codex.quota.domain.repository.CodexAccountRepository
import kotlinx.serialization.json.*
import com.codex.quota.data.cloud.DurableCloudWire.string
import com.codex.quota.data.cloud.DurableCloudWire.array
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class CloudRepository(private val accounts: CodexAccountRepository, private val tokens: AccountAccessTokenProvider,
    private val api: CloudApi = CloudApi(), private val durable: DurableCloudApi = DurableCloudApi()) {
    suspend fun identity(accountId: String): CloudIdentity {
        val account = accounts.getAccount(accountId)?.account ?: throw CloudException(CloudErrorKind.LOGIN)
        if (account.isDemoAccount || account.planType.isApiKeyPlan) throw CloudException(CloudErrorKind.LOGIN)
        val token = tokens.fresh(accountId).getOrElse { throw credentialError(it) }
        val workspace = JwtTokenParser.parseToken(token)?.chatgptAccountId ?: throw CloudException(CloudErrorKind.LOGIN)
        return CloudIdentity(accountId, workspace)
    }
    suspend fun environments(identity: CloudIdentity): List<CloudEnvironment> = read(identity) { token ->
        val result = mutableListOf<CloudEnvironment>()
        for(scope in listOf("user", "workspace")) {
            var cursor = ""; val seen = mutableSetOf<String>()
            do {
                val page = durable.get(token, identity.workspaceId, listOf("v1", "environment-configs"),
                    mapOf("scope" to scope, "limit" to "100", "omitDraft" to "true") + if(cursor.isBlank()) emptyMap() else mapOf("cursor" to cursor))
                result += page.array("data").map { DurableCloudWire.environment(it.jsonObject) }
                cursor = page.string("next_cursor")
                if(cursor.isNotBlank() && (!seen.add(cursor) || result.size > 1000)) throw CloudException(CloudErrorKind.RESPONSE)
            } while(cursor.isNotBlank())
        }
        result.distinctBy { it.id }.sortedBy { it.label.lowercase() }
    }
    suspend fun tasks(identity: CloudIdentity, cursor: String = "") = read(identity) { token ->
        val page = durable.get(token, identity.workspaceId, listOf("v1", "threads"), mapOf("limit" to "20") + if(cursor.isBlank()) emptyMap() else mapOf("cursor" to cursor))
        CloudPage(page.array("data").map { DurableCloudWire.task(it.jsonObject) }, page.string("nextCursor"))
    }
    suspend fun preparationModels(identity: CloudIdentity): List<CloudPreparationModel> = rpc(identity) { socket ->
        val result = mutableListOf<CloudPreparationModel>(); val seen = mutableSetOf<String>(); var cursor = ""
        do {
            val page = socket.request("model/list",buildJsonObject {
                put("limit",100); put("includeHidden",false); if(cursor.isNotBlank()) put("cursor",cursor)
            },mutation = false)
            result += DurableCloudWire.models(page); cursor = page.string("nextCursor")
            if(cursor.isNotBlank() && (!seen.add(cursor) || result.size > 1000)) throw CloudException(CloudErrorKind.RESPONSE)
        } while(cursor.isNotBlank())
        result.distinctBy { it.model }
    }
    suspend fun details(identity: CloudIdentity, id: String) = read(identity) { token ->
        val thread = durable.get(token, identity.workspaceId, listOf("v1", "threads", id))["thread"]!!.jsonObject
        val turns = durable.get(token, identity.workspaceId, listOf("v2", "threads", id, "turns"), mapOf("limit" to "20", "itemsView" to "full", "sortDirection" to "desc"))
        DurableCloudWire.details(thread, turns)
    }
    fun watchDetails(identity: CloudIdentity, id: String): Flow<CloudDetails> = flow {
        require(DurableCloudWire.validId(id))
        val socket = CloudRpc(checkedToken(identity), identity.workspaceId)
        try {
            socket.initialize()
            val thread = socket.request("thread/resume", buildJsonObject {
                put("threadId", id); put("excludeTurns", false)
            }, mutation = false)["thread"] as? JsonObject ?: throw CloudException(CloudErrorKind.RESPONSE)
            if (thread.string("id") != id) throw CloudException(CloudErrorKind.RESPONSE)
            val projection = CloudConversationStream(thread)
            emit(projection.details())
            for (event in socket.events) projection.apply(event)?.let { emit(it) }
            throw CloudException(CloudErrorKind.NETWORK)
        } finally { socket.close() }
    }
    suspend fun create(identity: CloudIdentity, environment: String, branch: String, prompt: String): String {
        // Branch belongs to the published config. Never pass a config ID into legacy POST tasks.
        return startThread(identity, environment, false)
    }
    suspend fun config(identity: CloudIdentity, id: String, draftId: String = "") = read(identity) { token ->
        DurableCloudWire.config(durable.get(token, identity.workspaceId, listOf("v1", "environment-configs", id) +
            if(draftId.isBlank()) emptyList() else listOf("drafts", draftId)))
    }
    suspend fun repositories(identity: CloudIdentity): List<CloudGithubRepository> = read(identity) { token ->
        val repos = mutableListOf<CloudGithubRepository>()
        var page = 1
        do {
            val data = durable.get(token, identity.workspaceId, listOf("list-repositories"), mapOf("page" to page.toString(), "per_page" to "20"), github = true).array("repositories")
            repos += data.map { val o = it.jsonObject
                val id = o.string("id").ifBlank { o["id"]?.jsonPrimitive?.longOrNull?.toString().orEmpty() }
                CloudGithubRepository(if(id.startsWith("github-")) id else "github-$id", o.string("repository_full_name"), o.string("default_branch")) }
            if(data.size < 20) break
            if(++page > 50) throw CloudException(CloudErrorKind.RESPONSE)
        } while(true)
        repos.filter { it.id.matches(Regex("github-[0-9]+")) && CloudWire.validBranch(it.branch) }.distinctBy { it.id }
    }
    suspend fun createConfig(identity: CloudIdentity, name: String, repos: List<CloudRepositoryRef>) = write(identity) { token ->
        DurableCloudWire.config(durable.write(token, identity.workspaceId, "POST", listOf("v1", "environment-configs"), DurableCloudWire.createConfig(name,repos)))
    }
    suspend fun openDraft(identity: CloudIdentity, id: String) = write(identity) { token ->
        durable.write(token, identity.workspaceId, "POST", listOf("v1", "environment-configs", id, "drafts"))
    }
    suspend fun saveDraft(identity: CloudIdentity, session: CloudSetupSession, patch: JsonObject) = write(identity) { token ->
        DurableCloudWire.config(durable.write(token, identity.workspaceId, "PATCH", draftPath(session), patch))
    }
    suspend fun beginPublish(identity: CloudIdentity, session: CloudSetupSession, revision: Int, key: String) = write(identity) { token ->
        durable.write(token, identity.workspaceId, "POST", draftPath(session) + listOf("approve", "begin"), buildJsonObject {
            put("expected_revision", revision); put("idempotency_key", key)
        })
    }
    suspend fun operation(identity: CloudIdentity, id: String) = read(identity) { token ->
        durable.get(token, identity.workspaceId, listOf("v1", "environment-operations", id))
    }
    suspend fun completePublish(identity: CloudIdentity, session: CloudSetupSession) = write(identity) { token ->
        DurableCloudWire.config(durable.write(token, identity.workspaceId, "POST", draftPath(session) + listOf("approve", "complete"), buildJsonObject {
            put("operation_id", session.operationId); if(session.explicitDraft) put("thread_id", session.threadId)
        }))
    }
    private fun draftPath(s: CloudSetupSession) = listOf("v1", "environment-configs", s.configId) +
        if(s.explicitDraft) listOf("drafts", s.draftId) else listOf("draft")
    suspend fun startThread(identity: CloudIdentity, configId: String, onboarding: Boolean): String = rpc(identity) { socket ->
        val result = socket.request("thread/start", DurableCloudWire.threadStart(configId,onboarding))
        (result["thread"] as? JsonObject)?.string("id")?.takeIf { DurableCloudWire.validId(it) } ?: throw CloudException(CloudErrorKind.UNCERTAIN)
    }
    suspend fun turn(identity: CloudIdentity, threadId: String, prompt: String) = rpc(identity) { socket ->
        val resumed = socket.request("thread/resume", buildJsonObject { put("threadId", threadId); put("excludeTurns", true) }, mutation = false)
        val thread = resumed["thread"]?.jsonObject ?: throw CloudException(CloudErrorKind.RESPONSE)
        if(thread.string("id") != threadId || (thread["status"] as? JsonObject)?.string("type") == "active") throw CloudException(CloudErrorKind.RESPONSE)
        socket.request("turn/start", DurableCloudWire.turnStart(threadId,prompt))
    }
    suspend fun prepareTurn(identity: CloudIdentity, threadId: String, prompt: String, choice: CloudPreparationChoice) = rpc(identity) { socket ->
        val thread = socket.request("thread/resume",buildJsonObject { put("threadId",threadId); put("excludeTurns",true) },mutation = false)["thread"]?.jsonObject
            ?: throw CloudException(CloudErrorKind.RESPONSE)
        if(thread.string("id") != threadId || (thread["status"] as? JsonObject)?.string("type") == "active") throw CloudException(CloudErrorKind.RESPONSE)
        socket.request("turn/start",DurableCloudWire.turnStart(threadId,prompt,choice))
    }
    suspend fun reply(identity: CloudIdentity, threadId: String, prompt: String, choice: CloudPreparationChoice, expectedTurnId: String = ""): String = rpc(identity) { socket ->
        val thread = socket.request("thread/resume",buildJsonObject { put("threadId",threadId); put("excludeTurns",true) },mutation = false)["thread"]?.jsonObject
            ?: throw CloudException(CloudErrorKind.RESPONSE)
        if(thread.string("id") != threadId) throw CloudException(CloudErrorKind.RESPONSE)
        val active = (thread["status"] as? JsonObject)?.string("type") == "active"
        if(active && !DurableCloudWire.validId(expectedTurnId)) throw CloudException(CloudErrorKind.RESPONSE)
        val (method,params) = DurableCloudWire.replyRequest(threadId,prompt,choice,active,expectedTurnId)
        DurableCloudWire.replyTurnId(socket.request(method,params))
    }
    suspend fun stop(identity: CloudIdentity,threadId: String,turnId: String) = rpc(identity) { socket ->
        require(DurableCloudWire.validId(threadId) && DurableCloudWire.validId(turnId))
        socket.request("turn/interrupt",buildJsonObject { put("threadId",threadId); put("turnId",turnId) })
    }
    private suspend fun <T> rpc(identity: CloudIdentity, block: suspend (CloudRpc) -> T): T {
        val socket = CloudRpc(checkedToken(identity), identity.workspaceId)
        return try { socket.initialize(); block(socket) } finally { socket.close() }
    }
    private suspend fun <T> write(identity: CloudIdentity, block: suspend (String) -> T): T {
        val token = checkedToken(identity)
        try { return block(token) }
        catch(e: CloudException) { if(e.kind == CloudErrorKind.LOGIN) tokens.fresh(identity.accountId, rejectedToken = token); throw e }
        catch(e: kotlinx.coroutines.CancellationException) { throw e }
        catch(_: Exception) { throw CloudException(CloudErrorKind.UNCERTAIN) }
    }
    private suspend fun checkedToken(identity: CloudIdentity): String {
        if (accounts.getAccount(identity.accountId) == null) throw CloudException(CloudErrorKind.LOGIN)
        val token = tokens.fresh(identity.accountId).getOrElse { throw credentialError(it) }
        if (JwtTokenParser.parseToken(token)?.chatgptAccountId != identity.workspaceId) throw CloudException(CloudErrorKind.LOGIN)
        return token
    }
    private suspend fun <T> read(identity: CloudIdentity, block: suspend (String) -> T): T {
        val token = checkedToken(identity)
        return try { block(token) } catch (e: CloudException) {
            if (e.kind != CloudErrorKind.LOGIN) throw e
            val refreshed = tokens.fresh(identity.accountId, rejectedToken = token).getOrElse { throw e }
            if (refreshed == token || JwtTokenParser.parseToken(refreshed)?.chatgptAccountId != identity.workspaceId) throw e
            block(refreshed)
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { throw CloudException(CloudErrorKind.RESPONSE) }
    }
    private fun credentialError(error: Throwable): CloudException {
        val rejected = error as? com.codex.quota.auth.OAuthTokenRefreshRejectedException
        return CloudException(when {
            rejected?.statusCode in listOf(400, 401) -> CloudErrorKind.LOGIN
            rejected?.statusCode == 429 -> CloudErrorKind.RATE_LIMIT
            error is java.io.IOException -> CloudErrorKind.NETWORK
            else -> CloudErrorKind.LOGIN
        })
    }
}
