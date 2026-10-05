package com.codex.quota.notifications.task

import kotlinx.coroutines.flow.MutableStateFlow

/** A read failure is not an empty registry. Callers synchronize access with the store lock. */
internal class PairingRegistryCache<T>(private val empty: T) {
    private var lastGood: T? = null
    val unavailable = MutableStateFlow(false)

    fun read(exists: Boolean, load: () -> T): T {
        if (!exists) {
            // Explicit clear/unpair invalidates this cache. Unexpected disappearance
            // (including temporarily inaccessible compatibility storage) does not.
            lastGood?.let { unavailable.value = true; return it }
            unavailable.value = false
            return empty
        }
        return try {
            load().also { remember(it) }
        } catch (_: Exception) {
            unavailable.value = true
            lastGood ?: empty
        }
    }

    fun requireWritable(value: T): T {
        check(!unavailable.value) { "Pairing storage is temporarily unavailable" }
        return value
    }

    fun remember(value: T) {
        lastGood = value
        unavailable.value = false
    }

    fun clear() {
        lastGood = null
        unavailable.value = false
    }
}
