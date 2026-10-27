package com.codex.quota

import com.codex.quota.notifications.task.parsePresenceRow
import org.junit.Assert.*
import org.junit.Test

class PresenceStreamTest {
    @Test fun malformedJsonDoesNotPreventParsingTheNextValidRow() {
        assertNull(parsePresenceRow("{malformed"))
        assertNull(parsePresenceRow("[]"))
        assertNotNull(parsePresenceRow("{\"event\":\"message\"}"))
    }
}
