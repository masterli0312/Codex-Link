package com.codex.quota.ui.feature.taskhistory

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.codex.quota.R
import com.codex.quota.notifications.task.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.text.NumberFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RemoteSessionSheet(id: String, record: TaskInboxRecord, computer: ComputerConnection?, now: Long, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val client = remember { RemoteConversationClient(context) }
    val scope = rememberCoroutineScope()
    var failure by remember { mutableStateOf(false) }
    var timedOut by remember(record.sessionRequest?.id) { mutableStateOf(false) }
    fun read(force: Boolean = false) { scope.launch {
        failure = false
        try { client.session(id, force = force) } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { failure = true }
    } }
    LaunchedEffect(id) { if (RemoteSessionCache.shouldRefresh(record, now)) read() }
    LaunchedEffect(record.sessionRequest?.id) { record.sessionRequest?.let {
        kotlinx.coroutines.delay((it.issued_at + 40_000 - System.currentTimeMillis()).coerceAtLeast(0)); timedOut = true
    } }
    val latest = record.sessionResult?.takeIf { it.request_id == record.sessionRequest?.id && !it.attachment_pending }
    val result = RemoteSessionCache.display(record)
    val pending = result == null && latest == null && !timedOut && !failure
    val online = computer?.recentlyReachable(now) == true
    val locale = context.resources.configuration.locales[0]
    val unit = stringResource(R.string.remote_session_count_unit)
    val belowUnit = stringResource(R.string.remote_session_below_unit)
    val resetDatePattern = stringResource(R.string.remote_session_date_pattern)
    val failed = failure || timedOut && latest == null || latest?.error?.isNotBlank() == true
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
      Surface(Modifier.padding(horizontal = 24.dp).widthIn(max = 420.dp).fillMaxWidth(),
          shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 6.dp) {
        Column(Modifier.fillMaxWidth().heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.8f).dp)
            .verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.remote_session_title), Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (pending) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            }
            Text(stringResource(if (online) R.string.computer_connected else if (computer?.checking == true)
                R.string.computer_checking else R.string.computer_disconnected), style = MaterialTheme.typography.titleLarge)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            val usage = result?.context_usage
            SessionValue(stringResource(R.string.remote_session_context), usage?.let {
                stringResource(R.string.remote_session_context_value, ((1.0 - it.used_tokens.toDouble() / it.window_tokens) * 100).coerceIn(0.0, 100.0).toInt(),
                    RemoteStatusFormat.compactCount(it.used_tokens, locale, unit, belowUnit),
                    RemoteStatusFormat.compactCount(it.window_tokens, locale, unit, belowUnit))
            } ?: stringResource(R.string.remote_session_unknown))
            listOf(R.string.remote_session_five_hour to result?.five_hour, R.string.remote_session_weekly to result?.weekly).forEach { (label, window) ->
                SessionValue(stringResource(label), window?.let {
                    val remaining = NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = 1 }.format(100 - it.used_percent)
                    it.reset_at?.takeIf { reset -> reset > 0 }?.let { reset ->
                        val pattern = if (label == R.string.remote_session_five_hour) "HH:mm" else resetDatePattern
                        stringResource(R.string.remote_session_window_value, remaining, SimpleDateFormat(pattern, locale).format(Date(reset * 1000)))
                    } ?: stringResource(R.string.remote_session_remaining, remaining)
                } ?: stringResource(R.string.remote_session_unknown))
            }
            if (failed) TextButton(onClick = { read(force = true) }, modifier = Modifier.align(Alignment.End)) {
                Text(stringResource(R.string.conversation_refresh))
            }
        }
      }
    }
}
@Composable
private fun SessionValue(title: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RemotePermissionSheet(selected: String, enabled: Boolean, onSelect: (String) -> Unit, onDismiss: () -> Unit) {
    var fullConfirm by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Text(stringResource(R.string.remote_permission_title), Modifier.padding(horizontal = 12.dp, vertical = 8.dp), style = MaterialTheme.typography.titleLarge)
            RemoteSessionRules.permissions.forEach { permission ->
                ListItem(headlineContent = { Text(stringResource(permissionTitle(permission))) }, supportingContent = { Text(stringResource(permissionDescription(permission))) },
                    leadingContent = { Icon(permissionIcon(permission), null, Modifier.size(22.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    trailingContent = { if (selected == permission) Icon(Icons.Outlined.Check, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurface) },
                    modifier = Modifier.fillMaxWidth().then(if (enabled) Modifier.clickable { if (permission == "full") fullConfirm = true else onSelect(permission) } else Modifier),
                    colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent))
            }
            Text(stringResource(R.string.remote_permission_next_turn), Modifier.padding(horizontal = 12.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (fullConfirm) AlertDialog(onDismissRequest = { fullConfirm = false }, title = { Text(stringResource(R.string.remote_permission_full)) },
        text = { Text(stringResource(R.string.remote_permission_full_confirm)) },
        confirmButton = { TextButton(onClick = { fullConfirm = false; onSelect("full") }, enabled = enabled) { Text(stringResource(R.string.remote_goal_confirm)) } },
        dismissButton = { TextButton(onClick = { fullConfirm = false }) { Text(stringResource(R.string.action_cancel)) } })
}
internal fun permissionTitle(permission: String): Int = when (permission) {
    "review" -> R.string.remote_permission_review
    "readonly" -> R.string.remote_permission_readonly
    "full" -> R.string.remote_permission_full
    else -> R.string.remote_permission_default
}
private fun permissionDescription(permission: String): Int = when (permission) {
    "review" -> R.string.remote_permission_review_desc
    "readonly" -> R.string.remote_permission_readonly_desc
    "full" -> R.string.remote_permission_full_desc
    else -> R.string.remote_permission_default_desc
}
internal fun permissionIcon(permission: String) = when (permission) {
    "review" -> ComposerIcons.Review
    "readonly" -> ComposerIcons.Lock
    "full" -> ComposerIcons.Key
    else -> ComposerIcons.Sliders
}
