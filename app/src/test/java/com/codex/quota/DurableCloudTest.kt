package com.codex.quota

import com.codex.quota.data.cloud.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class DurableCloudTest {
    private val config = """{"id":"workspace~asenvcfg_fixture","name":"Fixture","version_id":"w~cecfgver_a","version_revision":1,"repositories":[{"repository_id":"github-123","ref":"dev"}],"draft":{"id":"w~cecfgdraft_a","base_version_id":"w~cecfgver_a","revision":4,"network_policy":{"type":"restricted","presets":["package_managers"],"egress_rules":[{"host":"example.org"}]},"secrets":[{"id":"untouched"}]}}"""
    @Test fun `new config ID is opaque and version one is not published`() {
        val c = DurableCloudWire.config(DurableCloudWire.objectOf(config))
        assertFalse(c.published)
        assertTrue(DurableCloudWire.validId(c.id))
        assertFalse(CloudWire.validId(c.id))
        assertEquals("dev",c.repositories.single().ref)
    }
    @Test fun `draft save preserves restrictions and uses exact revision without replacing secrets`() {
        val c = DurableCloudWire.config(DurableCloudWire.objectOf(config))
        val patch = DurableCloudWire.draftPatch(c,"install","start","restricted")
        assertEquals(4,patch["expected_revision"]!!.jsonPrimitive.int)
        assertEquals(c.draft!!["network_policy"],patch["network_policy"])
        assertFalse(patch.containsKey("secrets"))
        assertFalse(patch.containsKey("repositories"))
    }
    @Test fun `durable allocation uses config selection and onboarding service`() {
        val start = DurableCloudWire.threadStart("w~asenvcfg_a",true)
        assertEquals("codex_cloud",start["serviceName"]!!.jsonPrimitive.content)
        assertEquals("w~asenvcfg_a",start["environments"]!!.jsonArray.single().jsonObject["onboardingConfigId"]!!.jsonPrimitive.content)
        assertFalse(start.containsKey("environment_id"))
    }
    @Test fun `preparation choice uses provider capabilities and sends explicit model and effort`() {
        val models = DurableCloudWire.models(DurableCloudWire.objectOf("""{"data":[{"model":"provider-model","displayName":"Provider model","supportedReasoningEfforts":[{"reasoningEffort":"low"},{"reasoningEffort":"ultra"}],"defaultReasoningEffort":"low","isDefault":true},{"model":"hidden-model","hidden":true}]}"""))
        assertEquals(1,models.size)
        assertEquals(CloudPreparationChoice("provider-model","low"),DurableCloudWire.chooseModel(models,null))
        val selected = CloudPreparationChoice("provider-model","ultra")
        assertTrue(DurableCloudWire.validChoice(selected,models))
        assertFalse(DurableCloudWire.validChoice(selected.copy(effort = "max"),models))
        val turn = DurableCloudWire.turnStart("thread-fixture","Prepare",selected)
        assertEquals("provider-model",turn["model"]!!.jsonPrimitive.content)
        assertEquals("ultra",turn["effort"]!!.jsonPrimitive.content)
        assertFalse(turn.containsKey("reasoningEffort"))
    }
    @Test fun `active followup steers exact turn while idle followup starts a new selected model turn`() {
        val choice = CloudPreparationChoice("provider-model","high")
        val (method,params) = DurableCloudWire.replyRequest("thread-a","Follow up",choice,true,"turn-active")
        assertEquals("turn/steer",method)
        assertEquals("turn-active",params["expectedTurnId"]!!.jsonPrimitive.content)
        assertFalse(params.containsKey("model")); assertFalse(params.containsKey("effort"))
        assertThrows(IllegalArgumentException::class.java) { DurableCloudWire.replyRequest("thread-a","Follow up",choice,true,"") }
        assertEquals("turn-active",DurableCloudWire.replyTurnId(buildJsonObject { put("turnId","turn-active") }))
        val (next,nextParams) = DurableCloudWire.replyRequest("thread-a","Follow up",choice,false,"")
        assertEquals("turn/start",next); assertEquals("provider-model",nextParams["model"]!!.jsonPrimitive.content)
        assertEquals("turn-next",DurableCloudWire.replyTurnId(buildJsonObject { putJsonObject("turn") { put("id","turn-next") } }))
    }
    @Test fun `stale completed history cannot stop polling a newly accepted turn`() {
        val old = CloudDetails(CloudTask("thread-a","Setup","completed"),listOf(CloudMessage("assistant","Old reply")),latestTurnId = "old-turn")
        val waiting = DurableCloudWire.reconcileReply(old,"new-turn","New question")
        assertEquals("running",waiting.task.status)
        assertEquals("New question",waiting.messages.last().text)
        val arrived = old.copy(latestTurnId = "new-turn",messages = listOf(CloudMessage("assistant","New reply")))
        assertEquals(arrived,DurableCloudWire.reconcileReply(arrived,"new-turn","New question"))
    }
    @Test fun `history must be hydrated and newest turn status controls result`() {
        val thread = DurableCloudWire.objectOf("""{"id":"thread-a","status":{"type":"idle"},"updatedAt":1234}""")
        val turns = DurableCloudWire.objectOf("""{"data":[{"id":"turn-b","status":"inProgress","itemsView":"full","items":[{"type":"userMessage","content":[{"type":"text","text":"Latest"}]}]},{"id":"turn-a","status":"completed","itemsView":"full","items":[{"type":"agentMessage","text":"Previous"}]}],"nextCursor":"next"}""")
        val d = DurableCloudWire.details(thread,turns)
        assertEquals("running",d.task.status)
        assertEquals("turn-b",d.activeTurnId)
        assertEquals(listOf("Previous","Latest"),d.messages.map { it.text })
        assertEquals(1234000,d.task.updatedAt)
        assertEquals("next",d.historyCursor)
        assertTrue(runCatching { DurableCloudWire.details(thread,DurableCloudWire.objectOf("""{"data":[{"itemsView":"notLoaded"}]}""")) }.isFailure)
    }
    @Test fun `native HTTP uses separate official host and never retries unknown writes`() = runBlocking {
        var captured: Request? = null
        val api = DurableCloudApi(OkHttpClient.Builder().addInterceptor {
            captured = it.request()
            Response.Builder().request(it.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .header("Content-Type","application/json").body("{}".toResponseBody()).build()
        }.build())
        api.get("credential-fixture","workspace-a",listOf("v1","environment-configs","w~config_a"))
        assertEquals("codex-cloud-backend.chatgpt.com",captured!!.url.host)
        assertEquals("/v1/environment-configs/w~config_a",captured!!.url.encodedPath)
        assertEquals("workspace-a",captured!!.header("ChatGPT-Account-ID"))
        api.get("credential-fixture","workspace-a",listOf("list-repositories"),github = true)
        assertEquals("chatgpt.com",captured!!.url.host)
        assertEquals("/backend-api/wham/github/list-repositories",captured!!.url.encodedPath)
        var calls = 0
        val broken = DurableCloudApi(OkHttpClient.Builder().addInterceptor { calls++; throw IOException("secret fixture") }.build())
        val error = runCatching { broken.write("credential-fixture","workspace-a","POST",listOf("v1","environment-configs")) }.exceptionOrNull() as CloudException
        assertEquals(1,calls); assertEquals(CloudErrorKind.UNCERTAIN,error.kind); assertFalse(error.toString().contains("secret fixture"))
    }
}
