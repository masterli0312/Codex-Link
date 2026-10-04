package com.codex.quota

import com.codex.quota.data.cloud.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class CloudWireTest {
    @Test fun environmentCatalogUsesRealIdsWithoutInventingProjects() {
        assertTrue(CloudWire.environments("[]").isEmpty())
        val list = CloudWire.environments("""[{"id":"env_a","label":"A"},{"id":"env_b","label":"B","is_pinned":true}]""")
        assertEquals(listOf("env_b", "env_a"), list.map { it.id })
        assertEquals("B", list.first().label)
    }
    @Test fun creationMatchesOfficialClientWithoutLocalModelsOrPermissions() {
        val body = Json.parseToJsonElement(CloudWire.createBody("env_a", "feature/mobile", "说明 \"Cloud\"\n下一行")).jsonObject
        assertEquals("env_a", body["new_task"]!!.jsonObject["environment_id"]!!.jsonPrimitive.content)
        assertEquals("feature/mobile", body["new_task"]!!.jsonObject["branch"]!!.jsonPrimitive.content)
        assertFalse(body["new_task"]!!.jsonObject["run_environment_in_qa_mode"]!!.jsonPrimitive.boolean)
        assertEquals("说明 \"Cloud\"\n下一行", body["input_items"]!!.jsonArray.first().jsonObject["content"]!!.jsonArray.first().jsonObject["text"]!!.jsonPrimitive.content)
        assertFalse(body.containsKey("model")); assertFalse(body.containsKey("approval_policy"))
        assertEquals("task_a", CloudWire.createdId("""{"task":{"id":"task_a"}}"""))
        assertEquals("task_b", CloudWire.createdId("""{"id":"task_b"}"""))
    }
    @Test fun listReadsOfficialSecondsAndStatusDoesNotAssumeCompletion() {
        val page = CloudWire.page("""{"items":[{"id":"task_a","title":"Work","updated_at":1700000000.125,"task_status_display":{"latest_turn_status_display":{"turn_status":"completed"}}},{"id":"task_b","title":"Other"}],"cursor":"next&cursor"}""")
        assertEquals(1700000000125L, page.items.first().updatedAt)
        assertEquals("completed", page.items.first().status)
        assertEquals("unknown", page.items.last().status)
        assertEquals("next&cursor", page.cursor)
    }
    @Test fun taskResultsReadTypedTextAndDiffWithoutRenderingOtherPayloads() {
        val result = CloudWire.details("""{"task":{"id":"task_a","title":"Fixture"},"task_status_display":{"state":"ready"},"current_user_turn":{"input_items":[{"type":"message","role":"user","content":[{"content_type":"text","text":"Question"}]}]},"current_assistant_turn":{"output_items":[{"type":"message","content":[{"content_type":"text","text":"Reply"},{"content_type":"image","text":"hidden"}]},{"type":"pr","output_diff":{"diff":"diff --git"}}],"worklog":{"messages":[{"author":{"role":"assistant"},"content":{"parts":["Worklog"]}},{"author":{"role":"tool"},"content":{"parts":["secret tool output"]}}]}}}""")
        assertEquals(listOf("Question", "Reply", "Worklog"), result.messages.map { it.text })
        assertEquals("diff --git", result.diff)
        assertEquals("completed", result.task.status)
    }
    @Test fun workspaceAndCredentialScopesCannotShareCacheOrSubmissionIdentity() {
        val first = CloudIdentity("local-one", "workspace-one")
        val second = CloudIdentity("local-two", "workspace-one")
        val third = CloudIdentity("local-one", "workspace-two")
        assertEquals(3, setOf(first.key, second.key, third.key).size)
        assertNotEquals(CloudWire.fingerprint(first,"env","main","Hi"), CloudWire.fingerprint(second,"env","main","Hi"))
        assertNotEquals(CloudWire.fingerprint(first,"env","main","Hi"), CloudWire.fingerprint(third,"env","main","Hi"))
    }
    @Test fun malformedEnvironmentBranchAndPromptCannotCreateTask() {
        listOf("../bad", "https://host/", "", "a\nb").forEach { assertFalse(CloudWire.validId(it)) }
        listOf("", "--option", "a..b", "x@{y", "a b", "a.lock", "foo//bar").forEach { assertFalse(CloudWire.validBranch(it)) }
        assertTrue(CloudWire.validBranch("feature/mobile")); assertTrue(CloudWire.validBranch("main"))
        assertFalse(CloudWire.validPrompt(" ")); assertFalse(CloudWire.validPrompt("a".repeat(16385)))
        assertThrows(IllegalArgumentException::class.java) { CloudWire.createBody("../bad", "main", "Hi") }
        assertThrows(IllegalArgumentException::class.java) { CloudWire.createdId("""{"ok":true}""") }
    }
}
