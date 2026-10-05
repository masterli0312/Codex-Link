package com.codex.quota

import com.codex.quota.notifications.task.*
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class ConversationImagesTest {
    private val host = "11111111-1111-1111-1111-111111111111"
    private val thread = "22222222-2222-2222-2222-222222222222"
    private val id = "a".repeat(64)
    private val key = Base64.getEncoder().encodeToString(ByteArray(32) { 5 })
    private fun seal(bytes: ByteArray): ByteArray {
        val iv = ByteArray(12) { 7 }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(Base64.getDecoder().decode(key), "AES"), GCMParameterSpec(128, iv))
        cipher.updateAAD(ConversationImageCrypto.aad(host, thread, id).toByteArray())
        return iv + cipher.doFinal(bytes)
    }
    @Test fun imageBytesCannotBeReusedAcrossHostsThreadsOrImages() {
        val bytes = byteArrayOf(1, 2, 3)
        val encrypted = seal(bytes)
        assertArrayEquals(bytes, ConversationImageCrypto.decrypt(encrypted, key, host, thread, id))
        for ((h, t, i) in listOf(Triple(thread, thread, id), Triple(host, host, id), Triple(host, thread, "b".repeat(64)))) {
            assertTrue(runCatching { ConversationImageCrypto.decrypt(encrypted, key, h, t, i) }.isFailure)
        }
        val corrupt = encrypted.copyOf().apply { this[13] = (this[13].toInt() xor 1).toByte() }
        assertTrue(runCatching { ConversationImageCrypto.decrypt(corrupt, key, host, thread, id) }.isFailure)
        assertTrue(runCatching { ConversationImageCrypto.decrypt(ByteArray(28), key, host, thread, id) }.isFailure)
    }
    @Test fun imageReferencesSurviveEncryptedSnapshotAndNativeHistoryMerge() {
        val photo = TaskConversationMessage("user", "", "turn:photo", images = listOf(ConversationImageRef(id)))
        val snapshot = TaskConversationSnapshot(conversation_id = TaskInbox.hash(thread), messages = listOf(photo))
        val wire = TaskContentCipher.encrypt(RemoteProtocol.json.encodeToString(snapshot), key, "CodexUsage:1.1:fixture")
        assertEquals(photo, TaskContentCipher.decode(wire, key, "fixture")!!.messages.single())
        val updated = photo.copy(text = "caption")
        assertEquals(listOf(updated), ConversationHistory.merge(listOf(photo), listOf(updated)))
        val old = RemoteProtocol.json.decodeFromString<TaskConversationMessage>("""{"role":"user","text":"old"}""")
        assertTrue(old.images.isEmpty())
    }
    @Test fun malformedImageReferencesAreRejectedAtBothProtocolBoundaries() {
        for (images in listOf(listOf(ConversationImageRef("../private")), List(11) { ConversationImageRef(it.toString(16).repeat(64)) }, List(2) { ConversationImageRef(id) })) {
            val message = TaskConversationMessage("user", "", images = images)
            val snapshot = TaskConversationSnapshot(messages = listOf(message))
            val encrypted = TaskContentCipher.encrypt(RemoteProtocol.json.encodeToString(snapshot), key, "CodexUsage:1.1:fixture")
            assertNull(TaskContentCipher.decode(encrypted, key, "fixture"))
            val event = RemoteEvent(host, thread, host, thread, TaskInbox.hash(thread), "history", 1, 1, messages = listOf(message))
            assertNull(RemoteProtocol.event(RemoteProtocol.wire("event", host, RemoteProtocol.json.encodeToString(event), key), key))
        }
    }
    @Test fun imageEventIsValidatedWithoutAlterationOfConversationState() {
        val event = RemoteEvent(host, thread, host, thread, TaskInbox.hash(thread), "image", 1, 1,
            image = RemoteImageDownload(id, "https://relay.invalid/file/image.bin", "image/png", 64, "b".repeat(64)))
        fun decode(e: RemoteEvent) = RemoteProtocol.event(RemoteProtocol.wire("event", host, RemoteProtocol.json.encodeToString(e), key), key)
        assertEquals(event, decode(event))
        assertNull(decode(event.copy(image = event.image!!.copy(size = ConversationImageRules.MAX_BYTES + 1L))))
        assertNull(decode(event.copy(image = event.image!!.copy(mime = "image/svg+xml"))))
    }
}
