package com.codex.quota.ui.feature.accountdetail

import android.content.Context
import androidx.core.content.ContextCompat
import com.codex.quota.R
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.codex.quota.domain.model.AccountWithUsage
import com.codex.quota.domain.repository.CodexAccountRepository
import com.codex.quota.domain.repository.UserPreferencesRepository
import com.codex.quota.domain.usecase.RefreshAccountUseCase
import com.codex.quota.domain.usecase.ConsumeResetCreditUseCase
import com.codex.quota.domain.usecase.ResetSpendOutcome
import com.codex.quota.domain.usecase.RemoveAccountUseCase
import com.codex.quota.domain.usecase.UpdateAccountUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.codex.quota.data.local.dao.CreditHistoryDao
import com.codex.quota.worker.WorkScheduler

class AccountDetailViewModel(
    context: Context,
    private val accountId: String,
    private val repository: CodexAccountRepository,
    private val preferencesRepository: UserPreferencesRepository,
    private val refreshAccountUseCase: RefreshAccountUseCase,
    private val updateAccountUseCase: UpdateAccountUseCase,
    private val removeAccountUseCase: RemoveAccountUseCase,
    private val consumeResetCredit: ConsumeResetCreditUseCase,
    creditHistoryDao: CreditHistoryDao
) : ViewModel() {

    private val localizedContext = ContextCompat.getContextForLanguage(context)
    private val appContext = context.applicationContext
    val creditHistory = creditHistoryDao.observe(accountId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val preferences = preferencesRepository.observePreferences().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val isUpdatingActivation = MutableStateFlow(false)
    fun setAutoActivation(enabled: Boolean) {
        if (!isUpdatingActivation.compareAndSet(false, true)) return
        viewModelScope.launch {
            try {
                preferencesRepository.setAutoActivateFiveHourAccount(accountId, enabled)
                if (enabled && preferencesRepository.getPreferences().backgroundSyncEnabled) {
                    WorkScheduler.scheduleFiveHourResetRefresh(appContext, accountId, repository.getAccount(accountId)?.usage, includeOverdue = true)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { _uiMessage.value = localizedContext.getString(R.string.error_update_account) }
            finally { isUpdatingActivation.value = false }
        }
    }

    val accountLoaded = MutableStateFlow(false)
    val accountState: StateFlow<AccountWithUsage?> = repository.observeAccount(accountId)
        .onEach { accountLoaded.value = true }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = null
        )

    val isBannerDismissed: StateFlow<Boolean> = preferencesRepository.observePreferences()
        .map { it.dismissedRenewalBannerAccountIds.contains(accountId) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = false
        )

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _uiMessage = MutableStateFlow<String?>(null)
    val uiMessage: StateFlow<String?> = _uiMessage.asStateFlow()

    private val _accountDeleted = MutableStateFlow(false)
    val accountDeleted: StateFlow<Boolean> = _accountDeleted.asStateFlow()

    private val _isResetting = MutableStateFlow(false)
    val isResetting: StateFlow<Boolean> = _isResetting.asStateFlow()

    private val _resetOutcome = MutableStateFlow<ResetSpendOutcome?>(null)
    val resetOutcome: StateFlow<ResetSpendOutcome?> = _resetOutcome.asStateFlow()

    fun consumeReset() {
        if (_isResetting.value) return
        viewModelScope.launch {
            if (_isResetting.value) return@launch
            _isResetting.value = true
            try {
                _resetOutcome.value = consumeResetCredit(accountId)
            } finally {
                _isResetting.value = false
            }
        }
    }

    fun clearResetOutcome() { _resetOutcome.value = null }

    fun refresh() {
        if (!_isRefreshing.compareAndSet(false, true)) return
        viewModelScope.launch {
            try {
                val result = refreshAccountUseCase(accountId)
                if (result.isFailure || result.getOrNull()?.status != com.codex.quota.domain.model.AuthStatus.AUTHENTICATED) {
                    _uiMessage.value = localizedContext.getString(R.string.error_refresh_account)
                }
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { _uiMessage.value = localizedContext.getString(R.string.error_refresh_account) }
            finally { _isRefreshing.value = false }
        }
    }

    fun dismissRenewalBanner() {
        viewModelScope.launch {
            preferencesRepository.setRenewalBannerDismissed(accountId, true)
        }
    }

    fun updateAccountDetails(newNickname: String, newColorHex: String, newRenewalDateEpochMs: Long?) {
        viewModelScope.launch {
            val result = updateAccountUseCase(accountId, newNickname, newColorHex, newRenewalDateEpochMs)
            if (result.isSuccess) {
                _uiMessage.value = localizedContext.getString(R.string.account_updated)
            } else {
                _uiMessage.value = localizedContext.getString(R.string.error_update_account)
            }
        }
    }

    fun updateRenewalDate(renewalDateEpochMs: Long?) {
        viewModelScope.launch {
            val result = updateAccountUseCase.setRenewalDate(accountId, renewalDateEpochMs)
            if (result.isSuccess) {
                _uiMessage.value = if (renewalDateEpochMs != null) localizedContext.getString(R.string.renewal_date_updated) else localizedContext.getString(R.string.renewal_date_removed)
            } else {
                _uiMessage.value = localizedContext.getString(R.string.error_update_renewal_date)
            }
        }
    }

    fun reauthenticate(newApiKey: String) {
        viewModelScope.launch {
            _isRefreshing.value = true
            val result = repository.reauthenticateAccount(accountId, newApiKey)
            _isRefreshing.value = false
            if (result.isSuccess) {
                _uiMessage.value = localizedContext.getString(R.string.account_reauthenticated)
            } else {
                _uiMessage.value = localizedContext.getString(R.string.error_reauthentication)
            }
        }
    }

    fun deleteAccount() {
        viewModelScope.launch {
            val result = removeAccountUseCase(accountId)
            if (result.isSuccess) {
                preferencesRepository.setAutoActivateFiveHourAccount(accountId, false)
                _accountDeleted.value = true
            } else {
                _uiMessage.value = localizedContext.getString(R.string.error_delete_account)
            }
        }
    }

    fun clearUiMessage() {
        _uiMessage.value = null
    }
}
