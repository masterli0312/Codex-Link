package com.codex.quota.notifications.task

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Separate from the credential master key. Pairing secrets and local records are Keystore wrapped. */
class TaskContentKeys(context: Context) {
    private val prefs = context.getSharedPreferences("task_notifications", Context.MODE_PRIVATE)
    fun read(): String? = prefs.getString(PREF_KEY, null)?.let(::decryptLocal)
    fun ensure(): String = synchronized(lock) {
        val wrapped = prefs.getString(PREF_KEY, null)
        if (wrapped != null) return@synchronized checkNotNull(decryptLocal(wrapped)) { "Pairing key is temporarily unavailable" }
        TaskContentCipher.newKey().also {
            check(prefs.edit().putString(PREF_KEY, encryptLocal(it)).commit())
        }
    }
    private fun key(create: Boolean): SecretKey = synchronized(lock) {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!store.containsAlias(ALIAS)) {
            check(create) { "Existing pairing wrapping key is unavailable" }
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            generator.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build())
            generator.generateKey()
        }
        (store.getEntry(ALIAS, null) as KeyStore.SecretKeyEntry).secretKey
    }
    fun encryptLocal(text: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(create = true))
        return Base64.getEncoder().encodeToString(cipher.iv + cipher.doFinal(text.toByteArray(Charsets.UTF_8)))
    }
    fun decryptLocal(text: String): String? = runCatching {
        val bytes = Base64.getDecoder().decode(text)
        require(bytes.size >= 28)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(create = false), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
    }.getOrNull()
    companion object {
        const val PREF_KEY = "content_key_wrapped"
        private const val ALIAS = "com_codex_usage_task_content_key"
        private val lock = Any()
    }
}
