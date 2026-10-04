package com.codex.quota.notifications.task

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import java.security.MessageDigest
import java.security.SecureRandom
import okhttp3.HttpUrl.Companion.toHttpUrl

data class TaskNotificationSettings(val enabled: Boolean = false, val endpoint: String = "", val enabledAtSeconds: Long = 0L, val contentEnabled: Boolean = false, val remoteEnabled: Boolean = false, val previewEnabled: Boolean = false,
    val relaySourceOrigin: String = "", val relayOrigin: String = "")

/** Separate from all OpenAI credentials and account preferences. Excluded from backup. */
class TaskNotificationStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("task_notifications", Context.MODE_PRIVATE)
    fun read() = TaskNotificationSettings(prefs.getBoolean("enabled", false), prefs.getString("endpoint", "").orEmpty(), prefs.getLong("enabled_at", 0L), prefs.getBoolean("content_enabled", false), prefs.getBoolean("remote_enabled", false), prefs.getBoolean("notification_preview_enabled", false),
        prefs.getString("relay_source_origin", "").orEmpty(), prefs.getString("relay_origin", "").orEmpty())
    val settings = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> trySend(read()) }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(read())
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.distinctUntilChanged()

    fun saveEndpoint(endpoint: String) {
        require(TaskNotificationProtocol.normalizeEndpoint(endpoint) == endpoint)
        val current = TaskNotificationProtocol.normalizeEndpoint(read().endpoint)
        if (current != null && current != endpoint) {
            val old = current.toHttpUrl()
            val next = endpoint.toHttpUrl()
            if (old.encodedPath == next.encodedPath) {
                prefs.edit().putString("relay_source_origin", old.newBuilder().encodedPath("/").build().toString())
                    .putString("relay_origin", next.newBuilder().encodedPath("/").build().toString()).apply()
                return
            }
        }
        if (read().endpoint != endpoint) {
            ComputerConnectionStore(context).clear()
            TaskInbox(context).clear()
            prefs.edit().remove(TaskContentKeys.PREF_KEY).putBoolean("content_enabled", false).putBoolean("remote_enabled", false).apply()
        }
        prefs.edit().remove("relay_source_origin").remove("relay_origin").putString("endpoint", endpoint).putBoolean("enabled", false).apply()
    }

    fun setContentEnabled(enabled: Boolean) {
        if (enabled) TaskContentKeys(context).ensure()
        prefs.edit().putBoolean("content_enabled", enabled).apply {
            if (!enabled) putBoolean("remote_enabled", false)
        }.apply()
    }
    fun setPreviewEnabled(enabled: Boolean) { prefs.edit().putBoolean("notification_preview_enabled", enabled).apply() }

    fun setRemoteEnabled(enabled: Boolean) {
        require(!enabled || read().contentEnabled)
        prefs.edit().putBoolean("remote_enabled", enabled).apply()
    }

    fun ensureEndpoint(): String {
        val saved = read().endpoint
        if (TaskNotificationProtocol.normalizeEndpoint(saved) != null) return saved
        val bytes = ByteArray(24).also { SecureRandom().nextBytes(it) }
        val endpoint = "https://ntfy.sh/codex-usage-" + bytes.joinToString("") { "%02x".format(it) }
        saveEndpoint(endpoint)
        return endpoint
    }

    fun setEnabled(enabled: Boolean) {
        if (enabled) require(TaskNotificationProtocol.normalizeEndpoint(read().endpoint) != null)
        prefs.edit().putBoolean("enabled", enabled).apply {
            if (enabled) putLong("enabled_at", System.currentTimeMillis() / 1000L)
        }.apply()
    }

    fun since(endpoint: String, fallback: Long): String = replaySince(prefs.getString("cursor_${key(endpoint)}", null), fallback)

    /** Persist deduplication before showing; retries after a lost publish response cannot notify twice. */
    fun claim(endpoint: String, event: TaskCompletionEvent): Boolean = synchronized(lock) {
        val key = key(endpoint)
        val seen = prefs.getString("seen_$key", "").orEmpty().split('\n').filter { it.isNotEmpty() }
        val id = key(event.deduplicationId)
        val fresh = id !in seen
        val next = (seen.filterNot { it == id } + id).takeLast(256)
        val cursor = maxOf(prefs.getString("cursor_$key", null)?.toLongOrNull() ?: 0L, event.timeEpochMs / 1000L)
        check(prefs.edit().putString("cursor_$key", cursor.toString()).putString("seen_$key", next.joinToString("\n")).commit())
        fresh
    }

    fun clear() { prefs.edit().clear().apply(); ComputerConnectionStore(context).clear(); TaskInbox(context).clear() }
    private fun key(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    companion object {
        private val lock = Any()
        // Replay one second at the boundary so messages sharing the cursor second are not lost.
        // Persisted event identities suppress notifications for already received messages.
        internal fun replaySince(cursor: String?, fallback: Long): String =
            (cursor?.toLongOrNull()?.let { (it - 1L).coerceAtLeast(0L) } ?: fallback).toString()
    }
}
