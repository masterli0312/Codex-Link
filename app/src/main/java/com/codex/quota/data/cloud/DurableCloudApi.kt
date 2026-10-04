package com.codex.quota.data.cloud

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** New Cloud uses its own OAuth backend, not the browser proxy or legacy wham tasks. */
class DurableCloudApi(client: OkHttpClient = OkHttpClient()) {
    private val client = client.newBuilder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(35, TimeUnit.SECONDS).build()
    suspend fun get(token: String, workspace: String, segments: List<String>, query: Map<String,String> = emptyMap(),
        github: Boolean = false) = request(token, workspace, segments, query = query, github = github)
    suspend fun write(token: String, workspace: String, method: String, segments: List<String>, body: JsonObject? = null) =
        request(token, workspace, segments, method = method, body = body)
    private suspend fun request(token: String, workspace: String, segments: List<String>, query: Map<String,String> = emptyMap(),
        method: String = "GET", body: JsonObject? = null, github: Boolean = false): JsonObject {
        require(token.isNotBlank() && workspace.isNotBlank())
        val url = HttpUrl.Builder().scheme("https").host(if(github) "chatgpt.com" else "codex-cloud-backend.chatgpt.com")
        if(github) url.addPathSegments("backend-api/wham/github")
        segments.forEach { require(DurableCloudWire.validId(it)); url.addPathSegment(it) }
        query.forEach { (k,v) -> url.addQueryParameter(k,v) }
        val mutating = method != "GET"
        val request = Request.Builder().url(url.build()).header("Authorization", "Bearer ${token.removePrefix("Bearer ").trim()}")
            .header("ChatGPT-Account-ID", workspace).header("Accept", "application/json").header("User-Agent", "CodexUsage-Android/1.2.0")
            .method(method, if(mutating) (body?.toString() ?: "").toRequestBody("application/json".toMediaType()) else null).build()
        val value = suspendCancellableCoroutine<String> { c ->
            val call = client.newCall(request); c.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { if(c.isActive) c.resumeWithException(CloudException(if(mutating) CloudErrorKind.UNCERTAIN else CloudErrorKind.NETWORK)) }
                override fun onResponse(call: Call, response: Response) {
                    try { response.use { r ->
                        if(!r.isSuccessful) throw CloudException(errorKind(r, mutating), r.code)
                        if(!r.header("Content-Type").orEmpty().contains("json")) throw CloudException(if(mutating) CloudErrorKind.UNCERTAIN else CloudErrorKind.RESPONSE)
                        val s = r.body?.source() ?: throw CloudException(CloudErrorKind.RESPONSE)
                        if(s.request(4L * 1024 * 1024 + 1)) throw CloudException(if(mutating) CloudErrorKind.UNCERTAIN else CloudErrorKind.RESPONSE)
                        if(c.isActive) c.resume(s.readUtf8())
                    } } catch(e: Exception) { if(c.isActive) c.resumeWithException(if(e is CloudException) e else CloudException(if(mutating) CloudErrorKind.UNCERTAIN else CloudErrorKind.RESPONSE)) }
                }
            })
        }
        return withContext(Dispatchers.Default) { try { DurableCloudWire.objectOf(value) }
            catch (_: Exception) { throw CloudException(if(mutating) CloudErrorKind.UNCERTAIN else CloudErrorKind.RESPONSE) } }
    }
    companion object {
        fun errorKind(r: Response, mutation: Boolean = false) = when {
            r.code == 401 -> CloudErrorKind.LOGIN
            r.code == 403 && (r.header("cf-mitigated") == "challenge" || r.header("Content-Type").orEmpty().contains("text/html")) -> CloudErrorKind.NODE_BLOCKED
            r.code == 403 -> CloudErrorKind.FORBIDDEN
            r.code == 429 -> CloudErrorKind.RATE_LIMIT
            mutation && (r.code >= 500 || r.code == 408) -> CloudErrorKind.UNCERTAIN
            else -> CloudErrorKind.RESPONSE
        }
    }
}
