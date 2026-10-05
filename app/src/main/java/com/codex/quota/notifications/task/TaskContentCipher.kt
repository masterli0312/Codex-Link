package com.codex.quota.notifications.task

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class TaskConversationMessage(val role: String, val text: String, val id: String = "", val position: Int = -1,
    val images: List<ConversationImageRef> = emptyList(),val phase: String = "", val files: List<ConversationFileRef> = emptyList())

@Serializable
data class ConversationImageRef(val id: String)

@Serializable
data class ConversationFileRef(val id: String, val name: String)

object ConversationFileRules {
    const val MAX_BYTES = 256 * 1024 * 1024
    fun valid(files: List<ConversationFileRef>) = files.size <= 10 && files.map { it.id }.distinct().size == files.size && files.all {
        it.id.matches(Regex("[a-f0-9]{64}")) && it.name.isNotBlank() && it.name.toByteArray().size <= 240 && it.name.none { c -> c in "/\\\r\n\u0000" }
    }
}

object ConversationImageRules {
    const val MAX_BYTES = 8 * 1024 * 1024
    fun valid(message: TaskConversationMessage): Boolean = ConversationFileRules.valid(message.files) && message.images.size <= SelectedAttachmentRules.MAX_FILES &&
        message.images.map { it.id }.distinct().size == message.images.size &&
        message.images.all { it.id.matches(Regex("[a-f0-9]{64}")) }
}

@Serializable
data class TaskConversationSnapshot(
    val conversation_id: String = "",
    val title: String = "",
    val reply: String = "",
    val messages: List<TaskConversationMessage> = emptyList(),
    val truncated: Boolean = false,
    val completed_at: String = "",
    val remote_ref: RemoteThreadRef? = null,
    val history_cursor: String = "",
    val activities: List<RemoteActivity> = emptyList(), val running: Boolean = false,
    val thread_ref: RemoteThreadRef? = null, val turn_durations: Map<String,Long> = emptyMap(),
    val turn_started_at: Map<String, Long> = emptyMap()
)

/** Independent pairing encryption. Never uses OpenAI credentials. IV(12) + ciphertext + GCM tag(16). */
object TaskContentCipher {
    const val MAX_BYTES = 196_608
    private val json = Json { ignoreUnknownKeys = true }

    fun newKey(): String = Base64.getEncoder().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })

    fun encrypt(text: String, key: String, aad: String): String {
        val bytes = text.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES)
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val keyBytes = Base64.getDecoder().decode(key).also { require(it.size == 32) }
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, iv))
        cipher.updateAAD(aad.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(iv + cipher.doFinal(bytes))
    }

    fun decrypt(encrypted: String, key: String, aad: String): String? = runCatching {
        require(encrypted.length <= (MAX_BYTES + 28) * 4 / 3 + 4)
        val bytes = Base64.getDecoder().decode(encrypted)
        require(bytes.size in 28..MAX_BYTES + 28)
        val keyBytes = Base64.getDecoder().decode(key).also { require(it.size == 32) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        cipher.updateAAD(aad.toByteArray(Charsets.UTF_8))
        String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
    }.getOrNull()

    fun decode(encrypted: String, key: String, identity: String): TaskConversationSnapshot? = runCatching {
        val text = decrypt(encrypted, key, "CodexUsage:1.1:$identity") ?: return null
        val snapshot = json.decodeFromString<TaskConversationSnapshot>(text)
        require(snapshot.conversation_id.isEmpty() || snapshot.conversation_id.matches(Regex("[a-f0-9]{64}")))
        require(snapshot.title.toByteArray().size <= 240 && snapshot.reply.toByteArray().size <= 65_536)
        require(snapshot.history_cursor.toByteArray().size <= 4096 && snapshot.messages.size <= 60 && snapshot.messages.all {
            it.role in setOf("user", "assistant") && it.text.toByteArray().size <= 16_384 && it.id.length <= 256 && it.position in -1..1_000_000 && ConversationImageRules.valid(it)
        })
        require(snapshot.remote_ref == null || RemoteProtocol.validRef(snapshot.remote_ref, snapshot.conversation_id))
        require(snapshot.thread_ref == null || RemoteProtocol.validRef(snapshot.thread_ref, snapshot.conversation_id))
        require(RemoteActivityRules.valid(snapshot.activities))
        snapshot
    }.getOrNull()
}
