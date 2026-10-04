package com.codex.quota.notifications.task

/** Debug timing only: no identities, endpoints, credentials or conversation text. */
internal object ConversationSyncTrace {
    private val lock = Any()
    fun record(stage: String, sequence: Long = 0, durationMs: Long = 0, context: android.content.Context? = null) {
        // Some OEMs suppress DEBUG/INFO logs even on debuggable apps.
        if (!com.codex.quota.BuildConfig.DEBUG) return
        val line = "$stage seq=$sequence durationMs=$durationMs at=${System.currentTimeMillis()}"
        android.util.Log.w("CodexSyncTiming", line)
        // Opt-in, local debug metadata for OEMs that also suppress app WARN logs.
        // Excluded from backup; never contains messages, identities or credentials.
        context?.let { ctx -> runCatching {
            if (!java.io.File(ctx.noBackupFilesDir, "sync-timing-enabled").exists()) return@runCatching
            synchronized(lock) {
                val file = java.io.File(ctx.noBackupFilesDir, "sync-timing.txt")
                if (file.length() > 65_536) file.writeText("")
                file.appendText(line + "\n")
            }
        } }
    }
}
