package com.codex.quota

import com.codex.quota.notifications.task.RemoteAttachmentResponse
import java.io.InputStream
import java.io.IOException
import okio.Buffer
import okio.buffer
import okio.source
import org.junit.Assert.*
import org.junit.Test

class RemoteAttachmentResponseTest {
    @Test fun smallUtf8ResponseIsPreservedAndBoundaryIsInclusive() {
        val payload = "{\"attachment\":{\"url\":\"https://fixture.invalid/file/照片.bin\"}}"
        assertEquals(payload, RemoteAttachmentResponse.read(Buffer().writeUtf8(payload)))
        val full = "a".repeat(RemoteAttachmentResponse.MAX_BYTES)
        assertEquals(full, RemoteAttachmentResponse.read(Buffer().writeUtf8(full)))
    }
    @Test fun responseBeyondLimitIsRejectedBeforeItIsReadIntoMemory() {
        var read = 0
        val input = object : InputStream() {
            override fun read(): Int { if (read >= 2 * 1024 * 1024) return -1; read++; return 'x'.code }
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                val count = minOf(length, 2 * 1024 * 1024 - read)
                if (count == 0) return -1
                bytes.fill('x'.code.toByte(), offset, offset + count)
                read += count
                return count
            }
        }
        input.source().buffer().use { source ->
            try {
                RemoteAttachmentResponse.read(source)
                fail("unbounded upload response must be rejected")
            } catch (error: IOException) { assertEquals("ATTACHMENT_UPLOAD", error.message) }
        }
        assertTrue(read <= RemoteAttachmentResponse.MAX_BYTES + 8192)
    }
}
