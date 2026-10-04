package com.codex.quota

import com.codex.quota.notifications.task.*
import org.junit.Assert.*
import org.junit.Test

class ConversationLookupIndexTest {
    private fun record(id: String = "record", pair: String = "pair", conversation: String = "chat", host: String = "computer") =
        TaskInboxRecord(id, 0, "", pair, TaskConversationSnapshot(conversation_id = conversation,
            thread_ref = RemoteThreadRef(host, "thread", "turn")))

    @Test fun lookupNeverAssumesAnUnseenOrChangedFileMatches() {
        val index = ConversationLookupIndex()
        assertNull(index.matches("file", 1, 50, record()))
        index.remember("file", 1, 50, record())
        assertEquals(true, index.matches("file", 1, 50, record()))
        assertNull(index.matches("file", 2, 50, record()))
        index.remember("file", 2, 50, record())
        assertNull(index.matches("file", 2, 51, record()))
    }

    @Test fun routingMetadataPreservesPairingConversationAndComputerIsolation() {
        val index = ConversationLookupIndex()
        index.remember("file", 1, 50, record())
        assertEquals(false, index.matches("file", 1, 50, record(pair = "other")))
        assertEquals(false, index.matches("file", 1, 50, record(conversation = "other")))
        assertEquals(false, index.matches("file", 1, 50, record(host = "other")))
        assertEquals(true, index.matches("file", 1, 50, record(host = "")))
    }

    @Test fun replacingIdentityAndClearingCannotReuseOldRoutes() {
        val index = ConversationLookupIndex()
        index.remember("file", 1, 50, record())
        index.remember("file", 1, 50, record(conversation = "changed"))
        assertEquals(false, index.matches("file", 1, 50, record()))
        index.forget("file")
        assertNull(index.matches("file", 1, 50, record()))
        index.remember("file", 1, 50, record())
        index.clear()
        assertNull(index.matches("file", 1, 50, record()))
    }

    @Test fun indexIsBoundedAndUnknownEvictedRecordsRequireAuthoritativeReads() {
        val index = ConversationLookupIndex(2)
        index.remember("a", 1, 50, record())
        index.remember("b", 1, 50, record())
        index.matches("a", 1, 50, record())
        index.remember("c", 1, 50, record())
        assertNull(index.matches("b", 1, 50, record()))
        assertEquals(true, index.matches("a", 1, 50, record()))
    }
}
