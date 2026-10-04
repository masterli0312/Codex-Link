package com.codex.quota.ui.feature.credithistory

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.History
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.codex.quota.CodexQuotaApplication
import com.codex.quota.R
import com.codex.quota.domain.model.AuthStatus
import com.codex.quota.ui.util.localizedAccountNickname
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import com.codex.quota.domain.model.CreditObservation
import com.codex.quota.domain.model.CreditStatistics
import com.codex.quota.ui.components.rememberQuotaClock
import com.codex.quota.ui.components.SectionSurface
import com.codex.quota.ui.components.SectionTitle
import com.codex.quota.domain.model.CreditHour
import com.codex.quota.data.local.entity.CreditHistoryEntity
import java.time.ZoneId
import java.time.Instant

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreditHistoryScreen(app: CodexQuotaApplication, accountId: String, onBack: () -> Unit) {
    val entries by remember(accountId) { app.database.creditHistoryDao().observe(accountId) }.collectAsState(initial = null)
    val account by remember(accountId) { app.repository.observeAccount(accountId) }.collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var refreshing by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val numbers = remember(locale) { NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = 6 } }
    val dates = remember(locale) { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale) }
    val hourDates = remember(locale) { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, locale) }
    val days = remember(locale) { DateFormat.getDateInstance(DateFormat.MEDIUM, locale) }
    val times = remember(locale) { DateFormat.getTimeInstance(DateFormat.SHORT, locale) }
    val now = rememberQuotaClock()
    val zone = ZoneId.systemDefault()
    val statistics = remember(entries, accountId, now, zone) { CreditStatistics.calculate(entries.orEmpty().map {
        CreditObservation(it.id, it.accountId, it.observedAtEpochMs, it.balance, it.change)
    }, accountId, now, zone) }
    var showAllHours by remember { mutableStateOf(false) }
    var visibleChanges by rememberSaveable(accountId) { mutableIntStateOf(8) }
    var showExplanation by rememberSaveable(accountId) { mutableStateOf(false) }
    val groupedChanges = remember(entries, visibleChanges, zone) {
        entries.orEmpty().take(visibleChanges).groupBy { Instant.ofEpochMilli(it.observedAtEpochMs).atZone(zone).toLocalDate() }
    }
    Scaffold(containerColor = MaterialTheme.colorScheme.background, topBar = { CenterAlignedTopAppBar(
        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = MaterialTheme.colorScheme.background),
        title = { Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(R.string.credit_history_title))
        account?.let { Text(localizedAccountNickname(context, it.account), style = MaterialTheme.typography.labelSmall) }
    } }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back)) } },
        actions = { IconButton(enabled = !refreshing && account != null, onClick = {
            refreshing = true
            scope.launch {
                try {
                    val result = app.repository.refreshAccount(accountId)
                    if (result.isFailure || result.getOrNull()?.status != AuthStatus.AUTHENTICATED)
                        snackbar.showSnackbar(context.getString(R.string.error_refresh_account))
                } catch (error: CancellationException) { throw error }
                catch (_: Exception) { snackbar.showSnackbar(context.getString(R.string.error_refresh_account)) }
                finally { refreshing = false }
            }
        }) { if (refreshing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            else Icon(Icons.Default.Refresh, stringResource(R.string.refresh_usage_data)) } }) },
        snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                SectionSurface { Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SectionTitle(stringResource(R.string.official_credit), Icons.Outlined.AccountBalanceWallet)
                    Text(account?.usage?.remainingCredits?.takeIf { it.isFinite() }?.let(numbers::format) ?: stringResource(R.string.value_unavailable),
                        style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold)
                    Text(stringResource(R.string.credit_detail_sampling_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } }
            }
            if (entries == null) item { Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
            else if (entries!!.isEmpty()) item { Text(stringResource(R.string.credit_history_empty), style = MaterialTheme.typography.bodyMedium) }
            else {
                item { SectionSurface { Column(Modifier.padding(20.dp)) { CreditStatisticsPanel(statistics, compact = true) } } }
                item {
                    SectionSurface {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            SectionTitle(stringResource(R.string.credit_hourly_title), Icons.Outlined.Schedule)
                            val maximum = statistics.hourly.maxOfOrNull { it.decrease } ?: 0.0
                            statistics.hourly.take(if (showAllHours) 24 else 6).forEach { hour ->
                                CreditHourlyRow(hour, hourDates.format(Date(hour.startEpochMs)), numbers, maximum)
                            }
                            TextButton(onClick = { showAllHours = !showAllHours }, modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(if (showAllHours) R.string.credit_hours_collapse else R.string.credit_hours_expand))
                            }
                        }
                    }
                }
                item { SectionTitle(stringResource(R.string.credit_change_details), Icons.Outlined.History) }
            }
            groupedChanges.forEach { (day, changes) ->
                item(key = "changes:$day") {
                    SectionSurface {
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                            Text(days.format(Date.from(day.atStartOfDay(zone).toInstant())), Modifier.padding(bottom = 12.dp),
                                style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(stringResource(R.string.credit_history_time_column), Modifier.width(56.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(stringResource(R.string.credit_history_balance_column), Modifier.weight(1.2f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(stringResource(R.string.credit_history_change_column), Modifier.weight(1f), style = MaterialTheme.typography.labelSmall,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.End, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            changes.forEachIndexed { index, entry ->
                                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                                CreditChangeRow(entry, times.format(Date(entry.observedAtEpochMs)), numbers)
                            }
                        }
                    }
                }
            }
            if (entries.orEmpty().size > visibleChanges) item {
                TextButton(onClick = { visibleChanges += 20 }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.credit_history_more)) }
            }
            item {
                TextButton(onClick = { showExplanation = !showExplanation }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.credit_history_info)) }
                if (showExplanation) Text(stringResource(R.string.credit_history_explanation) + "\n\n" + stringResource(R.string.credit_hourly_explanation),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun CreditChangeRow(entry: CreditHistoryEntity, time: String, numbers: NumberFormat) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(time, Modifier.width(56.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(numbers.format(entry.balance), Modifier.weight(1.2f), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        Text(entry.change?.let { (if (it > 0) "+" else "") + numbers.format(it) } ?: stringResource(R.string.credit_history_baseline),
            Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, textAlign = androidx.compose.ui.text.style.TextAlign.End,
            color = if (entry.change != null && entry.change > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun CreditHourlyRow(hour: CreditHour, time: String, numbers: NumberFormat, maximum: Double) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(time, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(if (hour.changes == 0) stringResource(R.string.credit_hour_no_records) else numbers.format(hour.decrease),
                Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, textAlign = androidx.compose.ui.text.style.TextAlign.End,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (hour.changes > 0) {
            if (hour.increase > 0) Text(stringResource(R.string.credit_hour_increase_amount, numbers.format(hour.increase)),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            // Decorative comparison of recorded decreases, not a quota percentage or billing estimate.
            Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
                val fraction = if (maximum > 0) (hour.decrease / maximum).coerceIn(0.0, 1.0).toFloat() else 0f
                if (fraction > 0) Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(MaterialTheme.colorScheme.primary))
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}
