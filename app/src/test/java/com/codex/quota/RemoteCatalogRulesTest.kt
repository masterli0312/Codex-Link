package com.codex.quota

import com.codex.quota.notifications.task.*
import org.junit.Assert.*
import org.junit.Test

class RemoteCatalogRulesTest {
    private val now = 100_000L
    private fun command(action: String = "threads") = RemoteCommand("request", action, "host", "host", "catalog", "library", now - 1_000)
    private fun anchor() = TaskInboxRecord("a", now, "library", "endpoint", TaskConversationSnapshot(),
        libraryAnchor = true, threadCatalogAt = now - 5_000)
    @Test fun returningToFreshCatalogIncludingEmptyCatalogDoesNotReload() {
        assertFalse(RemoteCatalogRules.shouldRefresh(anchor(), false, "", now))
        assertTrue(RemoteCatalogRules.shouldRefresh(anchor().copy(threadCatalogAt = now - 61_000), false, "", now))
        assertTrue(RemoteCatalogRules.shouldRefresh(anchor().copy(threadCatalogAt = 0), false, "", now))
        assertFalse(RemoteCatalogRules.shouldRefresh(anchor().copy(libraryAnchor = false), false, "", now))
    }
    @Test fun archivedAndSearchCatalogsCannotStandInForAnotherQuery() {
        assertTrue(RemoteCatalogRules.shouldRefresh(anchor().copy(threadCatalogArchived = true), false, "", now))
        assertTrue(RemoteCatalogRules.shouldRefresh(anchor().copy(threadCatalogSearch = "old"), false, "new", now))
        assertFalse(RemoteCatalogRules.shouldRefresh(anchor().copy(threadCatalogSearch = "new"), false, "new", now))
    }
    @Test fun currentSyncIsNotDuplicatedAndPendingCreateIsNotOverwritten() {
        val pending = anchor().copy(threadCatalogAt = 0, libraryRequest = command())
        assertFalse(RemoteCatalogRules.shouldRefresh(pending, false, "", now))
        assertTrue(RemoteCatalogRules.shouldRefresh(pending.copy(libraryRequest = command().copy(issued_at = now - 46_000)), false, "", now))
        assertFalse(RemoteCatalogRules.shouldRefresh(pending.copy(libraryRequest = command("create")), false, "", now))
    }
    @Test fun modelCacheSurvivesReturnAndRetriesOnlyWhenStaleOrIncomplete() {
        val result = RemoteEvent("models", "request", "host", "host", "catalog", "models", 1, now - 1_000)
        assertFalse(RemoteCatalogRules.shouldLoadModels(anchor().copy(modelCatalog = result), now))
        assertTrue(RemoteCatalogRules.shouldLoadModels(anchor().copy(modelCatalog = result.copy(at = now - 301_000)), now))
        assertTrue(RemoteCatalogRules.shouldLoadModels(anchor().copy(modelCatalog = result.copy(partial = true)), now))
        assertFalse(RemoteCatalogRules.shouldLoadModels(anchor().copy(modelRequest = command("models")), now))
    }
}
