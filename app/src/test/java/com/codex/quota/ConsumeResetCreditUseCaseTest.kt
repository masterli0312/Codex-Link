package com.codex.quota

import com.codex.quota.data.remote.ResetCreditCode
import com.codex.quota.data.remote.ResetCreditConsumer
import com.codex.quota.domain.model.AccountWithUsage
import com.codex.quota.domain.model.AuthStatus
import com.codex.quota.domain.model.CodexAccount
import com.codex.quota.domain.model.CodexUsage
import com.codex.quota.domain.repository.CodexAccountRepository
import com.codex.quota.domain.repository.ResetOperationStore
import com.codex.quota.domain.usecase.ConsumeResetCreditUseCase
import com.codex.quota.domain.usecase.ResetSpendOutcome
import com.codex.quota.security.CredentialStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class ConsumeResetCreditUseCaseTest {
    @Test
    fun staleRefreshRetainsOperationUntilAnAuthenticatedSnapshotArrives() = runBlocking {
        val account = mockk<CodexAccount> {
            every { isDemoAccount } returns false
            every { organizationId } returns "account-123"
        }
        val old = CodexUsage.empty("id", AuthStatus.AUTHENTICATED).copy(bankedResets = 1)
        var refresh = old.copy(status = AuthStatus.OFFLINE)
        val repository = mockk<CodexAccountRepository> {
            coEvery { getAccount("id") } returns AccountWithUsage(account, old)
            coEvery { refreshAccount("id") } answers { Result.success(refresh) }
        }
        val credentials = mockk<CredentialStore> { every { getApiKey("id") } returns "token" }
        val operations = mockk<ResetOperationStore>(relaxed = true) {
            coEvery { getOrCreateResetOperationId("id") } returns "persisted-id"
        }
        val consumer = mockk<ResetCreditConsumer> {
            coEvery { consume("token", "account-123", "persisted-id") } returns ResetCreditCode.RESET
        }
        val useCase = ConsumeResetCreditUseCase(repository, credentials, operations, consumer)
        for (status in listOf(AuthStatus.OFFLINE, AuthStatus.TEMPORARY_ERROR, AuthStatus.AUTHENTICATION_REQUIRED)) {
            refresh = old.copy(status = status)
            assertEquals(ResetSpendOutcome.Success(null, false), useCase("id"))
            coVerify(exactly = 0) { operations.clearResetOperationId(any(), any()) }
        }
        refresh = old
        assertEquals(ResetSpendOutcome.Success(old, false), useCase("id"))
        coVerify(exactly = 1) { operations.clearResetOperationId("id", "persisted-id") }
        coVerify(exactly = 4) { consumer.consume("token", "account-123", "persisted-id") }
    }

    @Test
    fun uncertainResponseReusesPersistedOperationId() = runBlocking {
        val account = mockk<CodexAccount> {
            every { isDemoAccount } returns false
            every { organizationId } returns "account-123"
        }
        val usage = mockk<CodexUsage> { every { bankedResets } returns 1 }
        val repository = mockk<CodexAccountRepository> {
            coEvery { getAccount("id") } returns AccountWithUsage(account, usage)
        }
        val credentials = mockk<CredentialStore> { every { getApiKey("id") } returns "token" }
        val operations = mockk<ResetOperationStore> {
            coEvery { getOrCreateResetOperationId("id") } returns "persisted-id"
        }
        val consumer = mockk<ResetCreditConsumer> {
            coEvery { consume("token", "account-123", "persisted-id") } returns ResetCreditCode.UNKNOWN
        }
        val useCase = ConsumeResetCreditUseCase(repository, credentials, operations, consumer)

        assertEquals(ResetSpendOutcome.Uncertain, useCase("id"))
        assertEquals(ResetSpendOutcome.Uncertain, useCase("id"))
        coVerify(exactly = 2) { consumer.consume("token", "account-123", "persisted-id") }
        coVerify(exactly = 0) { operations.clearResetOperationId(any(), any()) }
    }
}
