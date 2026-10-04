package com.codex.quota

import com.codex.quota.auth.*
import com.codex.quota.security.CredentialStore
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class AccountAccessTokenProviderTest {
    private class Store : CredentialStore {
        val tokens = java.util.concurrent.ConcurrentHashMap<String, String>()
        val refresh = java.util.concurrent.ConcurrentHashMap<String, String>()
        override fun storeApiKey(accountId: String, apiKey: String) { tokens[accountId] = apiKey }
        override fun getApiKey(accountId: String) = tokens[accountId]
        override fun storeOAuthTokens(accountId: String, accessToken: String, refreshToken: String, clientId: String) {
            tokens[accountId] = accessToken; refresh[accountId] = refreshToken
        }
        override fun getRefreshToken(accountId: String) = refresh[accountId]
        override fun getOAuthClientId(accountId: String) = "fixture-client"
        override fun removeApiKey(accountId: String) { tokens.remove(accountId); refresh.remove(accountId) }
        override fun clearAll() { tokens.clear(); refresh.clear() }
    }
    @Test fun concurrentQuotaAndCloudRefreshRotateTheSameAccountOnlyOnce() = runBlocking {
        val store = Store().apply { storeOAuthTokens("one", "old", "refresh-one", "client") }
        var calls = 0
        val provider = AccountAccessTokenProvider(store, expiryOf = { if (it == "new") Long.MAX_VALUE else 0 }, refreshTokens = { refresh, _ ->
            assertEquals("refresh-one", refresh); calls++; delay(30)
            Result.success(OAuthTokenResult("new", "rotated", null, 3600, null))
        })
        val first = async { provider.fresh("one") }; val second = async { provider.fresh("one", rejectedToken = "old") }
        assertEquals("new", first.await().getOrThrow()); assertEquals("new", second.await().getOrThrow())
        assertEquals(1, calls); assertEquals("rotated", store.getRefreshToken("one"))
    }
    @Test fun deletingAnAccountDuringRefreshCannotRestoreItsTokens() = runBlocking {
        val store = Store().apply { storeOAuthTokens("one", "old", "refresh-one", "client") }
        val started = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>()
        val provider = AccountAccessTokenProvider(store, expiryOf = { 0 }, refreshTokens = { _, _ ->
            started.complete(Unit); finish.await(); Result.success(OAuthTokenResult("new", "rotated", null, 3600, null))
        })
        val job = async { runCatching { provider.fresh("one") } }
        started.await(); store.removeApiKey("one"); finish.complete(Unit); job.await()
        assertNull(store.getApiKey("one")); assertNull(store.getRefreshToken("one"))
    }
}
