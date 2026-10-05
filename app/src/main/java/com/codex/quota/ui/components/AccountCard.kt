package com.codex.quota.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.codex.quota.R
import com.codex.quota.data.remote.CF_BLOCKED_ERROR
import com.codex.quota.domain.model.AccountWithUsage
import com.codex.quota.domain.model.AuthStatus
import com.codex.quota.ui.util.localizedAccountNickname
import com.codex.quota.ui.util.localizedShortPlanName
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import com.codex.quota.ui.theme.UiMetrics

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AccountCard(item: AccountWithUsage, onClick: () -> Unit, onSignInClick: () -> Unit, modifier: Modifier = Modifier,
    onLongClick: () -> Unit = {}, onCreditClick: () -> Unit = onClick,
    refreshing: Boolean = false, refreshFeedback: Int? = null) {
    val account = item.account
    val usage = item.usage
    val context = LocalContext.current
    val subscriptionDate = usage?.subscriptionRenewalEpochMs?.takeIf { it > 0L }
        ?: account.customRenewalDateEpochMs?.takeIf { it > 0L }
    val now = rememberQuotaClock()
    val signedOut = (usage?.status ?: account.authStatus) == AuthStatus.AUTHENTICATION_REQUIRED
    Card(modifier = modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick, onLongClickLabel = stringResource(R.string.manage_accounts)), shape = RoundedCornerShape(UiMetrics.CardRadius), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), elevation = CardDefaults.cardElevation(defaultElevation = 0.dp), border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(UiMetrics.ContentGap)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(localizedAccountNickname(context, account), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(localizedShortPlanName(context, account.planType), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                StatusBadge(usage?.status ?: account.authStatus)
            }
            if (usage?.errorMessage == CF_BLOCKED_ERROR) {
                Text(stringResource(R.string.node_blocked_message), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            }
            if (signedOut) {
                TextButton(onClick = onSignInClick) { Text(stringResource(R.string.re_authenticate_now)) }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    CircularQuotaGauge(usage?.remainingPercent?.takeUnless { usage.status != AuthStatus.AUTHENTICATED && usage.resetAtEpochMs?.let { reset -> reset <= now } == true }, size = 82.dp, strokeWidth = 8.dp)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        QuotaWindowLine(stringResource(R.string.quota_five_hour), usage?.fiveHourRemainingPercent, usage?.fiveHourResetAtEpochMs, now, status = usage?.status,
                            unavailableReason = if (usage?.isWeeklyQuotaExhausted == true) stringResource(R.string.five_hour_waiting_weekly) else null)
                        QuotaWindowLine(stringResource(R.string.quota_weekly), usage?.remainingPercent, usage?.resetAtEpochMs, now, status = usage?.status)
                    }
                }
                Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(12.dp))) {
                    Box(Modifier.weight(1f).clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onCreditClick).heightIn(min = UiMetrics.TouchTarget).padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                        SummaryMetric(stringResource(R.string.official_credit), usage?.remainingCredits?.let { NumberFormat.getNumberInstance(LocalConfiguration.current.locales[0]).format(it) } ?: stringResource(R.string.value_unavailable))
                    }
                    Box(Modifier.weight(1f).heightIn(min = UiMetrics.TouchTarget).padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                        SummaryMetric(stringResource(R.string.reset_opportunities), usage?.bankedResets?.toString() ?: stringResource(R.string.value_unavailable))
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                val subscriptionText = subscriptionDate?.takeIf { it > 0L }?.let { date ->
                    val formattedDate = SimpleDateFormat(
                        stringResource(R.string.subscription_card_date_pattern),
                        LocalConfiguration.current.locales[0]
                    ).format(Date(date))
                    stringResource(
                        when (usage?.willAutoRenew) {
                            true -> R.string.subscription_renews
                            false -> R.string.subscription_expires
                            null -> R.string.subscription_date
                        },
                        formattedDate
                    )
                } ?: stringResource(R.string.subscription_date_unavailable)
                Text(subscriptionText, modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.width(8.dp))
                Box(Modifier.width(96.dp).heightIn(min = 20.dp), contentAlignment = Alignment.CenterEnd) {
                    when {
                        refreshing -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp)
                            Text(stringResource(R.string.account_refreshing), style = MaterialTheme.typography.labelSmall, maxLines = 1)
                        }
                        refreshFeedback == R.string.account_refresh_failed -> Text(stringResource(refreshFeedback),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        else -> RelativeTimeText(account.lastSuccessfulSyncEpochMs, style = MaterialTheme.typography.labelSmall, now = now)
                    }
                }
            }
        }
    }
}

@Composable
private fun SummaryMetric(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
    }
}
