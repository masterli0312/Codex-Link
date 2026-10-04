package com.codex.quota

import com.codex.quota.ui.feature.taskhistory.VoiceModelArchive
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class VoiceModelArchiveTest {
    private fun archive(vararg paths: String): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip -> paths.forEach {
            zip.putNextEntry(ZipEntry(it)); zip.write(byteArrayOf(1)); zip.closeEntry()
        } }
        return out.toByteArray()
    }

    @Test fun rejectsTraversalAndIncompleteArchivesBeforeTheyBecomeUsable() {
        val root = Files.createTempDirectory("codex-voice-test-").toFile()
        try {
            val destination = java.io.File(root, "model")
            assertThrows(IllegalArgumentException::class.java) {
                VoiceModelArchive.unpack(archive("expected/../outside").inputStream(), destination, "expected")
            }
            assertFalse(java.io.File(root, "outside").exists())
            assertThrows(IllegalArgumentException::class.java) {
                VoiceModelArchive.unpack(archive("expected/other").inputStream(), destination, "expected")
            }
            VoiceModelArchive.unpack(archive("expected/am/final.mdl", "expected/conf/mfcc.conf").inputStream(), destination, "expected")
            assertTrue(java.io.File(destination, "am/final.mdl").isFile)
        } finally { root.deleteRecursively() }
    }
}
