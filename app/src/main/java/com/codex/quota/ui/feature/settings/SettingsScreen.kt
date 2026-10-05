package com.codex.quota.ui.feature.settings

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
import com.codex.quota.R
import com.codex.quota.domain.model.AppThemeMode
import com.codex.quota.domain.model.RefreshIntervalMinutes

private data class SettingEntry(val page: String, val title: Int, val subtitle: Int, val icon: androidx.compose.ui.graphics.vector.ImageVector)

private val entries = listOf(
    SettingEntry("language", R.string.language_title, R.string.language_summary, Icons.Outlined.Language),
    SettingEntry("appearance", R.string.appearance_theme, R.string.appearance_summary, Icons.Outlined.Palette),
    SettingEntry("sync", R.string.background_synchronization, R.string.sync_summary, Icons.Outlined.Sync),
    SettingEntry("notifications", R.string.notifications_alerts, R.string.notifications_summary, Icons.Outlined.Notifications),
    SettingEntry("task_notifications", R.string.computer_connection_settings_title, R.string.computer_connection_settings_summary, Icons.Outlined.Computer),
    SettingEntry("privacy", R.string.privacy_local_storage, R.string.privacy_summary, Icons.Outlined.Lock)
)
private val pages = entries

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel, modifier: Modifier = Modifier, page: String = "home", onNavigate: (String) -> Unit = {}, onNavigateBack: () -> Unit = {}) {
    val preferences by viewModel.preferencesState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showClearData by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    fun notificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    val currentPage = page.takeIf { it == "home" || pages.any { entry -> entry.page == it } } ?: "home"
    val title = if (currentPage == "home") R.string.settings_title else pages.first { it.page == currentPage }.title
    Scaffold(modifier = modifier.fillMaxSize(), containerColor = MaterialTheme.colorScheme.background,
        topBar = { CenterAlignedTopAppBar(
            title = { Text(stringResource(title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold) },
            colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            navigationIcon = { if (currentPage != "home") IconButton(onClick = onNavigateBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back)) } }
        ) }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (currentPage) {
                "home" -> {
                    Spacer(Modifier.height(4.dp))
                    SettingsGroup {
                        entries.forEachIndexed { index, entry ->
                            if (index > 0) SettingsDivider(startInset = 64.dp)
                            SettingsNavigationRow(stringResource(entry.title), stringResource(entry.subtitle), entry.icon) { onNavigate(entry.page) }
                        }
                    }
                }
                "language" -> {
                    SettingsHeading(stringResource(R.string.language_title), stringResource(R.string.language_summary))
                    SettingsGroup {
                        Column(Modifier.selectableGroup()) {
                            val selected = AppLanguage.fromLanguageTags(AppCompatDelegate.getApplicationLocales().toLanguageTags())
                            AppLanguage.entries.forEachIndexed { index, language ->
                                if (index > 0) SettingsDivider()
                                val label = when (language) {
                                    AppLanguage.FOLLOW_SYSTEM -> R.string.language_follow_system
                                    AppLanguage.SIMPLIFIED_CHINESE -> R.string.language_chinese
                                    AppLanguage.ENGLISH -> R.string.language_english
                                }
                                SettingsSelectRow(stringResource(label), selected == language, {
                                    AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(language.languageTag))
                                })
                            }
                        }
                    }
                }
                "appearance" -> {
                    SettingsHeading(stringResource(R.string.app_theme))
                    SettingsGroup {
                        Column(Modifier.selectableGroup()) {
                            AppThemeMode.entries.forEachIndexed { index, mode ->
                                if (index > 0) SettingsDivider()
                                val label = when (mode) { AppThemeMode.SYSTEM -> R.string.theme_system; AppThemeMode.LIGHT -> R.string.theme_light; AppThemeMode.DARK -> R.string.theme_dark }
                                SettingsSelectRow(stringResource(label), preferences.themeMode == mode, { viewModel.setThemeMode(mode) })
                            }
                        }
                    }
                    SettingsHeading(stringResource(R.string.settings_colors))
                    SettingsGroup {
                        SettingsToggleRow(stringResource(R.string.dynamic_colors_material_you), stringResource(R.string.adaptive_palette_based_on_system_wallpaper), preferences.dynamicColor, viewModel::setDynamicColor)
                        SettingsDivider()
                        SettingsToggleRow(stringResource(R.string.solid_color_theme), stringResource(R.string.solid_color_summary), !preferences.dynamicColor, { viewModel.setDynamicColor(!it) })
                    }
                }
                "sync" -> {
                    SettingsHeading(stringResource(R.string.background_synchronization))
                    SettingsGroup {
                        SettingsToggleRow(stringResource(R.string.periodic_background_sync), stringResource(R.string.app_wakes_periodically_in_background_to_refresh_quotas), preferences.backgroundSyncEnabled, { viewModel.setBackgroundSyncEnabled(context, it) })
                    }
                    SettingsHeading(stringResource(R.string.refresh_frequency))
                    SettingsGroup {
                        Column(Modifier.selectableGroup()) {
                            listOf(RefreshIntervalMinutes.MINUTES_15, RefreshIntervalMinutes.MINUTES_30, RefreshIntervalMinutes.HOURS_1, RefreshIntervalMinutes.HOURS_3).forEachIndexed { index, interval ->
                                if (index > 0) SettingsDivider()
                                val label = when (interval) { RefreshIntervalMinutes.MINUTES_15 -> R.string.interval_15_minutes; RefreshIntervalMinutes.MINUTES_30 -> R.string.interval_30_minutes; RefreshIntervalMinutes.HOURS_1 -> R.string.interval_1_hour; else -> R.string.interval_3_hours }
                                SettingsSelectRow(stringResource(label), preferences.refreshInterval == interval,
                                    { viewModel.setRefreshInterval(context, interval) }, enabled = preferences.backgroundSyncEnabled)
                            }
                        }
                    }
                }
                "notifications" -> {
                    SettingsHeading(stringResource(R.string.low_quota_warnings))
                    SettingsGroup {
                        SettingsToggleRow(stringResource(R.string.low_quota_warnings), stringResource(R.string.alerts_when_an_account_reaches_critical_quota_thresholds), preferences.quotaAlertsEnabled, { if (it) notificationPermission(); viewModel.setQuotaAlertsEnabled(it) })
                        SettingsDivider()
                        SettingsToggleRow(stringResource(R.string.five_hour_warning), stringResource(R.string.five_hour_warning_summary), preferences.includeFiveHourQuotaAlerts, { if (it) notificationPermission(); viewModel.setIncludeFiveHourQuotaAlerts(it) })
                        SettingsDivider()
                        SettingsToggleRow(stringResource(R.string.weekly_warning), stringResource(R.string.weekly_warning_summary), preferences.includeWeeklyQuotaAlerts, { if (it) notificationPermission(); viewModel.setIncludeWeeklyQuotaAlerts(it) })
                    }
                    SettingsHeading(stringResource(R.string.settings_reset_alerts))
                    SettingsGroup {
                        SettingsToggleRow(stringResource(R.string.five_hour_reset_reminder), stringResource(R.string.five_hour_reset_reminder_summary), preferences.fiveHourResetReminderEnabled, { if (it) notificationPermission(); viewModel.setFiveHourResetReminderEnabled(context, it) })
                        SettingsDivider()
                        SettingsToggleRow(stringResource(R.string.weekly_reset_reminder), stringResource(R.string.weekly_reset_reminder_summary), preferences.weeklyResetReminderEnabled,
                            { if (it) notificationPermission(); viewModel.setWeeklyResetReminderEnabled(context, it) })
                        SettingsDivider()
                        SettingsToggleRow(stringResource(R.string.reset_alert), stringResource(R.string.reset_opportunity_alert_summary), preferences.resetOpportunityAlertsEnabled,
                            { if (it) notificationPermission(); viewModel.setResetOpportunityAlertsEnabled(it) })
                    }
                    SettingsHeading(stringResource(R.string.alert_method))
                    SettingsGroup { SettingsInfo(stringResource(R.string.system_notification), stringResource(R.string.notification_channel_controls), Icons.Outlined.NotificationsActive) }
                }
                "privacy" -> {
                    SettingsHeading(stringResource(R.string.privacy_local_storage))
                    SettingsGroup {
                        SettingsInfo(stringResource(R.string.zero_telemetry), stringResource(R.string.zero_telemetry_detail), Icons.Outlined.Shield)
                        SettingsDivider()
                        SettingsInfo(stringResource(R.string.local_encryption), stringResource(R.string.local_encryption_detail), Icons.Outlined.Key)
                        SettingsDivider()
                        SettingsInfo(stringResource(R.string.no_cloud_relay), stringResource(R.string.no_cloud_relay_detail), Icons.Outlined.CloudOff)
                    }
                    SettingsHeading(stringResource(R.string.settings_local_data))
                    OutlinedButton(onClick = { showClearData = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                        shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                        Icon(Icons.Outlined.DeleteForever, null)
                        Spacer(Modifier.width(10.dp))
                        Text(stringResource(R.string.clear_all_local_data_secrets))
                    }
                    Text(stringResource(R.string.clear_data_detail), Modifier.padding(horizontal = 4.dp),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                "task_notifications" -> TaskNotificationSettingsPanel()
            }
            Spacer(Modifier.height(24.dp))
        }
    }
    if (showClearData) AlertDialog(onDismissRequest = { showClearData = false }, title = { Text(stringResource(R.string.clear_all_data)) },
        text = { Text(stringResource(R.string.this_will_permanently_delete_all_registered_accounts_cached_usage)) },
        confirmButton = { TextButton(onClick = { showClearData = false; viewModel.clearAllData(context) }) { Text(stringResource(R.string.clear_everything), color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { showClearData = false }) { Text(stringResource(R.string.action_cancel)) } })
}
