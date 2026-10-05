package com.codex.quota.notifications.task

/** Whole selections are validated before replacing the draft: no silent partial multi-selection. */
internal object SelectedAttachmentRules {
    const val MAX_FILES = 10
    fun <T, K> merge(current: List<T>, incoming: List<T>, key: (T) -> K): List<T> {
        val result = (current + incoming).associateBy(key).values.toList()
        require(result.size <= MAX_FILES) { "ATTACHMENT_TOO_MANY" }
        return result
    }
}
