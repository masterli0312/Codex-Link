package com.codex.quota

import com.codex.quota.ui.feature.taskhistory.ConversationScrollPolicy as Policy
import org.junit.Assert.*
import org.junit.Test

class ConversationScrollPolicyTest {
    @Test fun `unchanged visible tail does not reset scroll position`() {
        repeat(20) { assertEquals(Policy.Step.None, Policy.follow(5, 5, 4, 800, 900, true, false)) }
    }
    @Test fun `stream growth moves only newly measured overflow`() {
        assertEquals(Policy.Step.Move(28), Policy.follow(5, 5, 4, 928, 900, true, false))
        assertEquals(Policy.Step.None, Policy.follow(5, 5, 4, 900, 900, true, false))
    }
    @Test fun `terminal row replacement waits for the matching layout`() {
        assertEquals(Policy.Step.None, Policy.follow(6, 5, 4, 1000, 900, true, false))
        assertEquals(Policy.Step.Reveal(5), Policy.follow(6, 6, 4, 1000, 900, true, false))
    }
    @Test fun `manual reading dragging and fling never move the viewport`() {
        assertEquals(Policy.Step.None, Policy.follow(5, 5, 2, 1000, 900, false, false))
        assertEquals(Policy.Step.None, Policy.follow(5, 5, 4, 1000, 900, true, true))
    }
    @Test fun `reading inside a long final message is not at the bottom`() {
        assertFalse(Policy.nearEnd(5, 4, 1800, 900, 64))
        assertFalse(Policy.nearEnd(5, 3, 900, 900, 64))
        assertTrue(Policy.nearEnd(5, 4, 920, 900, 64))
    }
}
