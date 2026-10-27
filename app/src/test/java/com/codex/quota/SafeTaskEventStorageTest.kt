package com.codex.quota

import com.codex.quota.notifications.task.safelyStoreTaskEvent
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class SafeTaskEventStorageTest {
    @Test fun localStorageFailureStillAllowsTheClaimedNotificationToBeShown() {
        var shown = false
        val record = safelyStoreTaskEvent<String>({ error("local storage unavailable") }) { shown = true }
        assertNull(record)
        assertTrue(shown)
        assertEquals("record", safelyStoreTaskEvent({ "record" }) { assertEquals("record", it) })
        assertThrows(CancellationException::class.java) { safelyStoreTaskEvent<String>({ throw CancellationException() }) { fail("must not notify after cancellation") } }
    }
}
