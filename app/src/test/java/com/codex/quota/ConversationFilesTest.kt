package com.codex.quota

import com.codex.quota.notifications.task.*
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import java.security.MessageDigest
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class ConversationFilesTest {
    private val host = "11111111-1111-1111-1111-111111111111"
    private val thread = "22222222-2222-2222-2222-222222222222"
    private val key = Base64.getEncoder().encodeToString(ByteArray(32) { 6 })
    private val ref = ConversationFileRef("a".repeat(64), "Report.pdf")
    @Test fun nativeFileReferencesSurviveEncryptedHistoryAndRejectUnsafeNames() {
        val message = TaskConversationMessage("assistant", "[Report](Report.pdf)", "turn:answer", files = listOf(ref))
        val snapshot = TaskConversationSnapshot(messages = listOf(message))
        val encrypted = TaskContentCipher.encrypt(RemoteProtocol.json.encodeToString(snapshot), key, "CodexUsage:1.1:fixture")
        assertEquals(message, TaskContentCipher.decode(encrypted, key, "fixture")!!.messages.single())
        for (bad in listOf(ref.copy(id = "../private"), ref.copy(name = "../auth.json"), ref.copy(name = "bad\nname")))
            assertFalse(ConversationFileRules.valid(listOf(bad)))
        assertFalse(ConversationFileRules.valid(listOf(ref, ref)))
    }
    @Test fun fileResponsesCannotBypassSizeTypeOrFilenameChecks() {
        val event = RemoteEvent(host, thread, host, thread, TaskInbox.hash(thread), "file", 1, 1,
            file = RemoteFileDownload(ref.id, ref.name, "https://relay.invalid/file/artifact.bin", "application/octet-stream", 64, "b".repeat(64)))
        fun decode(value: RemoteEvent) = RemoteProtocol.event(RemoteProtocol.wire("event", host, RemoteProtocol.json.encodeToString(value), key), key)
        assertEquals(event, decode(event))
        assertNull(decode(event.copy(file = event.file!!.copy(size = ConversationFileRules.MAX_BYTES + 1L))))
        assertNull(decode(event.copy(status = "image")))
        assertNull(decode(event.copy(file = event.file!!.copy(name = "../../private"))))
    }
    @Test fun transferredFileAuthenticationBindsHostThreadAndFileIdentity() {
        val plain = "exact artifact bytes".toByteArray()
        val iv = ByteArray(12) { 4 }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(Base64.getDecoder().decode(key), "AES"), GCMParameterSpec(128, iv))
        cipher.updateAAD("CodexUsage:file:$host:$thread:${ref.id}".toByteArray())
        val encrypted = iv + cipher.doFinal(plain)
        assertArrayEquals(plain, ConversationFileTransfer.decrypt(encrypted, key, host, thread, ref.id))
        assertTrue(runCatching { ConversationFileTransfer.decrypt(encrypted, key, thread, host, ref.id) }.isFailure)
        assertTrue(runCatching { ConversationFileTransfer.decrypt(encrypted, key, host, thread, "b".repeat(64)) }.isFailure)
    }
    @Test fun streamingChecksAuthenticatedDigestLengthAndIdentityBeforeExposingAFile() {
        val plain = ByteArray(200000) { (it % 127).toByte() }
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(Base64.getDecoder().decode(key), "HmacSHA256"))
        val secret = mac.doFinal("CodexUsage:file:$host:$thread:${ref.id}:stream-v1".toByteArray())
        val iv = ByteArray(16) { 9 }
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(secret, "AES"), IvParameterSpec(iv))
        val encrypted = iv + cipher.doFinal(plain)
        val digest = MessageDigest.getInstance("SHA-256").digest(plain).joinToString("") { "%02x".format(it) }
        fun decrypt(bytes: ByteArray, sourceThread: String = thread, length: Long = plain.size.toLong()): ByteArray {
            val input = object : ByteArrayInputStream(bytes) {
                override fun read(b: ByteArray, off: Int, len: Int): Int { assertTrue(len <= 65536); return super.read(b, off, len) }
            }
            val output = ByteArrayOutputStream()
            ConversationFileTransfer.decryptStream(input, output, key, host, sourceThread, ref.id, length, digest)
            return output.toByteArray()
        }
        assertArrayEquals(plain, decrypt(encrypted))
        assertTrue(runCatching { decrypt(encrypted.copyOf(encrypted.size - 1)) }.isFailure)
        assertTrue(runCatching { decrypt(encrypted + byteArrayOf(0)) }.isFailure)
        assertTrue(runCatching { decrypt(encrypted, host) }.isFailure)
        val corrupt = encrypted.copyOf().apply { this[500] = (this[500].toInt() xor 1).toByte() }
        assertTrue(runCatching { decrypt(corrupt) }.isFailure)
    }
}
