package com.codex.quota

import android.app.Application
import android.net.Uri
import com.codex.quota.data.local.AppDatabase
import com.codex.quota.data.local.DataStoreManager
import com.codex.quota.data.remote.MockOpenAiDataSource
import com.codex.quota.data.remote.RealOpenAiDataSource
import com.codex.quota.data.remote.WhamResetCreditConsumer
import com.codex.quota.data.repository.CodexAccountRepositoryImpl
import com.codex.quota.data.repository.UserPreferencesRepositoryImpl
import com.codex.quota.domain.repository.CodexAccountRepository
import com.codex.quota.domain.repository.UserPreferencesRepository
import com.codex.quota.domain.usecase.ConsumeResetCreditUseCase
import com.codex.quota.domain.usecase.ActivateFiveHourWindowUseCase
import com.codex.quota.data.remote.CodexWindowActivator
import com.codex.quota.security.EncryptedCredentialStore
import com.codex.quota.security.KeystoreManager
import com.codex.quota.worker.WorkScheduler
import com.codex.quota.domain.model.isApiKeyPlan
import com.codex.quota.notifications.AccountReminderNotificationManager
import com.codex.quota.notifications.ResetOpportunityObservation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class CodexQuotaApplication : Application() {

    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    var currentOAuthCallbackUri: Uri? = null

    lateinit var database: AppDatabase
        private set

    lateinit var credentialStore: EncryptedCredentialStore
        private set

    lateinit var accessTokens: com.codex.quota.auth.AccountAccessTokenProvider
        private set

    val cloudRepository by lazy { com.codex.quota.data.cloud.CloudRepository(repository, accessTokens) }
    val cloudStore by lazy { com.codex.quota.data.cloud.CloudStateStore(this) }

    lateinit var dataStoreManager: DataStoreManager
        private set

    lateinit var repository: CodexAccountRepository
        private set

    lateinit var preferencesRepository: UserPreferencesRepository
        private set

    lateinit var consumeResetCredit: ConsumeResetCreditUseCase
        private set

    lateinit var activateFiveHourWindow: ActivateFiveHourWindowUseCase
        private set

    override fun onCreate() {
        super.onCreate()

        applicationScope.launch(Dispatchers.IO) {
            com.codex.quota.notifications.NotificationBranding.refreshExisting(this@CodexQuotaApplication)
        }

        database = AppDatabase.getInstance(this)
        val keystoreManager = KeystoreManager(this)
        credentialStore = EncryptedCredentialStore(this, keystoreManager)
        accessTokens = com.codex.quota.auth.AccountAccessTokenProvider(credentialStore)
        dataStoreManager = DataStoreManager(this)

        repository = CodexAccountRepositoryImpl(
            accountDao = database.accountDao(),
            usageSnapshotDao = database.usageSnapshotDao(),
            creditHistoryDao = database.creditHistoryDao(),
            credentialStore = credentialStore,
            accessTokens = accessTokens,
            onAccountRemoved = { cloudStore.removeAccount(it) },
            onDataCleared = { cloudStore.clear() },
            realDataSource = RealOpenAiDataSource(),
            mockDataSource = MockOpenAiDataSource(),
            refreshScope = applicationScope,
            onUsageRefreshed = { usage ->
                applicationScope.launch {
                    val prefs = preferencesRepository.getPreferences()
                    val account = repository.getAccount(usage.accountId)?.account
                    if (account != null && !account.isDemoAccount && !account.planType.isApiKeyPlan) {
                        val reminders = AccountReminderNotificationManager(this@CodexQuotaApplication)
                        if (dataStoreManager.observeResetOpportunity(ResetOpportunityObservation(account.id, usage.bankedResets, usage.fetchedAtEpochMs), reminders.canNotify())) {
                            reminders.showResetOpportunity(account, usage.bankedResets!!)
                        }
                        if (prefs.weeklyResetReminderEnabled) WorkScheduler.scheduleWeeklyResetReminder(this@CodexQuotaApplication, account.id, usage)
                    }
                    if (prefs.fiveHourResetReminderEnabled) {
                        WorkScheduler.scheduleFiveHourResetReminder(
                            this@CodexQuotaApplication, usage.accountId, usage
                        )
                    }
                    if (prefs.backgroundSyncEnabled) {
                        WorkScheduler.scheduleFiveHourResetRefresh(
                            this@CodexQuotaApplication,
                            usage.accountId,
                            usage,
                            includeOverdue = usage.accountId in prefs.autoActivateFiveHourAccountIds
                        )
                    }
                }
            }
        )

        preferencesRepository = UserPreferencesRepositoryImpl(dataStoreManager)
        consumeResetCredit = ConsumeResetCreditUseCase(repository, credentialStore, dataStoreManager, WhamResetCreditConsumer())
        activateFiveHourWindow = ActivateFiveHourWindowUseCase(
            repository, credentialStore, dataStoreManager, CodexWindowActivator()
        )

        // Restore one-time reset refreshes from persisted official window timestamps.
        applicationScope.launch {
            val prefs = preferencesRepository.getPreferences()
            if (prefs.weeklyResetReminderEnabled) {
                repository.getAllAccounts().forEach { item ->
                    if (!item.account.isDemoAccount && !item.account.planType.isApiKeyPlan) {
                        WorkScheduler.scheduleWeeklyResetReminder(this@CodexQuotaApplication, item.account.id, item.usage)
                    }
                }
            } else WorkScheduler.cancelWeeklyResetReminders(this@CodexQuotaApplication)
            if (prefs.fiveHourResetReminderEnabled) {
                repository.getAllAccounts().forEach { item ->
                    if (!item.account.isDemoAccount) {
                        WorkScheduler.scheduleFiveHourResetReminder(
                            this@CodexQuotaApplication, item.account.id,
                            item.usage
                        )
                    }
                }
            } else {
                WorkScheduler.cancelFiveHourResetReminders(this@CodexQuotaApplication)
            }
            if (prefs.backgroundSyncEnabled) {
                WorkScheduler.schedulePeriodicRefresh(this@CodexQuotaApplication, prefs.refreshInterval.minutes)
                repository.getAllAccounts().forEach { item ->
                    if (!item.account.isDemoAccount) {
                        WorkScheduler.scheduleFiveHourResetRefresh(
                            this@CodexQuotaApplication,
                            item.account.id,
                            item.usage,
                            includeOverdue = item.account.id in prefs.autoActivateFiveHourAccountIds
                        )
                    }
                }
            } else {
                WorkScheduler.cancelPeriodicRefresh(this@CodexQuotaApplication)
                WorkScheduler.cancelFiveHourResetRefresh(this@CodexQuotaApplication)
            }
        }
    }

    fun markOnboardingComplete() {
        applicationScope.launch {
            preferencesRepository.setHasCompletedOnboarding(true)
        }
    }
}
