package com.codex.quota.notifications.task

import kotlinx.coroutines.CancellationException

internal inline fun <T> safelyStoreTaskEvent(save: () -> T?, notify: (T?) -> Unit): T? {
    val stored = try {
        save()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }
    notify(stored)
    return stored
}
