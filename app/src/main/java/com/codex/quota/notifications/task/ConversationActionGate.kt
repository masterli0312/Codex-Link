package com.codex.quota.notifications.task

import java.util.concurrent.ConcurrentHashMap

/** Reject overlapping user actions; never queue a second click for automatic execution later. */
internal object ConversationActionGate {
    private val running = ConcurrentHashMap<String, Boolean>()
    suspend fun <T> run(identity: String, operation: suspend () -> T): T {
        if (running.putIfAbsent(identity, true) != null) throw IllegalStateException("REMOTE_BUSY")
        try { return operation() } finally { running.remove(identity) }
    }
}
