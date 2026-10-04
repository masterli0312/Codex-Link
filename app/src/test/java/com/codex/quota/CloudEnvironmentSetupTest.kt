package com.codex.quota

import com.codex.quota.data.cloud.*
import org.junit.Assert.*
import org.junit.Test
import java.net.URI

class CloudEnvironmentSetupTest {
    private val identity = CloudIdentity("account-a", "workspace-a")
    @Test fun `official configuration URL carries no credentials or account identity`() {
        val url = URI(CloudEnvironmentSetup.url)
        assertEquals("https", url.scheme)
        assertEquals("chatgpt.com", url.host)
        assertEquals("/settings/codex-cloud", url.path)
        assertNull(url.rawQuery); assertNull(url.userInfo); assertNull(url.fragment)
    }
    @Test fun `refresh keeps selection and does not replace draft branch or task history`() {
        val cache = CloudCache(identity, selectedEnvironment = "chosen", draft = "unsent prompt", branch = "feature/mobile",
            page = CloudPage(listOf(CloudTask("task-a", "Test"))))
        val result = CloudEnvironmentSetup.reconcile(cache, identity,
            listOf(CloudEnvironment("new", "New"), CloudEnvironment("chosen", "Updated")))
        assertEquals("chosen", result.selectedEnvironment)
        assertEquals(cache.draft, result.draft); assertEquals(cache.branch, result.branch); assertEquals(cache.page, result.page)
    }
    @Test fun `removed environment cannot remain a selectable stale target`() {
        val cache = CloudCache(identity, selectedEnvironment = "removed")
        assertEquals("new", CloudEnvironmentSetup.reconcile(cache, identity, listOf(CloudEnvironment("new"))).selectedEnvironment)
        assertEquals("", CloudEnvironmentSetup.reconcile(cache, identity, emptyList()).selectedEnvironment)
    }
    @Test fun `another workspace response cannot be applied to current account cache`() {
        assertThrows(IllegalArgumentException::class.java) {
            CloudEnvironmentSetup.reconcile(CloudCache(identity), CloudIdentity("account-a", "workspace-b"), listOf(CloudEnvironment("wrong")))
        }
    }
}
