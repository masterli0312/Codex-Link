package com.codex.quota

import com.codex.quota.notifications.task.ConversationFileCache
import org.junit.Assert.*
import org.junit.Test

class ConversationFileCacheTest {
    @Test fun repeatedReadsReuseOnlyAnUnchangedFile() {
        val cache = ConversationFileCache<String>(50, 1000)
        cache.remember("pair-a/chat", 1, 20, "latest")
        repeat(100) { assertEquals("latest", cache.get("pair-a/chat", 1, 20)) }
        assertNull(cache.get("pair-a/chat", 2, 20))
        assertNull(cache.get("pair-a/chat", 1, 20))
        cache.remember("pair-a/chat", 2, 20, "replaced")
        assertNull(cache.get("pair-a/chat", 2, 21))
    }

    @Test fun pairingsAndChangedOptionsCannotShareOrRestoreAValue() {
        val cache = ConversationFileCache<String>(50, 1000)
        cache.remember("pair-a/chat", 1, 20, "model-a")
        cache.remember("pair-b/chat", 1, 20, "model-b")
        cache.remember("pair-a/chat", 1, 20, "new-choice")
        assertEquals("new-choice", cache.get("pair-a/chat", 1, 20))
        assertEquals("model-b", cache.get("pair-b/chat", 1, 20))
        cache.clear()
        assertNull(cache.get("pair-a/chat", 1, 20))
        assertNull(cache.get("pair-b/chat", 1, 20))
    }

    @Test fun countAndByteLimitsEvictLeastRecentlyUsedData() {
        val cache = ConversationFileCache<String>(2, 50)
        cache.remember("a", 1, 20, "a")
        cache.remember("b", 1, 20, "b")
        assertEquals("a", cache.get("a", 1, 20))
        cache.remember("c", 1, 20, "c")
        assertNull(cache.get("b", 1, 20))
        cache.remember("d", 1, 40, "d")
        assertNull(cache.get("a", 1, 20))
        assertNull(cache.get("c", 1, 20))
        assertEquals("d", cache.get("d", 1, 40))
        cache.remember("d", 1, 100, "too large")
        assertNull(cache.get("d", 1, 40))
    }
}
