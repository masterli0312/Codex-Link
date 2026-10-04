package com.codex.quota.notifications.task

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import java.io.Closeable
import java.io.IOException

internal class RelayNetworkChanged : IOException("RELAY_NETWORK_CHANGED")
internal class RelayHttpFailure(val status: Int, val retryAfter: String?) : IOException("RELAY_HTTP")

internal class RelayRouteTracker<T>(private val initial: T?) {
    private val invalidated = java.util.concurrent.atomic.AtomicBoolean(false)
    fun available(network: T) = network != initial && invalidated.compareAndSet(false, true)
    fun lost(network: T) = network == initial && invalidated.compareAndSet(false, true)
    fun close() { invalidated.set(true) }
}

internal object RelayReconnectPolicy {
    fun waitMillis(error: Exception?, backoff: Long): Long = when {
        error is RelayNetworkChanged -> 0
        error is RelayHttpFailure && error.status == 429 ->
            ((error.retryAfter?.toLongOrNull()?.coerceIn(1, 3600) ?: 60) * 1000)
        else -> backoff
    }
}

/** Route changes invalidate a stale socket immediately. Relay availability is not host presence. */
internal object RelayConnection {
    fun watch(context: Context?, call: Call, onChange: (IOException) -> Unit): Closeable {
        val manager = context?.applicationContext?.getSystemService(ConnectivityManager::class.java)
            ?: return Closeable { }
        val initial = manager.activeNetwork
        val route = RelayRouteTracker(initial)
        val callback = object : ConnectivityManager.NetworkCallback() {
            fun invalidate() {
                onChange(RelayNetworkChanged())
                call.cancel()
            }
            override fun onAvailable(network: Network) { if (route.available(network)) invalidate() }
            override fun onLost(network: Network) { if (route.lost(network)) invalidate() }
        }
        val registered = runCatching { manager.registerDefaultNetworkCallback(callback) }.isSuccess
        return Closeable { route.close(); if (registered) runCatching { manager.unregisterNetworkCallback(callback) } }
    }

    suspend fun retry(context: Context?, error: Exception?, backoff: Long) {
        val wait = RelayReconnectPolicy.waitMillis(error, backoff)
        if (wait <= 0) return
        val manager = context?.applicationContext?.getSystemService(ConnectivityManager::class.java)
        // Rate limiting is a server instruction; changing networks must not bypass it.
        if (manager == null || error is RelayHttpFailure && error.status == 429) { delay(wait); return }
        val initial = manager.activeNetwork
        withTimeoutOrNull(wait) {
            callbackFlow<Unit> {
                val callback = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) { if (network != initial) trySend(Unit) }
                }
                val registered = runCatching { manager.registerDefaultNetworkCallback(callback) }.isSuccess
                awaitClose { if (registered) runCatching { manager.unregisterNetworkCallback(callback) } }
            }.first()
        }
    }
}
