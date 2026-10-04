package com.codex.quota.ui.feature.accountdetail

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.codex.quota.R
import com.codex.quota.data.remote.CF_BLOCKED_ERROR
import com.codex.quota.domain.model.AuthStatus
import com.codex.quota.domain.usecase.ResetSpendOutcome
import com.codex.quota.ui.components.RelativeTimeText
import com.codex.quota.ui.components.StatusBadge
import com.codex.quota.ui.components.rememberQuotaClock
import com.codex.quota.ui.util.formatQuotaPercent
import com.codex.quota.ui.util.localizedAccountNickname
import com.codex.quota.ui.util.localizedShortPlanName
import com.codex.quota.domain.model.CreditObservation
import com.codex.quota.domain.model.CreditStatistics
import com.codex.quota.domain.model.isApiKeyPlan
import com.codex.quota.ui.feature.credithistory.CreditStatisticsPanel
import com.codex.quota.ui.components.SectionSurface
import com.codex.quota.ui.components.SectionIcon
import com.codex.quota.ui.components.SectionTitle
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import java.time.ZoneId

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountDetailScreen(viewModel: AccountDetailViewModel, onNavigateBack: () -> Unit, onCreditHistory: () -> Unit, modifier: Modifier = Modifier) {
    val loaded by viewModel.accountLoaded.collectAsState()
    val data by viewModel.accountState.collectAsState()
    val refreshing by viewModel.isRefreshing.collectAsState()
    val deleted by viewModel.accountDeleted.collectAsState()
    val resetting by viewModel.isResetting.collectAsState()
    val resetOutcome by viewModel.resetOutcome.collectAsState()
    val message by viewModel.uiMessage.collectAsState()
    val snackbars = remember { SnackbarHostState() }
    LaunchedEffect(message) { message?.let { snackbars.showSnackbar(it); viewModel.clearUiMessage() } }
    var editNickname by remember { mutableStateOf(false) }
    var nickname by remember { mutableStateOf("") }
    var nicknameError by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    var showReset by remember { mutableStateOf(false) }
    LaunchedEffect(deleted) { if (deleted) onNavigateBack() }
    val account = data?.account
    val usage = data?.usage
    val creditHistory by viewModel.creditHistory.collectAsState()
    val preferences by viewModel.preferences.collectAsState()
    val updatingActivation by viewModel.isUpdatingActivation.collectAsState()
    val context = LocalContext.current
    val now = rememberQuotaClock()
    val zone = ZoneId.systemDefault()
    val creditStatistics = remember(creditHistory, account?.id, now, zone) { CreditStatistics.calculate(creditHistory.map {
        CreditObservation(it.id, it.accountId, it.observedAtEpochMs, it.balance, it.change)
    }, account?.id.orEmpty(), now, zone) }
    Scaffold(modifier = modifier.fillMaxSize(), containerColor = MaterialTheme.colorScheme.background, snackbarHost = { SnackbarHost(snackbars) }, topBar = {
        CenterAlignedTopAppBar(colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = MaterialTheme.colorScheme.background), title = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(account?.let { localizedAccountNickname(context, it) } ?: stringResource(R.string.account_details), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (account != null) Text(localizedShortPlanName(context, account.planType), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }, navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back)) } }, actions = {
            if (account != null) IconButton(onClick = { nickname = account.nickname; nicknameError = false; editNickname = true }) {
                Icon(Icons.Outlined.Edit, stringResource(R.string.edit_account_nickname))
            }
            IconButton(onClick = viewModel::refresh, enabled = !refreshing) { Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.refresh_usage_data)) }
        })
    }) { padding ->
        if (account == null) Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
            if (!loaded) CircularProgressIndicator()
            else Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.account_missing))
                TextButton(onClick = onNavigateBack) { Text(stringResource(R.string.action_back)) }
            }
        }
        else LazyColumn(modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (refreshing) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            item {
                Panel {
                    SectionTitle(stringResource(R.string.account_connection_status), Icons.Outlined.VerifiedUser)
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        StatusBadge(usage?.status ?: account.authStatus)
                        RelativeTimeText(account.lastSuccessfulSyncEpochMs, style = MaterialTheme.typography.labelSmall, now = now)
                    }
                    data?.let { com.codex.quota.ui.components.ActivationStatusText(it, now) }
                    if (usage?.errorMessage == CF_BLOCKED_ERROR) {
                        Text(stringResource(R.string.node_blocked_message), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            item {
                Panel {
                    CreditStatisticsPanel(creditStatistics, compact = true)
                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    TextButton(onClick = onCreditHistory, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Outlined.History, null, Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.credit_history_title), Modifier.weight(1f), textAlign = TextAlign.Start)
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
                    }
                }
            }
            if (!account.planType.isApiKeyPlan) item {
                Panel {
                    val activationEnabled = preferences != null && !updatingActivation && !account.isDemoAccount
                    val activationChecked = account.id in preferences?.autoActivateFiveHourAccountIds.orEmpty()
                    Row(Modifier.fillMaxWidth().toggleable(activationChecked, enabled = activationEnabled, role = Role.Switch,
                        onValueChange = viewModel::setAutoActivation), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        SectionIcon(Icons.Outlined.Bolt)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(stringResource(R.string.account_auto_activation_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text(stringResource(R.string.account_auto_activation_summary), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = activationChecked, onCheckedChange = null, enabled = activationEnabled)
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.account_auto_activation_network), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { showReset = true }, enabled = !resetting && usage?.status == AuthStatus.AUTHENTICATED && (usage.bankedResets ?: 0) > 0, shape = RoundedCornerShape(16.dp), modifier = Modifier.weight(1f).heightIn(min = 52.dp)) {
                        if (resetting) { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
                        Text(stringResource(R.string.use_reset))
                    }
                    OutlinedButton(onClick = { showDelete = true }, modifier = Modifier.weight(1f).heightIn(min = 52.dp), shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                        Icon(Icons.Default.Delete, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.remove_account))
                    }
                }
            }
            item { Spacer(Modifier.height(8.dp)) }
        }
    }
    if (editNickname && account != null) AlertDialog(onDismissRequest = { editNickname = false },
        title = { Text(stringResource(R.string.edit_account_nickname)) },
        text = { OutlinedTextField(value = nickname, onValueChange = { nickname = it; nicknameError = false },
            label = { Text(stringResource(R.string.nickname)) }, singleLine = true, isError = nicknameError,
            supportingText = { if (nicknameError) Text(stringResource(R.string.error_nickname_required)) }) },
        confirmButton = { TextButton(onClick = {
            if (nickname.isBlank()) nicknameError = true
            else {
                viewModel.updateAccountDetails(nickname.trim(), account.colorHex, account.customRenewalDateEpochMs)
                editNickname = false
            }
        }) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(onClick = { editNickname = false }) { Text(stringResource(R.string.action_cancel)) } })
    if (showDelete) AlertDialog(onDismissRequest = { showDelete = false }, title = { Text(stringResource(R.string.remove_account)) }, text = { Text(stringResource(R.string.remove_account_message, account?.let { localizedAccountNickname(context, it) } ?: "")) }, confirmButton = { TextButton(onClick = { showDelete = false; viewModel.deleteAccount() }) { Text(stringResource(R.string.remove_account)) } }, dismissButton = { TextButton(onClick = { showDelete = false }) { Text(stringResource(R.string.action_cancel)) } })
    if (showReset) AlertDialog(onDismissRequest = { showReset = false }, title = { Text(stringResource(R.string.reset_confirm_title)) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.reset_consumes_one))
            Text(stringResource(R.string.reset_after_label), fontWeight = FontWeight.Bold)
            LabelValue(stringResource(R.string.quota_weekly), stringResource(R.string.reset_weekly_preview, formatQuotaPercent(100.0)))
            LabelValue(stringResource(R.string.reset_remaining), ((usage?.bankedResets ?: 1) - 1).toString())
            Text(stringResource(R.string.reset_irreversible), color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = { Button(onClick = { showReset = false; viewModel.consumeReset() }, enabled = !resetting && usage?.status == AuthStatus.AUTHENTICATED) { Text(stringResource(R.string.reset_confirm_action)) } }, dismissButton = { TextButton(onClick = { showReset = false }) { Text(stringResource(R.string.action_cancel)) } })

    when (val outcome = resetOutcome) {
        is ResetSpendOutcome.Success -> AlertDialog(
            onDismissRequest = viewModel::clearResetOutcome,
            title = { Text(stringResource(R.string.reset_success_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LabelValue(stringResource(R.string.quota_weekly), formatQuotaPercent(outcome.freshUsage?.remainingPercent))
                    LabelValue(stringResource(R.string.reset_remaining), outcome.freshUsage?.bankedResets?.toString() ?: stringResource(R.string.value_unavailable))
                    Text(stringResource(if (outcome.freshUsage == null) R.string.reset_success_refresh_failed else R.string.reset_success_refresh_note))
                }
            },
            confirmButton = { TextButton(onClick = viewModel::clearResetOutcome) { Text(stringResource(R.string.action_got_it)) } }
        )
        null -> Unit
        else -> AlertDialog(
            onDismissRequest = viewModel::clearResetOutcome,
            title = { Text(stringResource(R.string.use_reset)) },
            text = { Text(stringResource(when (outcome) {
                ResetSpendOutcome.NoCredit -> R.string.reset_no_credit
                ResetSpendOutcome.NothingToReset -> R.string.reset_nothing_to_reset
                ResetSpendOutcome.Uncertain -> R.string.reset_uncertain
                else -> R.string.reset_unavailable
            })) },
            confirmButton = { TextButton(onClick = viewModel::clearResetOutcome) { Text(stringResource(R.string.action_got_it)) } }
        )
    }
}


@Composable
private fun Panel(content: @Composable ColumnScope.() -> Unit) {
    SectionSurface {
        Column(Modifier.fillMaxWidth().padding(18.dp), content = content)
    }
}

@Composable
private fun LabelValue(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, Modifier.weight(0.45f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, Modifier.weight(0.55f), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, textAlign = TextAlign.End)
    }
}
