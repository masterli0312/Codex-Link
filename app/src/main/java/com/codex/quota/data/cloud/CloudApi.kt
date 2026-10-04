package com.codex.quota.data.cloud

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

enum class CloudErrorKind { LOGIN, FORBIDDEN, NODE_BLOCKED, RATE_LIMIT, NETWORK, RESPONSE, UNCERTAIN }
class CloudException(val kind: CloudErrorKind, val httpCode: Int? = null) : IOException("Cloud ${kind.name}")

/** Fixed official origin, no redirects/logging. POST never retries: a timeout can still create a task. */
class CloudApi(client: OkHttpClient = OkHttpClient()) {
    private val client = client.newBuilder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS).build()

    suspend fun environments(token: String, workspace: String): List<CloudEnvironment> {
        val body = request("environments", token, workspace)
        return withContext(Dispatchers.Default) { CloudWire.environments(body) }
    }
    suspend fun tasks(token: String, workspace: String, cursor: String = ""): CloudPage {
        val url = url("tasks/list").newBuilder().addQueryParameter("limit", "20").addQueryParameter("task_filter", "current")
        if (cursor.isNotBlank()) url.addQueryParameter("cursor", cursor)
        val body = request(url.build(), token, workspace)
        return withContext(Dispatchers.Default) { CloudWire.page(body) }
    }
    suspend fun details(token: String, workspace: String, id: String): CloudDetails {
        require(CloudWire.validId(id))
        val body = request(url("tasks").newBuilder().addPathSegment(id).build(), token, workspace)
        return withContext(Dispatchers.Default) { CloudWire.details(body) }
    }
    suspend fun create(token: String, workspace: String, environment: String, branch: String, prompt: String) =
        CloudWire.createdId(request(url("tasks"), token, workspace, CloudWire.createBody(environment, branch, prompt)))

    private fun url(path: String) = HttpUrl.Builder().scheme("https").host("chatgpt.com")
        .addPathSegments("backend-api/wham/$path").build()
    private suspend fun request(path: String, token: String, workspace: String) = request(url(path), token, workspace)
    private suspend fun request(url: HttpUrl, token: String, workspace: String, body: String? = null): String {
        require(token.isNotBlank() && workspace.isNotBlank())
        val builder = Request.Builder().url(url).header("Authorization", "Bearer ${token.trim().removePrefix("Bearer ")}")
            .header("ChatGPT-Account-Id", workspace).header("Accept", "application/json").header("User-Agent", "CodexUsage-Android/1.2.0")
        if (body != null) builder.post(body.toRequestBody("application/json".toMediaType()))
        val call = client.newCall(builder.build())
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (!continuation.isCancelled) continuation.resumeWithException(CloudException(
                        if (body != null) CloudErrorKind.UNCERTAIN else CloudErrorKind.NETWORK))
                }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        response.use {
                            if (!it.isSuccessful) throw CloudException(when {
                                it.code == 401 -> CloudErrorKind.LOGIN
                                it.code == 403 && (it.header("cf-mitigated") == "challenge" ||
                                    it.header("cf-ray") != null && it.header("Content-Type").orEmpty().contains("text/html")) -> CloudErrorKind.NODE_BLOCKED
                                it.code == 403 -> CloudErrorKind.FORBIDDEN
                                it.code == 429 -> CloudErrorKind.RATE_LIMIT
                                body != null && it.code >= 500 -> CloudErrorKind.UNCERTAIN
                                else -> CloudErrorKind.RESPONSE
                            }, it.code)
                            val source = it.body?.source() ?: throw CloudException(CloudErrorKind.RESPONSE)
                            if (source.request(4L * 1024 * 1024 + 1)) throw CloudException(CloudErrorKind.RESPONSE)
                            val value = source.readUtf8()
                            if (!continuation.isCancelled) continuation.resume(value)
                        }
                    } catch (e: Exception) {
                        if (!continuation.isCancelled) continuation.resumeWithException(
                            if (e is CloudException) e else CloudException(if (body != null) CloudErrorKind.UNCERTAIN else CloudErrorKind.NETWORK))
                    }
                }
            })
        }
    }
}
