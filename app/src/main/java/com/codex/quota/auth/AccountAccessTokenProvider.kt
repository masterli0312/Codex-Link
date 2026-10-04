package com.codex.quota.auth

import com.codex.quota.security.CredentialStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/** Shared by quota and Cloud: a rotating refresh token must have only one writer per account. */
class AccountAccessTokenProvider(private val store: CredentialStore,
    private val expiryOf: (String) -> Long? = { JwtTokenParser.parseToken(it)?.expiresAtEpochMs },
    private val refreshTokens: suspend (String, String) -> Result<OAuthTokenResult> = { refresh, client -> OAuthManager.refreshAccessToken(refresh, client) }) {
    private val locks = ConcurrentHashMap<String, Mutex>()

    suspend fun fresh(accountId: String, fallback: String = "", rejectedToken: String? = null): Result<String> =
        locks.getOrPut(accountId) { Mutex() }.withLock {
            val token = store.getApiKey(accountId) ?: fallback
            val expiry = expiryOf(token)
            val forced = rejectedToken != null && rejectedToken == token
            if (!forced && (expiry == null || expiry > System.currentTimeMillis() + 5 * 60_000L))
                return@withLock Result.success(token)
            val refresh = store.getRefreshToken(accountId) ?: return@withLock Result.success(token)
            val client = store.getOAuthClientId(accountId)
                ?: return@withLock Result.failure(IllegalStateException("OAuth client identifier missing"))
            val result = refreshTokens(refresh, client)
            val rejection = result.exceptionOrNull() as? OAuthTokenRefreshRejectedException
            if (!forced && rejection?.statusCode in listOf(400, 401) && expiry != null && expiry <= System.currentTimeMillis())
                return@withLock Result.success(token)
            result.mapCatching {
                // A deletion or re-login during a refresh must not resurrect or overwrite credentials.
                check(store.getApiKey(accountId) == token) { "Account credentials changed" }
                store.storeOAuthTokens(accountId, it.accessToken, it.refreshToken ?: refresh, client)
                it.accessToken
            }
        }
}
