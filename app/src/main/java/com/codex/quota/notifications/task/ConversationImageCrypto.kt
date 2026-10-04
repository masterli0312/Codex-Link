package com.codex.quota.notifications.task

import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object ConversationImageCrypto {
    fun aad(host: String, thread: String, image: String) = "CodexUsage:image:$host:$thread:$image"
    fun decrypt(bytes: ByteArray, key: String, host: String, thread: String, image: String): ByteArray {
        require(bytes.size in 29..ConversationImageRules.MAX_BYTES + 28)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val secret = Base64.getDecoder().decode(key).also { require(it.size == 32) }
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(secret, "AES"), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        cipher.updateAAD(aad(host, thread, image).toByteArray())
        return cipher.doFinal(bytes.copyOfRange(12, bytes.size))
    }
}
