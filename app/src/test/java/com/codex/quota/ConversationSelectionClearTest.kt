package com.codex.quota

import android.content.Context
import android.content.SharedPreferences
import com.codex.quota.notifications.task.ConversationSyncSelectionStore
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class ConversationSelectionClearTest {
    @Test fun clearingSelectionsRemovesEncryptedFilesWithoutDeletingOtherData() {
        val root = Files.createTempDirectory("conversation-selection").toFile()
        try {
            val context = mockk<Context>()
            every { context.applicationContext } returns context
            every { context.noBackupFilesDir } returns root
            every { context.getSharedPreferences(any(), any()) } returns mockk<SharedPreferences>()
            val directory = root.resolve("conversation-sync-selection").apply { mkdirs() }
            directory.resolve("encrypted.json").writeText("encrypted fixture")
            root.resolve("other").writeText("keep")
            ConversationSyncSelectionStore(context).clear()
            assertFalse(directory.exists())
            assertTrue(root.resolve("other").exists())
        } finally { root.deleteRecursively() }
    }
}
