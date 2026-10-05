package com.codex.quota

import com.codex.quota.notifications.task.PairingRegistryCache
import org.junit.Assert.*
import org.junit.Test

class PairingRegistryCacheTest {
    @Test fun transientDecryptFailureKeepsTheLastVerifiedPairingAndRecovers() {
        val cache = PairingRegistryCache(emptyList<String>())
        assertEquals(listOf("desktop"), cache.read(true) { listOf("desktop") })
        repeat(3) {
            assertEquals(listOf("desktop"), cache.read(true) { error("Keystore unavailable") })
            assertTrue(cache.unavailable.value)
        }
        assertEquals(listOf("desktop", "tablet"), cache.read(true) { listOf("desktop", "tablet") })
        assertFalse(cache.unavailable.value)
    }
    @Test fun failedColdReadCannotOverwriteAnExistingRegistryWithANewPairing() {
        val cache = PairingRegistryCache(emptyList<String>())
        val failed = cache.read(true) { error("unreadable existing ciphertext") }
        assertTrue(cache.unavailable.value)
        assertThrows(IllegalStateException::class.java) { cache.requireWritable(failed) }
        val restored = cache.read(true) { listOf("original-host") }
        assertEquals(listOf("original-host"), cache.requireWritable(restored))
    }
    @Test fun explicitUnpairDoesNotResurrectACachedComputer() {
        val cache = PairingRegistryCache(emptyList<String>())
        cache.read(true) { listOf("desktop") }
        cache.clear()
        assertEquals(emptyList<String>(), cache.read(false) { error("missing file must not be read") })
        assertFalse(cache.unavailable.value)
        assertEquals(emptyList<String>(), cache.read(true) { error("new unreadable file") })
    }
    @Test fun unexpectedlyMissingStorageKeepsThePairingAndBlocksDestructiveWrites() {
        val cache = PairingRegistryCache(emptyList<String>())
        cache.read(true) { listOf("desktop") }
        val value = cache.read(false) { error("unused") }
        assertEquals(listOf("desktop"), value)
        assertTrue(cache.unavailable.value)
        assertThrows(IllegalStateException::class.java) { cache.requireWritable(value) }
        assertEquals(listOf("desktop"), cache.read(true) { listOf("desktop") })
        assertFalse(cache.unavailable.value)
    }
}
