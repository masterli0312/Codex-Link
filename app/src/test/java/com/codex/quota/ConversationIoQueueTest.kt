package com.codex.quota

import com.codex.quota.notifications.task.ConversationIoQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class ConversationIoQueueTest {
    @Test fun aReadAfterQueuedWritesSeesTheLatestDraftOffTheCallerThread() = runBlocking {
        ConversationIoQueue().use { queue ->
            val caller = Thread.currentThread()
            var draft = ""
            val first = queue.submit { assertNotSame(caller, Thread.currentThread()); draft = "old" }
            val second = queue.submit { draft = "latest" }
            assertEquals("latest", withTimeout(5000) { queue.submit { draft }.await() })
            first.await(); second.await()
        }
    }
    @Test fun leavingTheScreenDoesNotCancelItsAlreadyQueuedDraftWrite() = runBlocking {
        ConversationIoQueue().use { queue ->
            val release = CountDownLatch(1)
            val queued = CompletableDeferred<Unit>()
            var saved = false
            val screen = launch {
                val write = queue.submit { check(release.await(5, TimeUnit.SECONDS)); saved = true }
                queued.complete(Unit)
                write.await()
            }
            withTimeout(5000) { queued.await() }
            screen.cancelAndJoin()
            release.countDown()
            assertTrue(withTimeout(5000) { queue.submit { saved }.await() })
        }
    }
    @Test fun oneFailedWriteDoesNotStopSubsequentSaves() = runBlocking {
        ConversationIoQueue().use { queue ->
            val failed = queue.submit { throw IllegalStateException("fixture") }
            assertTrue(runCatching { failed.await() }.isFailure)
            assertEquals("saved", withTimeout(5000) { queue.submit { "saved" }.await() })
        }
    }
}
