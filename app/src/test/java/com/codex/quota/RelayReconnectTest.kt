package com.codex.quota

import com.codex.quota.notifications.task.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class RelayReconnectTest {
    @Test fun initialNetworkCallbackDoesNotRestartAHealthySocketAndRouteChangeOccursOnce() {
        val route = RelayRouteTracker("wifi")
        assertFalse(route.available("wifi"))
        assertFalse(route.lost("old-cellular"))
        assertTrue(route.available("cellular"))
        assertFalse(route.lost("wifi"))
        assertFalse(route.available("cellular"))
        val replacement = RelayRouteTracker("cellular")
        assertFalse(replacement.lost("wifi"))
        assertFalse(replacement.available("cellular"))
        assertTrue(replacement.lost("cellular"))
    }
    @Test fun availabilityAfterOfflineWakesOnceAndLateCallbacksCannotCancelAfterDisposal() {
        val route = RelayRouteTracker<String>(null)
        assertTrue(route.available("wifi"))
        assertFalse(route.available("wifi"))
        val disposed = RelayRouteTracker("wifi")
        disposed.close()
        assertFalse(disposed.available("cellular"))
        assertFalse(disposed.lost("wifi"))
    }
    @Test fun routeChangesReconnectImmediatelyEvenAfterLongBackoff() {
        assertEquals(0L, RelayReconnectPolicy.waitMillis(RelayNetworkChanged(), 30_000))
        assertEquals(4000L, RelayReconnectPolicy.waitMillis(IOException("timeout"), 4000))
    }
    @Test fun rateLimitsAreRespectedAndMalformedHeadersCannotCreateBusyLoops() {
        assertEquals(90_000L, RelayReconnectPolicy.waitMillis(RelayHttpFailure(429, "90"), 2000))
        assertEquals(60_000L, RelayReconnectPolicy.waitMillis(RelayHttpFailure(429, "invalid"), 2000))
        assertEquals(1000L, RelayReconnectPolicy.waitMillis(RelayHttpFailure(429, "0"), 2000))
        assertEquals(3_600_000L, RelayReconnectPolicy.waitMillis(RelayHttpFailure(429, Long.MAX_VALUE.toString()), 2000))
        assertEquals(2000L, RelayReconnectPolicy.waitMillis(RelayHttpFailure(503, null), 2000))
    }
}
