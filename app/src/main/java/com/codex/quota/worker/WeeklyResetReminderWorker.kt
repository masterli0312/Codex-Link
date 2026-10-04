package com.codex.quota.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.codex.quota.CodexQuotaApplication
import com.codex.quota.domain.model.AuthStatus
import com.codex.quota.domain.model.CodexUsage
import com.codex.quota.domain.model.isApiKeyPlan
import com.codex.quota.notifications.AccountReminderNotificationManager

internal fun hasValidWeeklyResetWindow(usage: CodexUsage?): Boolean {
    if (usage?.status != AuthStatus.AUTHENTICATED || (usage.resetAtEpochMs ?: 0) <= 0) return false
    val remaining = usage.remainingPercent ?: return false
    return remaining.isFinite() && remaining in 0.0..100.0
}

internal fun shouldSendWeeklyResetReminder(usage: CodexUsage?, resetAt: Long, now: Long): Boolean =
    hasValidWeeklyResetWindow(usage) && usage?.resetAtEpochMs == resetAt &&
        now >= resetAt - WeeklyResetReminderWorker.LEAD_TIME_MS && now < resetAt

class WeeklyResetReminderWorker(appContext: Context, workerParams: WorkerParameters) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        val app = applicationContext as? CodexQuotaApplication ?: return Result.failure()
        val accountId = inputData.getString(ACCOUNT_ID) ?: return Result.failure()
        val resetAt = inputData.getLong(RESET_AT, 0)
        if (!app.preferencesRepository.getPreferences().weeklyResetReminderEnabled) return Result.success()
        val item = app.repository.getAccount(accountId) ?: return Result.success()
        if (item.account.isDemoAccount || item.account.planType.isApiKeyPlan ||
            !shouldSendWeeklyResetReminder(item.usage, resetAt, System.currentTimeMillis())) return Result.success()
        val notifications = AccountReminderNotificationManager(applicationContext)
        if (notifications.canNotify() && app.dataStoreManager.claimWeeklyResetReminder(accountId, resetAt)) notifications.showWeeklyReset(item.account)
        return Result.success()
    }

    companion object {
        const val ACCOUNT_ID = "account_id"
        const val RESET_AT = "reset_at"
        const val LEAD_TIME_MS = 5 * 60_000L
    }
}
