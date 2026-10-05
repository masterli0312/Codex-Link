package com.codex.quota

import com.codex.quota.data.cloud.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class CloudThreadManagementTest {
    private val identity = CloudIdentity("credential", "workspace")
    private fun cache() = CloudCache(identity, page = CloudPage(listOf(CloudTask("one", "old"), CloudTask("two", "other"))),
        details = listOf(CloudDetails(CloudTask("one", "old"), listOf(CloudMessage("user", "original message")))), pinnedThreads = setOf("one"))
    @Test fun writesUseOfficialThreadRpcAndTrimNames() {
        val (method, params) = CloudThreadManagement.request("rename", "one", " New title ")
        assertEquals("thread/name/set", method); assertEquals("New title", params["name"]?.jsonPrimitive?.content)
        val archive = CloudThreadManagement.request("archive", "one")
        assertEquals("thread/archive", archive.first); assertEquals(setOf("threadId"), archive.second.keys)
        for ((action, name) in listOf("delete" to "", "rename" to "\n", "rename" to "x".repeat(241))) {
            assertTrue(runCatching { CloudThreadManagement.request(action, "one", name) }.isFailure)
        }
    }
    @Test fun acknowledgedRenameUpdatesHeaderAndListWithoutChangingMessagesOrIdentity() {
        val before = cache(); val after = CloudThreadManagement.apply(before, "rename", "one", "New title")
        assertEquals(identity, after.identity); assertEquals("New title", after.page.items.first().title)
        assertEquals("New title", after.details.single().task.title)
        assertEquals(before.details.single().messages, after.details.single().messages)
        assertEquals(before.page.items.last(), after.page.items.last())
    }
    @Test fun acknowledgedArchiveRemovesOnlyItsOwnCachedConversation() {
        val before = cache(); val after = CloudThreadManagement.apply(before, "archive", "one")
        assertEquals(listOf("two"), after.page.items.map { it.id })
        assertEquals(listOf("one"), after.archivedPage.items.map { it.id })
        assertEquals(before.details, after.details)
        assertTrue(after.pinnedThreads.isEmpty()); assertEquals(identity, after.identity)
        val other = before.copy(identity = CloudIdentity("another-credential", "another-workspace"))
        assertEquals("old", other.page.items.first().title)
        val restored = CloudThreadManagement.apply(after, "unarchive", "one")
        assertEquals("thread/unarchive", CloudThreadManagement.request("unarchive", "one").first)
        assertEquals(setOf("one", "two"), restored.page.items.map { it.id }.toSet())
        assertTrue(restored.archivedPage.items.isEmpty())
    }
}
