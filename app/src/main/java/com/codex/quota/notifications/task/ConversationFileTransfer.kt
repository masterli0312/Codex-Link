package com.codex.quota.notifications.task

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.crypto.Mac
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Request
import okhttp3.Callback
import okhttp3.Call
import okhttp3.Response

/** Files are opaque references from a real native message, fetched only after a user tap. */
object ConversationFileTransfer {
    private val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(240, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()

    suspend fun loadToFile(context: Context, recordId: String, file: ConversationFileRef): File = withContext(Dispatchers.IO) {
        require(ConversationFileRules.valid(listOf(file)))
        val record = requireNotNull(TaskInbox(context).read(recordId))
        val thread = requireNotNull(record.snapshot.thread_ref ?: record.snapshot.remote_ref).thread_id
        val (computer, info) = RemoteConversationClient(context).file(recordId, file.id)
        require(info.name == file.name)
        val url = requireNotNull(RelayRouting.attachmentUrl(context, computer.endpoint, info.url))
        val root = File(context.cacheDir, "conversation-downloads").apply { mkdirs() }
        root.listFiles().orEmpty().filter { it.isFile && it.extension == "part" && System.currentTimeMillis() - it.lastModified() > 86400_000L }.forEach { it.delete() }
        val staged = File.createTempFile("artifact-", ".part", root)
        try {
            if (info.format == "stream-v1") stream(url, staged, computer, thread, info)
            else {
                require(info.size <= 32 * 1024 * 1024)
                val encrypted = ConversationImageTransfer.download(http, url, info.size + 28)
                val bytes = decrypt(encrypted, computer.key, computer.hostId, thread, file.id)
                require(bytes.size.toLong() == info.size && MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) } == info.sha256)
                staged.writeBytes(bytes)
            }
            require(ComputerConnectionStore(context).find(record)?.key == computer.key)
            staged
        } catch (e: Exception) { staged.delete(); throw e }
    }

    private suspend fun stream(url: String, staged: File, computer: ComputerConnection, thread: String, info: RemoteFileDownload): Unit = suspendCancellableCoroutine { continuation ->
        val call = http.newCall(Request.Builder().url(url).build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) {
                try {
                    response.use {
                        if (!it.isSuccessful) throw IOException("FILE_TRANSFER")
                        val body = requireNotNull(it.body)
                        require(body.contentLength() <= info.size + 16)
                        body.byteStream().use { input -> staged.outputStream().use { output ->
                            decryptStream(input, output, computer.key, computer.hostId, thread, info.file_id, info.size, info.sha256)
                        } }
                    }
                    if (continuation.isActive) continuation.resume(Unit)
                } catch (e: Exception) { if (continuation.isActive) continuation.resumeWithException(e) }
            }
        })
    }

    /** Metadata containing the digest is authenticated by the AES-GCM event, before download. */
    internal fun decryptStream(input: InputStream, output: OutputStream, key: String, host: String, thread: String, id: String, size: Long, sha: String) {
        require(size in 1..ConversationFileRules.MAX_BYTES.toLong())
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(Base64.getDecoder().decode(key).also { require(it.size == 32) }, "HmacSHA256"))
        val secret = mac.doFinal("CodexUsage:file:$host:$thread:$id:stream-v1".toByteArray())
        val iv = ByteArray(16)
        var offset = 0
        while (offset < iv.size) { val n = input.read(iv, offset, iv.size - offset); require(n > 0); offset += n }
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(secret, "AES"), javax.crypto.spec.IvParameterSpec(iv))
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(65536)
        var written = 0L
        while (true) {
            val n = input.read(buffer); if (n < 0) break
            if (n == 0) continue
            written += n; require(written <= size)
            val bytes = cipher.update(buffer, 0, n)
            digest.update(bytes); output.write(bytes)
        }
        val tail = cipher.doFinal(); digest.update(tail); output.write(tail)
        require(written == size && digest.digest().joinToString("") { "%02x".format(it) } == sha)
    }

    internal fun decrypt(bytes: ByteArray, key: String, host: String, thread: String, id: String): ByteArray {
        require(bytes.size in 29..ConversationFileRules.MAX_BYTES + 28)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val secret = Base64.getDecoder().decode(key).also { require(it.size == 32) }
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(secret, "AES"), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        cipher.updateAAD("CodexUsage:file:$host:$thread:$id".toByteArray())
        return cipher.doFinal(bytes.copyOfRange(12, bytes.size))
    }
}
