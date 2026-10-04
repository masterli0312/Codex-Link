package com.codex.quota.notifications.task

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

class NtfyTaskClient(private val client: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS).readTimeout(75, TimeUnit.SECONDS).build(),
    private val context: android.content.Context? = null) {
    private val relayClient = client.newBuilder().addInterceptor(RelayRouting.interceptor(context)).followRedirects(false).followSslRedirects(false).build()
    private val attachmentClient = relayClient.newBuilder().followRedirects(false).followSslRedirects(false)
        .readTimeout(10, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS).build()

    suspend fun downloadSnapshot(endpoint: String, attachment: TaskAttachment, key: String, identity: String): TaskConversationSnapshot? =
        withContext(Dispatchers.IO) {
            val url = RelayRouting.attachmentUrl(context, endpoint, attachment.url) ?: return@withContext null
            if (attachment.expiresAt <= System.currentTimeMillis()) return@withContext null
            try {
                attachmentClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
                    if (!response.isSuccessful) return@withContext null
                    val body = response.body ?: return@withContext null
                    if (body.contentLength() > TaskContentCipher.MAX_BYTES + 28) return@withContext null
                    val bytes = java.io.ByteArrayOutputStream()
                    body.byteStream().use { input ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (bytes.size() + count > TaskContentCipher.MAX_BYTES + 28) return@withContext null
                            bytes.write(buffer, 0, count)
                        }
                    }
                    TaskContentCipher.decode(java.util.Base64.getEncoder().encodeToString(bytes.toByteArray()), key, identity)
                }
            } catch (_: IOException) { null }
        }

    fun events(endpoint: String, since: String, onConnected: () -> Unit) = callbackFlow {
        require(TaskNotificationProtocol.normalizeEndpoint(endpoint) != null)
        val url = endpoint.toHttpUrl().newBuilder().addPathSegment("json").addQueryParameter("since", since).build()
        val call = relayClient.newCall(Request.Builder().url(url).header("Accept", "application/x-ndjson").build())
        val network = RelayConnection.watch(context, call) { close(it) }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { close(e) }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!response.isSuccessful) { close(RelayHttpFailure(response.code, response.header("Retry-After"))); return }
                    val source = response.body?.source() ?: run { close(); return }
                    onConnected()
                    try {
                        while (!source.exhausted()) {
                            val line = source.readUtf8LineStrict(32_768)
                            val event = TaskNotificationProtocol.decode(line) ?: continue
                            // Backpressure must reconnect from the persisted cursor, never silently drop events.
                            if (!trySend(event).isSuccess) { close(IOException("Notification queue full")); return }
                        }
                        close()
                    } catch (e: IOException) { close(e) }
                }
            }
        })
        awaitClose { network.close(); call.cancel() }
    }
}
