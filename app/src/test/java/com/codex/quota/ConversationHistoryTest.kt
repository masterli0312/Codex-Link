package com.codex.quota

import com.codex.quota.notifications.task.*
import org.junit.Assert.*
import org.junit.Test

class ConversationHistoryTest {
    @Test fun overlappingHistoryPagesUseIdentityAndPreserveRepeatedUserMessages() {
        val a = TaskConversationMessage("user", "hello", "turn1:item1")
        val b = TaskConversationMessage("user", "hello", "turn2:item1")
        val c = TaskConversationMessage("assistant", "reply", "turn2:item2")
        assertEquals(listOf(a, b, c), ConversationHistory.merge(listOf(a, b), listOf(b, c)))
        assertEquals(2, ConversationHistory.merge(listOf(a.copy(id = "")), listOf(b.copy(id = ""))).size)
    }
}
