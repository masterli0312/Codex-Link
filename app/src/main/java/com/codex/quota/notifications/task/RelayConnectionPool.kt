package com.codex.quota.notifications.task

import okhttp3.ConnectionPool

/** Reuse TLS connections across foreground conversations, presence and the background listener.
 * Clients retain their own routing, timeout and dispatcher policies; keys stay in request payloads. */
internal object RelayConnectionPool {
    val pool = ConnectionPool()
}
