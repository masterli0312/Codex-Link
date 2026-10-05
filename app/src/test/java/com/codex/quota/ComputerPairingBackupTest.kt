package com.codex.quota

import com.codex.quota.notifications.task.ComputerPairingBackup
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test

class ComputerPairingBackupTest {
    private val key = Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() })
    private val host = "11111111-2222-3333-4444-555555555555"
    private fun payload(endpoint: String = "https://example.com/original-topic", contentKey: String = key, hostId: String = host) =
        """{"endpoint":"$endpoint","contentKey":"$contentKey","remoteHostId":"$hostId","remoteEnabled":true}"""
    private fun zip(vararg entries: Pair<String, String>): ByteArrayInputStream {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip -> entries.forEach { (name, body) ->
            zip.putNextEntry(ZipEntry(name)); zip.write(body.toByteArray()); zip.closeEntry()
        } }
        return ByteArrayInputStream(bytes.toByteArray())
    }
    @Test fun restoresExactComputerIdentityWithoutRunningIncludedScripts() {
        val backup = ComputerPairingBackup.readZip(zip("setup.cmd" to "do not execute", "pairing.json" to payload()))
        assertEquals(host, backup.hostId)
        assertEquals(key, backup.key)
        assertEquals("https://example.com/original-topic", backup.endpoint)
        assertFalse(backup.toString().contains(key))
        assertFalse(backup.toString().contains(backup.endpoint))
    }
    @Test fun rejectsMalformedOrIncompletePairings() {
        listOf(payload(endpoint = "http://example.com/topic"), payload(contentKey = "bad-key"), payload(hostId = "wrong-host"), "{}").forEach {
            assertThrows(Exception::class.java) { ComputerPairingBackup.parse(it) }
        }
        assertThrows(Exception::class.java) { ComputerPairingBackup.readZip(zip("setup.cmd" to "script only")) }
        assertThrows(Exception::class.java) { ComputerPairingBackup.readZip(zip("../pairing.json" to payload())) }
    }
    @Test fun rejectsOversizedPairingAndZipExpansion() {
        assertThrows(Exception::class.java) { ComputerPairingBackup.readZip(zip("pairing.json" to " ".repeat(4097))) }
        assertThrows(Exception::class.java) { ComputerPairingBackup.readZip(zip("other" to "a".repeat(4_194_305), "pairing.json" to payload())) }
    }
}
