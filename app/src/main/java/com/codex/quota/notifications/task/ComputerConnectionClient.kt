package com.codex.quota.notifications.task

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID
import java.util.concurrent.TimeUnit
import android.content.Context

/** A presence reply proves computer reachability, never Provider connectivity or quota. */
class ComputerConnectionClient(context: Context) {
    private val store = ComputerConnectionStore(context)
    private val http = OkHttpClient.Builder().addInterceptor(RelayRouting.interceptor(context.applicationContext)).connectTimeout(8, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()
    suspend fun ensure(connection: ComputerConnection) {
        if (!connection.recentlyReachable() && !probe(connection, force = false)) throw java.io.IOException("COMPUTER_UNREACHABLE")
    }
    suspend fun probe(connection: ComputerConnection, force: Boolean = true): Boolean = locks.getOrPut(connection.id) { Mutex() }.withLock {
        val current = withContext(Dispatchers.IO) { store.all().firstOrNull { it.id == connection.id } } ?: return@withLock false
        // Renew before the lease expires. Waiting until expiry makes an idle healthy host flicker offline.
        if (!force && current.recentlyReachable() && System.currentTimeMillis() - current.lastReachableAt < 30_000) return@withLock true
        probeCurrent(current)
    }
    private suspend fun probeCurrent(connection: ComputerConnection): Boolean = withContext(Dispatchers.IO) {
        if (!connection.hostId.matches(Regex("[a-fA-F0-9]{8}(-[a-fA-F0-9]{4}){3}-[a-fA-F0-9]{12}")) || connection.key.isBlank()) return@withContext false
        val c = RemoteCommand(UUID.randomUUID().toString(), "presence", connection.hostId, connection.hostId,
            TaskInbox.hash(connection.hostId), "presence", System.currentTimeMillis())
        store.beginProbe(connection, c.id)
        try {
            val e = withTimeout(25_000) {
                val wire = RemoteProtocol.wire("command", c.id, RemoteProtocol.json.encodeToString(c), connection.key)
                http.newCall(Request.Builder().url(RemoteProtocol.topic(connection.endpoint, connection.key, connection.hostId, "commands"))
                    .post(wire.toString().toRequestBody("application/json".toMediaType())).build()).execute().use {
                    if (!it.isSuccessful) throw java.io.IOException("PRESENCE_PUBLISH")
                }
                events(RemoteProtocol.topic(connection.endpoint, connection.key, connection.hostId, "events") +
                    "/json?since=" + (c.issued_at / 1000 - 1), connection.key).first {
                        it.status == "presence" && RemoteProtocol.accepts(RemoteConversationState(c), it)
                }
            }
            // A late response cannot restore a removed connection or replace a newer probe.
            store.finishProbe(connection, c.id, e.host_name)
            store.all().any { it.id == connection.id && it.recentlyReachable() }
        } catch (_: TimeoutCancellationException) { store.finishProbe(connection, c.id); false }
        catch (cancelled: CancellationException) { store.finishProbe(connection, c.id); throw cancelled }
        catch (_: Exception) { store.finishProbe(connection, c.id); false }
    }
    companion object { private val locks = java.util.concurrent.ConcurrentHashMap<String, Mutex>() }
    private fun events(url: String, key: String) = callbackFlow<RemoteEvent> {
        val call = http.newCall(Request.Builder().url(url).build())
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: java.io.IOException) { close(e) }
            override fun onResponse(call: Call, response: Response) { response.use {
                if (!it.isSuccessful) { close(java.io.IOException("PRESENCE_STREAM")); return }
                val source = it.body?.source() ?: run { close(); return }
                try {
                    while (!source.exhausted()) {
                        val row = runCatching { Json.parseToJsonElement(source.readUtf8LineStrict(40_000)).jsonObject }.getOrNull() ?: continue
                        if (row["event"]?.jsonPrimitive?.content != "message") continue
                        val wire = runCatching { Json.parseToJsonElement(row["message"]!!.jsonPrimitive.content).jsonObject }.getOrNull() ?: continue
                        val event = RemoteProtocol.event(wire, key) ?: continue
                        trySend(event)
                    }
                    close()
                } catch (e: java.io.IOException) { close(e) }
            } }
        })
        awaitClose { call.cancel() }
    }
}
