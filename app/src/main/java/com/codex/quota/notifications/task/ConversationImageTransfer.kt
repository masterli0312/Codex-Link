package com.codex.quota.notifications.task

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal object ConversationImageTransfer {
    suspend fun <T> retry(pause: suspend (Long) -> Unit = { delay(it) }, operation: suspend () -> T): T {
        repeat(3) { attempt ->
            try { return operation() }
            catch (e: Exception) {
                if (e is CancellationException && e !is TimeoutCancellationException) throw e
                if (attempt == 2 || e !is IOException && e !is TimeoutCancellationException) throw e
                pause(250L * (attempt + 1))
            }
        }
        error("IMAGE_TRANSFER")
    }

    suspend fun download(http: OkHttpClient, url: String, limit: Long): ByteArray = suspendCancellableCoroutine { continuation ->
        val call = http.newCall(Request.Builder().url(url).build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val bytes = response.use {
                        if (!it.isSuccessful) {
                            if (it.code in setOf(404, 408, 429, 500, 502, 503, 504)) throw IOException("IMAGE_TRANSFER")
                            error("IMAGE_UNAVAILABLE")
                        }
                        val body = requireNotNull(it.body)
                        require(body.contentLength() <= limit)
                        val out = ByteArrayOutputStream()
                        body.byteStream().use { input ->
                            val buffer = ByteArray(32768)
                            while (true) {
                                val n = input.read(buffer); if (n < 0) break
                                require(out.size() + n <= limit)
                                out.write(buffer, 0, n)
                            }
                        }
                        out.toByteArray()
                    }
                    if (continuation.isActive) continuation.resume(bytes)
                } catch (e: Exception) { if (continuation.isActive) continuation.resumeWithException(e) }
            }
        })
    }
}
