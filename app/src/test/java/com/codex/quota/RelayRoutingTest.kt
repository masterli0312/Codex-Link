package com.codex.quota

import com.codex.quota.notifications.task.RelayRouting
import org.junit.Assert.*
import org.junit.Test

class RelayRoutingTest {
    private val paired = setOf("/cu-private", "/topic")
    @Test fun migrationPreservesTopicQueryAndOtherComputerOrigins() {
        assertEquals("https://relay.example:8443/cu-private/json?since=123",
            RelayRouting.rewrite("https://ntfy.sh/cu-private/json?since=123", "https://ntfy.sh/", "https://relay.example:8443/", paired))
        assertEquals("https://other.example/cu-other/json", RelayRouting.rewrite(
            "https://other.example/cu-other/json", "https://ntfy.sh/", "https://relay.example/", paired))
        assertEquals("https://ntfy.sh:8443/cu-private", RelayRouting.rewrite(
            "https://ntfy.sh:8443/cu-private", "https://ntfy.sh/", "https://relay.example/", paired))
        assertEquals("https://ntfy.sh/cu-other/json", RelayRouting.rewrite(
            "https://ntfy.sh/cu-other/json", "https://ntfy.sh/", "https://relay.example/", paired))
        assertEquals("https://ntfy.sh/cu-private-other/json", RelayRouting.rewrite(
            "https://ntfy.sh/cu-private-other/json", "https://ntfy.sh/", "https://relay.example/", paired))
    }
    @Test fun previouslyIssuedAttachmentsKeepTheirRealOrigin() {
        val file = "https://ntfy.sh/file/private.bin"
        assertEquals(file, RelayRouting.rewrite(file, "https://ntfy.sh/", "https://relay.example/", paired))
        assertEquals("https://relay.example/file/private.bin", RelayRouting.rewrite(
            "https://relay.example/file/private.bin", "https://ntfy.sh/", "https://relay.example/", paired))
    }
    @Test fun insecureAmbiguousOrCredentialBearingOriginsCannotRerouteAnything() {
        listOf("http://relay.example/", "https://user:password@relay.example/", "https://relay.example/path",
            "https://relay.example/?token=secret", "https://relay.example/#fragment").forEach {
            assertNull(RelayRouting.origin(it))
            assertEquals("https://ntfy.sh/topic", RelayRouting.rewrite("https://ntfy.sh/topic", "https://ntfy.sh/", it, paired))
        }
        assertEquals("https://relay.example/", RelayRouting.origin("https://relay.example"))
    }
}
