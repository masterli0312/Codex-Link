package com.codex.quota.notifications.task

import android.content.Context

/** Only display preferences, keyed by paired computer; no credentials or plugin code. */
class ComposerPluginStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("composer-visible-plugins", Context.MODE_PRIVATE)
    private fun key(endpointHash: String, host: String) = TaskInbox.hash("$endpointHash:$host")
    fun read(endpointHash: String, host: String): Set<String>? {
        val key = key(endpointHash, host)
        return if (preferences.contains(key)) preferences.getStringSet(key, emptySet())?.toSet() else null
    }
    fun save(endpointHash: String, host: String, ids: Set<String>) {
        require(ids.size <= 100 && ids.all { it.matches(Regex("[a-f0-9]{64}")) })
        preferences.edit().putStringSet(key(endpointHash, host), ids.toSet()).apply()
    }
}
