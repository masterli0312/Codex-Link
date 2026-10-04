package com.codex.quota

import com.codex.quota.notifications.task.TaskContentCipher
import com.codex.quota.notifications.task.TaskNotificationProtocol
import java.util.Base64
import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class TaskContentCipherTest {
    private val key = Base64.getEncoder().encodeToString(ByteArray(32) { 7 })
    private val identity = "codex:" + "a".repeat(64)
    @Test fun nodeEncryptedFixtureDecryptsOnAndroidWithChineseAndEmoji() {
        val wire = "AwMDAwMDAwMDAwMDXtzXai5EO2BAYqfkRrFpiwiTWQcbCLZcDaziwY+cYoKTQtOufXM1YD4X35Gv4sUoQldgpe7nTlUWc3OPHFj6HZmAWgzKwlYrL4fb9LHq1TGPkaUg9fkyXI4aZW/j2Q=="
        val snapshot = TaskContentCipher.decode(wire, key, identity)
        assertEquals("中文对话", snapshot?.title)
        assertEquals("完成 🔔", snapshot?.reply)
        assertNull(TaskContentCipher.decode(wire, key, identity + "wrong"))
        assertNull(TaskContentCipher.decode(wire, Base64.getEncoder().encodeToString(ByteArray(32) { 8 }), identity))
        val corrupted = Base64.getDecoder().decode(wire).also { it[20] = (it[20].toInt() xor 1).toByte() }
        assertNull(TaskContentCipher.decode(Base64.getEncoder().encodeToString(corrupted), key, identity))
    }
    @Test fun encryptedContentIsBoundedAndCannotExportHiddenRoles() {
        fun encode(value: String) = TaskContentCipher.encrypt(value, key, "CodexUsage:1.1:$identity")
        assertNull(TaskContentCipher.decode(encode("""{"messages":[{"role":"system","text":"hidden"}]}"""), key, identity))
        assertNull(TaskContentCipher.decode(encode("""{"title":"${"文".repeat(100)}"}"""), key, identity))
        assertNull(TaskContentCipher.decrypt("invalid", key, "aad"))
        assertNull(TaskContentCipher.decrypt("a".repeat(300_000), key, "aad"))
    }
    @Test fun attachmentRequestsStayOnPairedHttpsServiceAndNeverFollowArbitraryUrls() {
        val endpoint = "https://ntfy.sh/private-topic"
        assertEquals("https://ntfy.sh/file/abc123.bin", TaskNotificationProtocol.attachmentUrl(endpoint, "https://ntfy.sh/file/abc123.bin"))
        for (url in listOf("http://ntfy.sh/file/a.bin", "https://evil.example/file/a.bin", "https://ntfy.sh/private-topic/json",
            "https://u:p@ntfy.sh/file/a.bin", "https://ntfy.sh/file/a.bin?q=1", "https://ntfy.sh/file/a.bin#x",
            "https://ntfy.sh:444/file/a.bin", "https://ntfy.sh/file/../../secret")) {
            assertNull(url, TaskNotificationProtocol.attachmentUrl(endpoint, url))
        }
    }
    @Test fun richPreviewDecodeIsCompatibleAndAnInvalidOptionalAttachmentCannotSuppressIt() {
        val content = TaskContentCipher.encrypt("""{"title":"任务","reply":"完成"}""", key, "CodexUsage:1.1:$identity")
        val payload = buildJsonObject {
            put("schema_version", "1.1"); put("agent_source", "codex"); put("status", "turn_complete")
            put("session_id", "codex"); put("turn_id", "a".repeat(64)); put("encrypted_content", content)
        }.toString()
        val envelope = buildJsonObject { put("event", "message"); put("id", "x"); put("time", 1700000000L); put("message", payload) }.toString()
        val event = TaskNotificationProtocol.decode(envelope)
        assertEquals(identity, event?.deduplicationId)
        assertEquals("任务", TaskContentCipher.decode(event!!.encryptedContent!!, key, identity)?.title)
        assertNull(event.attachment)
        val malformedAttachment = envelope.dropLast(1) + ",\"attachment\":[]}"
        assertEquals(content, TaskNotificationProtocol.decode(malformedAttachment)?.encryptedContent)
    }
}
