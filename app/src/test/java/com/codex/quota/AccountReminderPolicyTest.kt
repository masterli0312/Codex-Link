package com.codex.quota

import com.codex.quota.domain.model.AuthStatus
import com.codex.quota.domain.model.CodexUsage
import com.codex.quota.notifications.ResetOpportunityObservation
import com.codex.quota.notifications.isResetOpportunityIncrease
import com.codex.quota.worker.shouldSendWeeklyResetReminder
import org.junit.Assert.*
import org.junit.Test

class AccountReminderPolicyTest {
    private val reset = 1_000_000L
    private fun usage() = CodexUsage(accountId = "a", remainingPercent = 0.0, usedPercent = 100.0,
        usedTokens = null, totalLimitTokens = null, remainingCredits = null, resetAtEpochMs = reset,
        status = AuthStatus.AUTHENTICATED, fetchedAtEpochMs = 600_000L)

    @Test fun weeklyReminderIncludesExhaustedQuotaAndOnlyTheFiveMinuteLeadWindow() {
        assertTrue(shouldSendWeeklyResetReminder(usage(), reset, reset - 300_000))
        assertTrue(shouldSendWeeklyResetReminder(usage(), reset, reset - 1))
        assertFalse(shouldSendWeeklyResetReminder(usage(), reset, reset - 300_001))
        assertFalse(shouldSendWeeklyResetReminder(usage(), reset, reset))
    }

    @Test fun weeklyReminderRejectsOldChangedUnknownAndOfflineWindows() {
        assertFalse(shouldSendWeeklyResetReminder(usage(), reset + 1, reset - 100))
        assertFalse(shouldSendWeeklyResetReminder(null, reset, reset - 100))
        assertFalse(shouldSendWeeklyResetReminder(usage().copy(resetAtEpochMs = null), reset, reset - 100))
        assertFalse(shouldSendWeeklyResetReminder(usage().copy(status = AuthStatus.OFFLINE), reset, reset - 100))
        assertFalse(shouldSendWeeklyResetReminder(usage().copy(remainingPercent = Double.NaN), reset, reset - 100))
    }

    @Test fun resetAlertUsesOnlyAnIncreaseBetweenTwoKnownOfficialObservations() {
        val old = ResetOpportunityObservation("a", 0, 100)
        val next = old.copy(count = 2, fetchedAtEpochMs = 200)
        assertTrue(isResetOpportunityIncrease(old, next, true))
        assertFalse(isResetOpportunityIncrease(null, next, true))
        assertFalse(isResetOpportunityIncrease(old.copy(count = null), next, true))
        assertFalse(isResetOpportunityIncrease(old, next.copy(count = null), true))
        assertFalse(isResetOpportunityIncrease(old, next.copy(count = -1), true))
        assertFalse(isResetOpportunityIncrease(next, next.copy(count = 1, fetchedAtEpochMs = 300), true))
    }

    @Test fun resetAlertRejectsDuplicatesStaleResultsOtherAccountsAndDisabledSetting() {
        val old = ResetOpportunityObservation("a", 1, 100)
        val next = old.copy(count = 2, fetchedAtEpochMs = 200)
        assertFalse(isResetOpportunityIncrease(old, next, false))
        assertFalse(isResetOpportunityIncrease(next, next, true))
        assertFalse(isResetOpportunityIncrease(next, old.copy(count = 3), true))
        assertFalse(isResetOpportunityIncrease(old, next.copy(accountId = "b"), true))
        assertFalse(isResetOpportunityIncrease(old.copy(count = -1), next, true))
    }
}
