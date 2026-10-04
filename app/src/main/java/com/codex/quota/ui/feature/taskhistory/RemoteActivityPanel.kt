package com.codex.quota.ui.feature.taskhistory

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.codex.quota.R
import com.codex.quota.notifications.task.RemoteActivity
import com.codex.quota.notifications.task.ConversationActivityGroups

@Composable
internal fun RemoteActivityInline(activity: RemoteActivity) {
    var expanded by remember(activity.id) { mutableStateOf(false) }
    ActivityRow(activity) { expanded = true }
    if (expanded) ActivityDetails(activity) { expanded = false }
}

@Composable
internal fun RemoteActivityGroupInline(activities: List<RemoteActivity>) {
    val first = activities.first()
    var expanded by remember(first.id) { mutableStateOf(false) }
    var selectedId by remember(first.id) { mutableStateOf<String?>(null) }
    val status = when {
        activities.any { it.status == "inProgress" } -> "inProgress"
        activities.any { it.status == "failed" } -> "failed"
        activities.any { it.status == "declined" } -> "declined"
        else -> "completed"
    }
    val count = if (first.type == "mcpToolCall") pluralStringResource(R.plurals.remote_activity_call_count, activities.size, activities.size)
        else pluralStringResource(R.plurals.remote_activity_item_count, activities.size, activities.size)
    Column {
        ActivityRow(first.copy(status = status), label = activityLabel(first) + " · " + count,
            expanded = expanded) { expanded = !expanded }
        if (expanded) Column(Modifier.padding(start = 24.dp)) {
            activities.forEachIndexed { index, activity ->
                key(activity.id) {
                    ActivityRow(activity, label = stringResource(R.string.remote_activity_child,
                        activityStatus(activity), activityLabel(activity), index + 1)) { selectedId = activity.id }
                }
            }
        }
    }
    activities.firstOrNull { it.id == selectedId }?.let { ActivityDetails(it) { selectedId = null } }
}

@Composable
private fun activityName(a: RemoteActivity): String = stringResource(when (a.type) {
    "commandExecution" -> R.string.remote_activity_command
    "fileChange" -> R.string.remote_activity_files
    "mcpToolCall" -> R.string.remote_activity_tool
    "plan" -> R.string.remote_activity_plan
    "compaction" -> R.string.remote_activity_compaction
    else -> R.string.remote_activity_diff
})

@Composable
private fun activityStatus(a: RemoteActivity): String = stringResource(when (a.status) {
        "inProgress" -> R.string.remote_activity_running
        "failed" -> R.string.remote_activity_failed
        "declined" -> R.string.remote_activity_denied
        else -> R.string.remote_activity_completed
    })

@Composable
private fun activityLabel(a: RemoteActivity): String =
    if (a.type == "mcpToolCall") ConversationActivityGroups.tool(a)?.display ?: activityName(a) else activityName(a)

@Composable
private fun ActivityRow(a: RemoteActivity, label: String = activityLabel(a), expanded: Boolean = false, onClick: () -> Unit) {
    val status = activityStatus(a)
    val color = if (a.status in setOf("failed", "declined")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    Row(Modifier.fillMaxWidth().heightIn(min = 40.dp).clickable(onClick = onClick)
        .semantics { stateDescription = status }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(when (a.type) { "commandExecution" -> Icons.Outlined.Terminal; "mcpToolCall" -> Icons.Outlined.Extension; else -> Icons.Outlined.Description }, null, Modifier.size(16.dp), tint = color)
        Text(label, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = color)
        if (a.status == "inProgress") CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp, color = color)
        else if (a.status in setOf("failed", "declined")) Text(status, style = MaterialTheme.typography.labelSmall, color = color)
        Icon(if (expanded) Icons.Outlined.ExpandMore else Icons.Outlined.ChevronRight, null, Modifier.size(14.dp), tint = color)
    }
}

@Composable
private fun ActivityDetails(a: RemoteActivity, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val text = remember(a) { (listOf(a.title, a.detail) + a.files.map { it.path + "\n" + it.diff }).filter { it.isNotBlank() }.joinToString("\n\n") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(activityName(a)) }, text = {
        Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            a.exit_code?.let { Text(stringResource(R.string.remote_activity_exit_code, it), style = MaterialTheme.typography.labelMedium) }
            a.duration_ms?.let { Text(stringResource(R.string.remote_activity_duration, it), style = MaterialTheme.typography.labelMedium) }
            if (a.truncated) Text(stringResource(R.string.remote_activity_truncated), style = MaterialTheme.typography.bodySmall)
            if (text.isNotBlank()) SelectionContainer { Text(text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
            else Text(stringResource(R.string.remote_activity_no_output), style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_got_it)) } },
        dismissButton = { if (text.isNotBlank()) IconButton(onClick = {
            val clip = ClipData.newPlainText(context.getString(R.string.remote_activity_title), text)
            if (Build.VERSION.SDK_INT >= 33) clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
        }) { Icon(Icons.Outlined.ContentCopy, stringResource(R.string.conversation_copy), Modifier.size(18.dp)) } })
}
