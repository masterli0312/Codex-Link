package com.codex.quota.notifications.task

import java.io.File
import java.nio.file.Files

/** Missing storage is an empty value; unreadable existing storage must never be replaced. */
internal object StoredFileRead {
    fun existsOrUnknown(file: File): Boolean =
        !Files.notExists(file.toPath()) || !Files.notExists(File(file.path + ".bak").toPath())

    fun <T> forUpdate(exists: Boolean, empty: () -> T, load: () -> T): T =
        if (exists) load() else empty()
}
