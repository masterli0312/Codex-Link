package com.codex.quota

import com.codex.quota.notifications.task.*
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ConversationEnvironmentRulesTest {
    private val host = "11111111-2222-3333-4444-555555555555"
    private val computer = ComputerConnection("one", host, "Real computer", "https://example.test/one", "key", 0)
    private val project = RemoteThreadSummary("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", project_id = "a".repeat(64),
        project_name = "Real project", project_path = "C:\\work\\project")
    private fun anchor() = TaskInboxRecord("anchor", 0, "anchor", TaskInbox.hash(computer.endpoint),
        TaskConversationSnapshot(), libraryAnchor = true, computerHostId = host, threadCatalog = listOf(project, project.copy(title = "other")))

    @Test fun anotherComputerOrPairingCannotSupplyTheProjectCatalog() {
        assertEquals(listOf(project), ConversationEnvironmentRules.projects(anchor(), computer))
        assertTrue(ConversationEnvironmentRules.projects(anchor(), computer.copy(hostId = "other")).isEmpty())
        assertTrue(ConversationEnvironmentRules.projects(anchor(), computer.copy(endpoint = "https://example.test/two")).isEmpty())
        assertTrue(ConversationEnvironmentRules.projects(anchor().copy(libraryAnchor = false), computer).isEmpty())
        assertTrue(ConversationEnvironmentRules.projects(null, computer).isEmpty())
    }

    @Test fun removedProjectsFallBackToNoProjectAndNeverAnotherProjectsIdentity() {
        assertEquals(project.project_id, ConversationEnvironmentRules.resolveProject(project.project_id, listOf(project)))
        assertEquals("default", ConversationEnvironmentRules.resolveProject("b".repeat(64), listOf(project)))
        assertEquals("default", ConversationEnvironmentRules.resolveProject("default", listOf(project)))
        assertTrue(ConversationEnvironmentRules.validProject("default"))
        assertTrue(ConversationEnvironmentRules.validProject(project.project_id))
        assertFalse(ConversationEnvironmentRules.validProject("C:\\arbitrary"))
        assertFalse(ConversationEnvironmentRules.validProject(""))
    }

    @Test fun oldPreferencesAndThreadCatalogsRemainReadableWithoutInventedPaths() {
        val preferences = Json.decodeFromString<ConversationPreferences>("{\"draft\":\"draft\",\"model\":\"real\"}")
        assertEquals("draft", preferences.draft)
        assertEquals("default", preferences.project)
        val thread = Json.decodeFromString<RemoteThreadSummary>("{\"thread_id\":\"thread\",\"project_name\":\"old\"}")
        assertEquals("", thread.project_path)
    }
}
