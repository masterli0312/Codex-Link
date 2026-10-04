package com.codex.quota

import com.codex.quota.notifications.task.ConversationImageTransfer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class ConversationImageTransferTest {
    @Test fun transientBusyAndTransferErrorsRecoverWithoutUserTap() = runBlocking {
        var calls = 0
        val delays = mutableListOf<Long>()
        val result = ConversationImageTransfer.retry(pause = { delays += it }) {
            calls++
            if (calls < 3) throw IOException(if (calls == 1) "IMAGE_BUSY" else "IMAGE_TRANSFER")
            "image"
        }
        assertEquals("image", result)
        assertEquals(listOf(250L, 500L), delays)
    }
    @Test fun permanentFailureAndCancellationAreNotRetried() = runBlocking {
        for (failure in listOf(IllegalArgumentException("IMAGE_UNAVAILABLE"), CancellationException("closed"))) {
            var calls = 0
            try {
                ConversationImageTransfer.retry(pause = { fail("unexpected delay") }) { calls++; throw failure }
                fail("expected failure")
            } catch (actual: Exception) { assertSame(failure, actual) }
            assertEquals(1, calls)
        }
    }
    @Test fun networkRetriesAreBounded() = runBlocking {
        var calls = 0
        try { ConversationImageTransfer.retry(pause = {}) { calls++; throw IOException("failed") }; fail("expected failure") }
        catch (_: IOException) { assertEquals(3, calls) }
    }
}
