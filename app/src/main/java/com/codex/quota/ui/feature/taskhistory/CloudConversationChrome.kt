package com.codex.quota.ui.feature.taskhistory

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.codex.quota.R
import com.codex.quota.data.cloud.CloudDetails
import com.codex.quota.data.cloud.CloudTask
import com.codex.quota.domain.model.CodexUsage
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date

@Composable
internal fun cloudConversationTitle(task: CloudTask?, details: CloudDetails?): String =
    task?.title?.takeIf { it.isNotBlank() }
        ?: details?.messages?.firstOrNull { it.role == "user" }?.text?.lineSequence()?.firstOrNull()?.take(80)
            ?.takeIf { it.isNotBlank() }
        ?: stringResource(R.string.cloud_untitled_conversation)

/** Cloud dot describes the last successful read, never a fabricated persistent socket. */
@Composable
internal fun CloudConversationHeader(title: String, subtitle: String, synced: Boolean, onBack: () -> Unit,
    onEnvironment: () -> Unit, onStatus: () -> Unit, onMenu: (() -> Unit)?, menu: @Composable () -> Unit = {}) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back)) }
        Surface(onClick = onEnvironment, modifier = Modifier.weight(1f), shape = RoundedCornerShape(28.dp),
            color = Color.Transparent) {
            Column(Modifier.padding(horizontal = 4.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Icon(Icons.Outlined.CloudQueue, null, Modifier.size(15.dp), tint = colors.onSurfaceVariant)
                    Box(Modifier.size(5.dp).background(if (synced) Color(0xFF008575) else colors.outline, CircleShape))
                    Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                }
            }
        }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onStatus) { ConversationStatusIcon() }
                if (onMenu != null) Box {
                    IconButton(onClick = onMenu) { Icon(Icons.Outlined.MoreVert, stringResource(R.string.cloud_conversation_actions)) }
                    menu()
                }
            }
    }
}

@Composable
internal fun CloudSheetAction(label: String, icon: ImageVector, onClick: () -> Unit, enabled: Boolean = true,
    selected: Boolean = false, supporting: String? = null) {
    Surface(onClick = onClick, enabled = enabled, color = Color.Transparent,
        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Row(Modifier.padding(horizontal = 18.dp, vertical = 14.dp).heightIn(min = 24.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Icon(icon, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(label, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (!supporting.isNullOrBlank()) Text(supporting, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (selected) Icon(Icons.Outlined.Check, null, tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CloudSelectionSheet(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp).padding(bottom = 24.dp)) {
            Text(title, Modifier.padding(horizontal = 18.dp, vertical = 12.dp), style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
internal fun CloudQuotaStatus(usage: CodexUsage?, fetchedAt: Long, task: CloudTask?, details: CloudDetails?, onDismiss: () -> Unit) {
    val locale = LocalConfiguration.current.locales[0]
    AlertDialog(onDismissRequest = onDismiss, confirmButton = {}, shape = RoundedCornerShape(28.dp),
        title = { Text(stringResource(R.string.remote_session_title)) }, text = {
            Column(Modifier.heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.65f).dp)
                .verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Text(stringResource(cloudStatus(task?.status)), style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                val contextUsed = details?.contextUsed
                val contextLimit = details?.contextLimit
                val contextValue = if (contextUsed != null && contextLimit != null && contextLimit > 0 && contextUsed in 0..contextLimit) {
                    val unit = stringResource(R.string.remote_session_count_unit)
                    val below = stringResource(R.string.remote_session_below_unit)
                    stringResource(R.string.remote_session_context_value, ((1 - contextUsed.toDouble() / contextLimit) * 100).toInt(),
                        com.codex.quota.notifications.task.RemoteStatusFormat.compactCount(contextUsed, locale, unit, below),
                        com.codex.quota.notifications.task.RemoteStatusFormat.compactCount(contextLimit, locale, unit, below))
                } else stringResource(R.string.remote_session_unknown)
                CloudStatusValue(stringResource(R.string.remote_session_context), contextValue)
                listOf(Triple(R.string.remote_session_five_hour, usage?.fiveHourRemainingPercent, usage?.fiveHourResetAtEpochMs),
                    Triple(R.string.remote_session_weekly, usage?.remainingPercent, usage?.resetAtEpochMs)).forEach { (label, percent, reset) ->
                    val value = percent?.takeIf { it.isFinite() && it in 0.0..100.0 }?.let {
                        val formatted = NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = 1 }.format(it)
                        if (reset != null && reset > 0) stringResource(R.string.remote_session_window_value, formatted,
                            SimpleDateFormat(if (label == R.string.remote_session_five_hour) "HH:mm" else stringResource(R.string.remote_session_date_pattern), locale).format(Date(reset)))
                        else stringResource(R.string.remote_session_remaining, formatted)
                    } ?: stringResource(R.string.remote_session_unknown)
                    CloudStatusValue(stringResource(label), value)
                }
                if (fetchedAt > 0) Text(stringResource(R.string.cloud_last_synced, conversationTime(fetchedAt, System.currentTimeMillis())),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        })
}

@Composable
private fun CloudStatusValue(title: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
