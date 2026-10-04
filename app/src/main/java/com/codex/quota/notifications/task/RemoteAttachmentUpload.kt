package com.codex.quota.notifications.task

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrl

data class SelectedRemoteFile(val uri: Uri, val name: String, val mime: String) {
    override fun toString() = "SelectedRemoteFile"
}
object RemoteAttachmentUpload {
    const val MAX_BYTES = 8 * 1024 * 1024
    private val extensions = setOf("jpg", "jpeg", "png", "webp", "txt", "md", "csv", "json", "log")
    fun select(context: Context, uri: Uri, photo: Boolean): SelectedRemoteFile {
        require(uri.scheme == "content")
        var name: String? = null
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use {
            if (it.moveToFirst()) {
                name = it.getString(0)
                if (!it.isNull(1)) require(it.getLong(1) in 1..MAX_BYTES.toLong()) { "ATTACHMENT_TOO_LARGE" }
            }
        }
        val mime = context.contentResolver.getType(uri).orEmpty().take(120)
        val display = requireNotNull(name).takeIf { it.isNotBlank() && it.toByteArray().size <= 160 && !it.any { c -> c in "/\\\n\r\u0000" } }
            ?: throw IllegalArgumentException("ATTACHMENT_INVALID")
        val extension = display.substringAfterLast('.', "").lowercase()
        require(extension in extensions && (!photo || extension in setOf("jpg", "jpeg", "png", "webp"))) { "ATTACHMENT_UNSUPPORTED" }
        return SelectedRemoteFile(uri, display, mime)
    }
    suspend fun upload(context: Context, http: OkHttpClient, computer: ComputerConnection, thread: String, file: SelectedRemoteFile): RemoteAttachment = withContext(Dispatchers.IO) {
        val bytes = context.contentResolver.openInputStream(file.uri)?.use { input ->
            val output = ByteArrayOutputStream(); val buffer = ByteArray(32_768)
            while (true) { val n = input.read(buffer); if (n < 0) break; require(output.size() + n <= MAX_BYTES) { "ATTACHMENT_TOO_LARGE" }; output.write(buffer, 0, n) }
            output.toByteArray()
        } ?: throw IOException("ATTACHMENT_READ")
        require(bytes.isNotEmpty())
        val id = UUID.randomUUID().toString()
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(Base64.getDecoder().decode(computer.key), "AES"), GCMParameterSpec(128, iv))
        cipher.updateAAD("CodexUsage:attachment:${computer.hostId}:$thread:$id".toByteArray())
        val request = Request.Builder().url(RemoteProtocol.topic(computer.endpoint, computer.key, computer.hostId, "commands"))
            .header("Filename", "codex-upload.bin").header("Message", "Encrypted attachment")
            .post((iv + cipher.doFinal(bytes)).toRequestBody("application/octet-stream".toMediaType())).build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("ATTACHMENT_UPLOAD")
            val body = response.body?.let { RemoteAttachmentResponse.read(it.source()) } ?: throw IOException("ATTACHMENT_UPLOAD")
            val url = Json.parseToJsonElement(body).jsonObject["attachment"]?.jsonObject?.get("url")?.jsonPrimitive?.content
                ?: throw IOException("ATTACHMENT_UPLOAD")
            val parsed = url.toHttpUrl(); val origin = RelayRouting.endpoint(context, computer.endpoint).toHttpUrl()
            require(parsed.isHttps && parsed.host == origin.host && parsed.port == origin.port && parsed.username.isBlank() && parsed.password.isBlank() &&
                parsed.query == null && parsed.fragment == null && parsed.encodedPath.matches(Regex("/file/[A-Za-z0-9_.-]{1,128}")))
            RemoteAttachment(id, file.name, file.mime, url, bytes.size.toLong(), sha)
        }
    }
}
