package com.codex.quota

import com.codex.quota.notifications.task.StoredFileRead
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class StoredFileReadTest {
    @Test fun failedExistingReadCannotBeTreatedAsEmptyDuringUpdate() {
        var saved = "original encrypted draft or selection"
        fun update(exists: Boolean, load: () -> String) {
            val previous = StoredFileRead.forUpdate(exists, { "" }, load)
            saved = "$previous updated"
        }
        assertThrows(IllegalStateException::class.java) { update(true) { error("decrypt failed") } }
        assertEquals("original encrypted draft or selection", saved)
        update(true) { saved }
        assertEquals("original encrypted draft or selection updated", saved)
        update(false) { error("missing file must not be loaded") }
        assertEquals(" updated", saved)
    }

    @Test fun backupCountsAsExistingStorage() {
        val directory = Files.createTempDirectory("stored-file").toFile()
        try {
            val file = directory.resolve("data")
            assertFalse(StoredFileRead.existsOrUnknown(file))
            directory.resolve("data.bak").writeText("backup")
            assertTrue(StoredFileRead.existsOrUnknown(file))
        } finally { directory.deleteRecursively() }
    }
}
