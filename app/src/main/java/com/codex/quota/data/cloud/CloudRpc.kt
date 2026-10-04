package com.codex.quota.data.cloud

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.*
import okhttp3.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.TimeUnit

/** Scoped to one identity and owner. Sensitive websocket subprotocol is never logged or persisted. */
class CloudRpc(private val token: String, private val workspace: String, client: OkHttpClient = OkHttpClient()) {
    private val client = client.newBuilder().followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .pingInterval(20, TimeUnit.SECONDS).build()
    private val ready = CompletableDeferred<Unit>()
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<JsonObject>>()
    private val ids = AtomicLong()
    val events = Channel<JsonObject>(Channel.CONFLATED)
    private val socket = client.newWebSocket(Request.Builder().url("wss://codex-cloud-backend.chatgpt.com/")
        .header("ChatGPT-Account-ID", workspace).header("X-OpenAI-Product-Sku", "codex")
        .header("Sec-WebSocket-Protocol", "codex-app-server, codex-client.desktop, openai-bearer.$token").build(), object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) { ready.complete(Unit) }
        override fun onMessage(webSocket: WebSocket, text: String) {
            if(text.toByteArray().size > 4*1024*1024) { close(); return }
            val message = runCatching { DurableCloudWire.objectOf(text) }.getOrNull() ?: return
            val id = message["id"]?.jsonPrimitive?.longOrNull
            if(id != null) pending.remove(id)?.let {
                if(message["error"] != null) it.completeExceptionally(CloudException(CloudErrorKind.RESPONSE))
                else it.complete(message["result"] as? JsonObject ?: JsonObject(emptyMap()))
            } else events.trySend(message)
        }
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            fail(CloudException(response?.let { DurableCloudApi.errorKind(it) } ?: CloudErrorKind.NETWORK, response?.code))
        }
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null); fail(CloudException(CloudErrorKind.NETWORK)) }
    })
    suspend fun initialize() {
        withTimeout(20_000) { ready.await() }
        request("initialize", buildJsonObject { putJsonObject("clientInfo") { put("name", "codex_usage_android"); put("version", "1.2.0") }
            putJsonObject("capabilities") { put("experimentalApi", true) } }, mutation = false)
        socket.send(buildJsonObject { put("method", "initialized") }.toString())
    }
    suspend fun request(method: String, params: JsonObject, mutation: Boolean = true): JsonObject {
        val id = ids.incrementAndGet(); val reply = CompletableDeferred<JsonObject>(); pending[id] = reply
        if(!socket.send(buildJsonObject { put("id", id); put("method", method); put("params", params) }.toString())) {
            pending.remove(id); throw CloudException(CloudErrorKind.NETWORK)
        }
        return try { withTimeout(45_000) { reply.await() } }
        catch(e: CancellationException) { if(e is TimeoutCancellationException) throw CloudException(if(mutation) CloudErrorKind.UNCERTAIN else CloudErrorKind.NETWORK); throw e }
        catch(e: CloudException) { if(mutation && e.kind == CloudErrorKind.NETWORK) throw CloudException(CloudErrorKind.UNCERTAIN); throw e }
        finally { pending.remove(id) }
    }
    private fun fail(e: CloudException) { ready.completeExceptionally(e); pending.values.forEach { it.completeExceptionally(e) }; pending.clear(); events.close() }
    fun close() { socket.cancel(); fail(CloudException(CloudErrorKind.NETWORK)) }
}
