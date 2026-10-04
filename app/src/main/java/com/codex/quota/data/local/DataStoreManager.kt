package com.codex.quota.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.codex.quota.domain.model.AppThemeMode
import com.codex.quota.domain.model.QuotaWindow
import com.codex.quota.domain.model.RefreshIntervalMinutes
import com.codex.quota.domain.model.UserPreferences
import com.codex.quota.domain.model.WidgetThemeMode
import com.codex.quota.domain.repository.ResetOperationStore
import com.codex.quota.domain.repository.FiveHourActivationStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID
import com.codex.quota.notifications.ResetOpportunityObservation
import com.codex.quota.notifications.isResetOpportunityIncrease

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "user_settings")

class DataStoreManager(private val context: Context) : ResetOperationStore, FiveHourActivationStore {

    val fiveHourActivationClaims: Flow<Map<String, Long>> = context.dataStore.data.map { prefs ->
        prefs.asMap().mapNotNull { (key, value) ->
            if (key.name.startsWith("five_hour_activation_") && value is Long)
                key.name.removePrefix("five_hour_activation_") to value else null
        }.toMap()
    }

    /** Claim before the billable request. An uncertain network result is never retried automatically. */
    override suspend fun claimFiveHourActivation(accountId: String, resetAtEpochMs: Long): Boolean {
        val key = longPreferencesKey("five_hour_activation_" + accountId)
        var claimed = false
        context.dataStore.edit { prefs ->
            if ((prefs[key] ?: 0L) < resetAtEpochMs) {
                prefs[key] = resetAtEpochMs
                claimed = true
            }
        }
        return claimed
    }

    override suspend fun getOrCreateResetOperationId(accountId: String): String {
        val key = stringPreferencesKey("pending_reset_operation_" + accountId)
        var operationId = ""
        context.dataStore.edit { prefs ->
            operationId = prefs[key] ?: UUID.randomUUID().toString().also { prefs[key] = it }
        }
        return operationId
    }

    override suspend fun clearResetOperationId(accountId: String, operationId: String) {
        val key = stringPreferencesKey("pending_reset_operation_" + accountId)
        context.dataStore.edit { prefs ->
            if (prefs[key] == operationId) prefs.remove(key)
        }
    }

    private object PreferencesKeys {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val WIDGET_THEME_MODE = stringPreferencesKey("widget_theme_mode")
        val BACKGROUND_SYNC_ENABLED = booleanPreferencesKey("background_sync_enabled")
        val AUTO_ACTIVATE_FIVE_HOUR_ACCOUNTS = stringSetPreferencesKey("auto_activate_five_hour_accounts")
        val REFRESH_INTERVAL = longPreferencesKey("refresh_interval_minutes")
        val REFRESH_ON_APP_OPEN = booleanPreferencesKey("refresh_on_app_open")
        val SIGNED_OUT_NOTIFICATIONS = booleanPreferencesKey("signed_out_notifications_enabled")
        val QUOTA_ALERTS_ENABLED = booleanPreferencesKey("quota_alerts_enabled")
        val INCLUDE_FIVE_HOUR_QUOTA_ALERTS = booleanPreferencesKey("include_five_hour_quota_alerts")
        val FIVE_HOUR_RESET_REMINDER_ENABLED = booleanPreferencesKey("five_hour_reset_reminder_enabled")
        val WEEKLY_RESET_REMINDER_ENABLED = booleanPreferencesKey("weekly_reset_reminder_enabled")
        val RESET_OPPORTUNITY_ALERTS_ENABLED = booleanPreferencesKey("reset_opportunity_alerts_enabled")
        val INCLUDE_WEEKLY_QUOTA_ALERTS = booleanPreferencesKey("include_weekly_quota_alerts")
        val INCLUDE_GPT_RESERVE_ALERTS = booleanPreferencesKey("include_gpt_reserve_alerts")
        val QUOTA_ALERT_THRESHOLDS = stringSetPreferencesKey("quota_alert_thresholds_set")
        val ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
        val DISMISSED_RENEWAL_BANNERS = stringSetPreferencesKey("dismissed_renewal_banners")
    }

    val userPreferencesFlow: Flow<UserPreferences> = context.dataStore.data.map { prefs ->
        val themeModeStr = prefs[PreferencesKeys.THEME_MODE] ?: AppThemeMode.SYSTEM.name
        val themeMode = try {
            AppThemeMode.valueOf(themeModeStr)
        } catch (e: Exception) {
            AppThemeMode.SYSTEM
        }

        val widgetThemeStr = prefs[PreferencesKeys.WIDGET_THEME_MODE] ?: WidgetThemeMode.DARK_OBSIDIAN.name
        val widgetThemeMode = WidgetThemeMode.fromString(widgetThemeStr)

        val refreshIntervalMinutes = prefs[PreferencesKeys.REFRESH_INTERVAL] ?: 30L

        val thresholdStrings = prefs[PreferencesKeys.QUOTA_ALERT_THRESHOLDS] ?: setOf("5", "10", "25")
        val thresholds = thresholdStrings.mapNotNull { it.toIntOrNull() }.toSet().ifEmpty { setOf(5, 10, 25) }

        UserPreferences(
            themeMode = themeMode,
            dynamicColor = prefs[PreferencesKeys.DYNAMIC_COLOR] ?: true,
            widgetThemeMode = widgetThemeMode,
            backgroundSyncEnabled = prefs[PreferencesKeys.BACKGROUND_SYNC_ENABLED] ?: true,
            autoActivateFiveHourAccountIds = prefs[PreferencesKeys.AUTO_ACTIVATE_FIVE_HOUR_ACCOUNTS] ?: emptySet(),
            refreshInterval = RefreshIntervalMinutes.fromMinutes(refreshIntervalMinutes),
            refreshOnAppOpen = prefs[PreferencesKeys.REFRESH_ON_APP_OPEN] ?: true,
            signedOutNotificationsEnabled = prefs[PreferencesKeys.SIGNED_OUT_NOTIFICATIONS] ?: true,
            quotaAlertsEnabled = prefs[PreferencesKeys.QUOTA_ALERTS_ENABLED] ?: true,
            includeFiveHourQuotaAlerts = prefs[PreferencesKeys.INCLUDE_FIVE_HOUR_QUOTA_ALERTS] ?: false,
            fiveHourResetReminderEnabled = prefs[PreferencesKeys.FIVE_HOUR_RESET_REMINDER_ENABLED] ?: false,
            weeklyResetReminderEnabled = prefs[PreferencesKeys.WEEKLY_RESET_REMINDER_ENABLED] ?: false,
            resetOpportunityAlertsEnabled = prefs[PreferencesKeys.RESET_OPPORTUNITY_ALERTS_ENABLED] ?: false,
            includeWeeklyQuotaAlerts = prefs[PreferencesKeys.INCLUDE_WEEKLY_QUOTA_ALERTS] ?: true,
            includeGptReserveAlerts = false, // Retired; never restore an old enabled setting.
            quotaAlertThresholds = thresholds,
            hasCompletedOnboarding = prefs[PreferencesKeys.ONBOARDING_COMPLETED] ?: false,
            dismissedRenewalBannerAccountIds = prefs[PreferencesKeys.DISMISSED_RENEWAL_BANNERS] ?: emptySet()
        )
    }

    suspend fun getPreferences(): UserPreferences = userPreferencesFlow.first()

    suspend fun setThemeMode(mode: AppThemeMode) {
        context.dataStore.edit { prefs ->
            prefs[PreferencesKeys.THEME_MODE] = mode.name
        }
    }

    suspend fun setDynamicColor(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[PreferencesKeys.DYNAMIC_COLOR] = enabled
        }
    }

    suspend fun setWidgetThemeMode(mode: WidgetThemeMode) {
        context.dataStore.edit { prefs ->
            prefs[PreferencesKeys.WIDGET_THEME_MODE] = mode.name
        }
    }

    suspend fun setBackgroundSyncEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[PreferencesKeys.BACKGROUND_SYNC_ENABLED] = enabled
        }
    }

    suspend fun setAutoActivateFiveHourAccount(accountId: String, enabled: Boolean) {
        context.dataStore.edit { prefs ->
            val current = prefs[PreferencesKeys.AUTO_ACTIVATE_FIVE_HOUR_ACCOUNTS] ?: emptySet()
            prefs[PreferencesKeys.AUTO_ACTIVATE_FIVE_HOUR_ACCOUNTS] =
                if (enabled) current + accountId else current - accountId
        }
    }

    suspend fun setRefreshInterval(interval: RefreshIntervalMinutes) {
        context.dataStore.edit { prefs ->
            prefs[PreferencesKeys.REFRESH_INTERVAL] = interval.minutes
        }
    }

    suspend fun setRefreshOnAppOpen(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[PreferencesKeys.REFRESH_ON_APP_OPEN] = enabled
        }
    }

    suspend fun setSignedOutNotificationsEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[PreferencesKeys.SIGNED_OUT_NOTIFICATIONS] = enabled
        }
    }

    suspend fun setQuotaAlertsEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[PreferencesKeys.QUOTA_ALERTS_ENABLED] = enabled
        }
    }

    suspend fun setIncludeFiveHourQuotaAlerts(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[PreferencesKeys.INCLUDE_FIVE_HOUR_QUOTA_ALERTS] = enabled
        }
    }

    suspend fun setFiveHourResetReminderEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[PreferencesKeys.FIVE_HOUR_RESET_REMINDER_ENABLED] = enabled
        }
    }

    /** Atomically mark a window so overlapping workers cannot notify twice. */
    suspend fun claimFiveHourResetReminder(accountId: String, resetAtEpochMs: Long): Boolean {
        val key = longPreferencesKey("five_hour_reset_reminder_" + accountId)
        var claimed = false
        context.dataStore.edit { prefs ->
            if ((prefs[key] ?: 0L) < resetAtEpochMs) {
                prefs[key] = resetAtEpochMs
                claimed = true
            }
        }
        return claimed
    }

    suspend fun setWeeklyResetReminderEnabled(enabled: Boolean) {
        context.dataStore.edit { it[PreferencesKeys.WEEKLY_RESET_REMINDER_ENABLED] = enabled }
    }

    suspend fun setResetOpportunityAlertsEnabled(enabled: Boolean) {
        context.dataStore.edit { it[PreferencesKeys.RESET_OPPORTUNITY_ALERTS_ENABLED] = enabled }
    }

    suspend fun claimWeeklyResetReminder(accountId: String, resetAtEpochMs: Long): Boolean {
        val key = longPreferencesKey("weekly_reset_reminder_" + accountId)
        var claimed = false
        context.dataStore.edit { prefs ->
            if ((prefs[key] ?: 0L) < resetAtEpochMs) {
                prefs[key] = resetAtEpochMs
                claimed = true
            }
        }
        return claimed
    }

    /** Update the official observation even when alerts are off. Atomic edit deduplicates concurrent refreshes. */
    suspend fun observeResetOpportunity(observation: ResetOpportunityObservation, canNotify: Boolean): Boolean {
        if (observation.fetchedAtEpochMs <= 0 || observation.accountId.isBlank()) return false
        val timeKey = longPreferencesKey("reset_opportunity_observed_at_" + observation.accountId)
        val countKey = intPreferencesKey("reset_opportunity_count_" + observation.accountId)
        var notify = false
        context.dataStore.edit { prefs ->
            val previousAt = prefs[timeKey] ?: 0L
            if (observation.fetchedAtEpochMs > previousAt) {
                val previous = ResetOpportunityObservation(observation.accountId, prefs[countKey], previousAt)
                notify = isResetOpportunityIncrease(previous, observation, canNotify && (prefs[PreferencesKeys.RESET_OPPORTUNITY_ALERTS_ENABLED] ?: false))
                prefs[timeKey] = observation.fetchedAtEpochMs
                val count = observation.count?.takeIf { it >= 0 }
                if (count == null) prefs.remove(countKey) else prefs[countKey] = count
            }
        }
        return notify
    }

    suspend fun setIncludeWeeklyQuotaAlerts(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[PreferencesKeys.INCLUDE_WEEKLY_QUOTA_ALERTS] = enabled
        }
    }

    suspend fun setIncludeGptReserveAlerts(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[PreferencesKeys.INCLUDE_GPT_RESERVE_ALERTS] = enabled
        }
    }

    suspend fun setQuotaAlertThresholds(thresholds: Set<Int>) {
        context.dataStore.edit { prefs ->
            prefs[PreferencesKeys.QUOTA_ALERT_THRESHOLDS] = thresholds.map { it.toString() }.toSet()
        }
    }

    suspend fun toggleQuotaAlertThreshold(threshold: Int) {
        context.dataStore.edit { prefs ->
            val current = (prefs[PreferencesKeys.QUOTA_ALERT_THRESHOLDS] ?: setOf("5", "10", "25"))
                .mapNotNull { it.toIntOrNull() }
                .toMutableSet()

            if (current.contains(threshold)) {
                if (current.size > 1) {
                    current.remove(threshold)
                }
            } else {
                current.add(threshold)
            }
            prefs[PreferencesKeys.QUOTA_ALERT_THRESHOLDS] = current.map { it.toString() }.toSet()
        }
    }

    suspend fun setHasCompletedOnboarding(completed: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[PreferencesKeys.ONBOARDING_COMPLETED] = completed
        }
    }

    suspend fun setRenewalBannerDismissed(accountId: String, dismissed: Boolean = true) {
        context.dataStore.edit { prefs ->
            val current = prefs[PreferencesKeys.DISMISSED_RENEWAL_BANNERS] ?: emptySet()
            prefs[PreferencesKeys.DISMISSED_RENEWAL_BANNERS] = if (dismissed) {
                current + accountId
            } else {
                current - accountId
            }
        }
    }

    suspend fun getLastNotifiedQuotaThreshold(accountId: String, window: QuotaWindow = QuotaWindow.WEEKLY): Int? {
        val prefs = context.dataStore.data.first()
        val key = quotaAlertThresholdKey(accountId, window)
        return prefs[key]
    }

    suspend fun setLastNotifiedQuotaThreshold(accountId: String, threshold: Int?, window: QuotaWindow = QuotaWindow.WEEKLY) {
        val key = quotaAlertThresholdKey(accountId, window)
        context.dataStore.edit { prefs ->
            if (threshold != null) {
                prefs[key] = threshold
            } else {
                prefs.remove(key)
            }
        }
    }

    private fun quotaAlertThresholdKey(accountId: String, window: QuotaWindow): Preferences.Key<Int> {
        return when (window) {
            QuotaWindow.WEEKLY -> intPreferencesKey("last_quota_alert_threshold_$accountId")
            QuotaWindow.FIVE_HOUR -> intPreferencesKey("last_quota_alert_threshold_5h_$accountId")
            QuotaWindow.GPT_RESERVE -> intPreferencesKey("last_quota_alert_threshold_gpt_reserve_$accountId")
        }
    }

    suspend fun isSignedOutAlertNotified(accountId: String): Boolean {
        val prefs = context.dataStore.data.first()
        val key = booleanPreferencesKey("last_signed_out_notified_$accountId")
        return prefs[key] ?: false
    }

    suspend fun setSignedOutAlertNotified(accountId: String, notified: Boolean) {
        val key = booleanPreferencesKey("last_signed_out_notified_$accountId")
        context.dataStore.edit { prefs ->
            if (notified) {
                prefs[key] = true
            } else {
                prefs.remove(key)
            }
        }
    }
}
