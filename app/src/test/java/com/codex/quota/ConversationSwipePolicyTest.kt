package com.codex.quota

import com.codex.quota.ui.feature.taskhistory.ConversationSwipePolicy
import org.junit.Assert.*
import org.junit.Test

class ConversationSwipePolicyTest {
    @Test fun onlyLeftwardMovementRevealsRightSideActions() {
        assertEquals(0f, ConversationSwipePolicy.clamp(20f, 200f), 0f)
        assertEquals(-200f, ConversationSwipePolicy.clamp(-300f, 200f), 0f)
        assertFalse(ConversationSwipePolicy.reveal(-50f, 0f, 200f, 400f))
        assertTrue(ConversationSwipePolicy.reveal(-100f, 0f, 200f, 400f))
        assertTrue(ConversationSwipePolicy.reveal(-10f, -500f, 200f, 400f))
        assertFalse(ConversationSwipePolicy.reveal(-180f, 500f, 200f, 400f))
        assertFalse(ConversationSwipePolicy.reveal(0f, -500f, 0f, 400f))
    }
}
