package com.codex.quota

import com.codex.quota.ui.feature.settings.clearPrivateTaskCaches
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class PrivateTaskCacheTest {
    @Test fun clearsPairingArchivesDownloadsAndPreviewsWithoutDeletingOtherCaches() {
        val root = Files.createTempDirectory("private-task-caches").toFile()
        try {
            listOf("task-notifications", "conversation-downloads", "conversation-previews", "other").forEach {
                root.resolve(it).mkdirs()
                root.resolve("$it/fixture").writeText("fixture")
            }
            clearPrivateTaskCaches(root)
            listOf("task-notifications", "conversation-downloads", "conversation-previews").forEach {
                assertFalse(root.resolve(it).exists())
            }
            assertTrue(root.resolve("other/fixture").exists())
        } finally { root.deleteRecursively() }
    }
}
