package com.codex.quota.notifications.task

import java.io.Closeable
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel

/** Ordered, blocking local IO with a lifetime independent of the screen that queued it. */
internal class ConversationIoQueue : Closeable {
    private val dispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "conversation-preferences").apply { isDaemon = true }
    }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    fun <T> submit(operation: () -> T) = scope.async { operation() }
    override fun close() { scope.cancel(); dispatcher.close() }
}
