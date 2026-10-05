package com.codex.quota.notifications.task

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.Base64
import java.util.UUID
import java.util.zip.ZipInputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Import only pairing data; never run code contained in the user's installation package. */
data class ComputerPairingBackup(val endpoint: String, val key: String, val hostId: String) {
    override fun toString() = "ComputerPairingBackup()"

    companion object {
        fun parse(text: String): ComputerPairingBackup {
            require(text.toByteArray(Charsets.UTF_8).size <= 4_096)
            val data = Json.parseToJsonElement(text).jsonObject
            val endpoint = requireNotNull(data["endpoint"]?.jsonPrimitive?.contentOrNull)
            require(TaskNotificationProtocol.normalizeEndpoint(endpoint) == endpoint)
            val key = requireNotNull(data["contentKey"]?.jsonPrimitive?.contentOrNull)
            require(key.length <= 64 && Base64.getDecoder().decode(key).size == 32)
            val host = requireNotNull(data["remoteHostId"]?.jsonPrimitive?.contentOrNull)
            require(UUID.fromString(host).toString() == host)
            return ComputerPairingBackup(endpoint, key, host)
        }

        fun readZip(input: InputStream): ComputerPairingBackup {
            // Bound both the archive and decompressed content, including skipped script entries.
            val bytes = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8_192)
            while (true) {
                val count = input.read(buffer)
                if (count == -1) break
                require(bytes.size() + count <= 1_048_576)
                bytes.write(buffer, 0, count)
            }
            var backup: ComputerPairingBackup? = null
            var entries = 0
            var total = 0
            ZipInputStream(ByteArrayInputStream(bytes.toByteArray())).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    require(++entries <= 64)
                    val pairing = if (entry.name == "pairing.json") java.io.ByteArrayOutputStream() else null
                    while (true) {
                        val count = zip.read(buffer)
                        if (count == -1) break
                        total += count
                        require(total <= 4_194_304)
                        if (pairing != null) {
                            require(pairing.size() + count <= 4_096)
                            pairing.write(buffer, 0, count)
                        }
                    }
                    if (pairing != null) {
                        require(backup == null)
                        backup = parse(pairing.toString(Charsets.UTF_8.name()))
                    }
                }
            }
            return requireNotNull(backup)
        }
    }
}
