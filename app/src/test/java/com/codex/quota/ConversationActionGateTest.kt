package com.codex.quota

import com.codex.quota.notifications.task.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ConversationActionGateTest {
    @Test fun concurrentClicksAreRejectedRatherThanExecutedAfterTheFirstAction() = runBlocking {
        val identity = UUID.randomUUID().toString()
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val executed = AtomicInteger()
        val first = launch {
            ConversationActionGate.run(identity) { executed.incrementAndGet(); entered.complete(Unit); finish.await() }
        }
        withTimeout(5000) { entered.await() }
        try {
            repeat(20) {
                assertEquals("REMOTE_BUSY", runCatching {
                    withTimeout(5000) { ConversationActionGate.run(identity) { executed.incrementAndGet() } }
                }.exceptionOrNull()?.message)
            }
            assertEquals(1, executed.get())
        } finally { finish.complete(Unit); first.join() }
    }
    @Test fun cancellationReleasesOnlyItsOwnConversationAndOtherHostsProceed() = runBlocking {
        val identity = UUID.randomUUID().toString()
        val entered = CompletableDeferred<Unit>()
        val first = launch { ConversationActionGate.run(identity) { entered.complete(Unit); awaitCancellation() } }
        withTimeout(5000) { entered.await() }
        try { assertEquals("other host", ConversationActionGate.run(identity + ":other") { "other host" }) }
        finally { first.cancelAndJoin() }
        assertEquals("available", ConversationActionGate.run(identity) { "available" })
        assertTrue(runCatching { ConversationActionGate.run(identity) { throw IllegalStateException("fixture") } }.isFailure)
        assertEquals("available", ConversationActionGate.run(identity) { "available" })
    }
    private val ref = RemoteThreadRef("11111111-2222-3333-4444-555555555555", "44444444-2222-3333-4444-555555555555", "finished")
    private fun record() = TaskInboxRecord("r", 1, "fixture", "pair", TaskConversationSnapshot(
        conversation_id = TaskInbox.hash(ref.thread_id), remote_ref = ref))
    @Test fun atomicPersistenceKeepsOneRequestAndDoesNotOverwriteNewerPreferences() {
        val guard = Any()
        var persisted = record().copy(selectedModel = "newer-selection", pinned = true)
        val started = AtomicInteger()
        val ready = CountDownLatch(8)
        val release = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(8)
        try {
            val jobs = (1..8).map {
                executor.submit {
                    val command = RemoteProtocol.command(ref, persisted.snapshot.conversation_id, "fixture")
                    ready.countDown(); check(release.await(5, TimeUnit.SECONDS))
                    synchronized(guard) {
                        runCatching { persisted = RemoteSendRules.begin(persisted, command); started.incrementAndGet() }
                    }
                }
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS)); release.countDown()
            jobs.forEach { it.get(5, TimeUnit.SECONDS) }
            assertEquals(1, started.get())
            assertEquals("newer-selection", persisted.selectedModel)
            assertTrue(persisted.pinned)
            assertTrue(ConversationIndex.hasPendingTurn(persisted))
        } finally { release.countDown(); executor.shutdownNow() }
    }
    @Test fun changedBaselineArchivedThreadsAndUnconfirmedRequestsCannotStartAgain() {
        val record = record()
        val command = RemoteProtocol.command(ref, record.snapshot.conversation_id, "fixture")
        assertThrows(IllegalArgumentException::class.java) { RemoteSendRules.begin(record.copy(archived = true), command) }
        assertThrows(IllegalArgumentException::class.java) { RemoteSendRules.begin(record.copy(snapshot = record.snapshot.copy(remote_ref = ref.copy(baseline_turn = "newer"))), command) }
        assertThrows(IllegalArgumentException::class.java) { RemoteSendRules.begin(record, command.copy(host_id = UUID.randomUUID().toString())) }
        assertThrows(IllegalArgumentException::class.java) { RemoteSendRules.begin(RemoteSendRules.begin(record, command), command) }
        val unknown = RemoteEvent(UUID.randomUUID().toString(), command.id, ref.host_id, ref.thread_id, command.conversation_id, "unknown", 1, 1)
        assertThrows(IllegalArgumentException::class.java) { RemoteSendRules.begin(record.copy(remoteState = RemoteConversationState(command, unknown)), command) }
        assertNotNull(RemoteSendRules.begin(record.copy(remoteState = RemoteConversationState(command, unknown.copy(status = "completed"))), command))
    }
}
