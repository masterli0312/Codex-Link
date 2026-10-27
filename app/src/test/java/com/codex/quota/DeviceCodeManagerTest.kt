package com.codex.quota

import com.codex.quota.auth.DeviceCodeManager
import com.codex.quota.auth.DeviceCodeSession
import com.codex.quota.auth.DevicePollResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceCodeManagerTest {

    @Test fun pollHttpResponsesDistinguishPendingThrottlingAndErrors() {
        assertEquals(DevicePollResult.Pending, DeviceCodeManager.classifyPollResponse(403, ""))
        assertEquals(DevicePollResult.Pending, DeviceCodeManager.classifyPollResponse(400, "authorization_pending"))
        assertEquals(DevicePollResult.SlowDown, DeviceCodeManager.classifyPollResponse(429, ""))
        assertEquals(DevicePollResult.SlowDown, DeviceCodeManager.classifyPollResponse(400, "slow_down"))
        assertEquals(DevicePollResult.Expired, DeviceCodeManager.classifyPollResponse(400, "expired_token"))
        assertTrue(DeviceCodeManager.classifyPollResponse(400, "access_denied") is DevicePollResult.Error)
        assertTrue(DeviceCodeManager.classifyPollResponse(503, "") is DevicePollResult.Error)
        assertTrue((DeviceCodeManager.classifyPollResponse(503, "authorization_pending") as DevicePollResult.Error).connectionFailure)
        assertTrue(DeviceCodeManager.classifyPollResponse(200, "") is DevicePollResult.Error)
    }

    @Test
    fun deviceCodeSession_validProperties() {
        val session = DeviceCodeSession(
            deviceAuthId = "deviceauth_test_123",
            userCode = "0XV8-EZOKP",
            verificationUri = DeviceCodeManager.VERIFICATION_URL,
            expiresInSeconds = 900,
            intervalSeconds = 5
        )

        assertEquals("deviceauth_test_123", session.deviceAuthId)
        assertEquals("0XV8-EZOKP", session.userCode)
        assertEquals(DeviceCodeManager.VERIFICATION_URL, session.verificationUri)
        assertEquals(900, session.expiresInSeconds)
        assertEquals(5, session.intervalSeconds)
        assertTrue(session.userCode.contains("-"))
    }

    @Test
    fun pollDeviceToken_expiredSession_returnsExpired() = runTest {
        val expiredSession = DeviceCodeSession(
            deviceAuthId = "deviceauth_test",
            userCode = "TEST-12345",
            verificationUri = DeviceCodeManager.VERIFICATION_URL,
            expiresInSeconds = 0,
            intervalSeconds = 5,
            createdAtEpochMs = System.currentTimeMillis() - 10000L
        )

        val pollResult = DeviceCodeManager.pollDeviceToken(expiredSession)
        assertEquals(DevicePollResult.Expired, pollResult)
    }
}
