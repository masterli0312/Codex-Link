package com.codex.quota.ui.feature.taskhistory

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.codex.quota.R
import com.codex.quota.notifications.task.TaskInboxRecord

/** Only references resolved and revalidated by the paired computer's native skills catalog. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RemoteSkillsSheet(record: TaskInboxRecord, selected: List<String>, enabled: Boolean,
    onSelect: (List<String>) -> Unit, onRefresh: () -> Unit, onDismiss: () -> Unit) {
    val result = record.skillsResult?.takeIf { it.request_id == record.skillsRequest?.id && !it.attachment_pending }
    var timedOut by remember(record.skillsRequest?.id) { mutableStateOf(false) }
    LaunchedEffect(record.skillsRequest?.id) { record.skillsRequest?.let { kotlinx.coroutines.delay((it.issued_at + 40_000 - System.currentTimeMillis()).coerceAtLeast(0)); timedOut = true } }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.8f).dp).padding(bottom = 20.dp)) {
            Text(stringResource(R.string.remote_skills_title), Modifier.padding(horizontal = 24.dp, vertical = 12.dp), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.remote_skills_source), Modifier.padding(horizontal = 24.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (result == null && !timedOut) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 16.dp))
            if (timedOut && result == null || result?.error?.isNotBlank() == true) Text(stringResource(R.string.remote_skills_unavailable), Modifier.padding(24.dp), color = MaterialTheme.colorScheme.error)
            if (result != null && result.error.isBlank() && result.skills.isEmpty()) Text(stringResource(R.string.remote_skills_empty), Modifier.padding(24.dp))
            LazyColumn(Modifier.weight(1f, fill = false)) {
                items(result?.skills.orEmpty().sortedWith(compareByDescending<com.codex.quota.notifications.task.RemoteSkill> { it.plugin }.thenBy { it.name }), key = { it.id }) { skill ->
                    val allowed = enabled && (skill.id in selected || selected.size < 4)
                    fun toggle() { onSelect(if (skill.id in selected) selected - skill.id else selected + skill.id) }
                    ListItem(headlineContent = { Text(skill.name) }, supportingContent = { Text(skill.description, maxLines = 2) },
                        leadingContent = { Icon(Icons.Outlined.Extension, null) }, trailingContent = { Checkbox(skill.id in selected, onCheckedChange = { toggle() }, enabled = allowed) },
                        modifier = Modifier.clickable(enabled = allowed) { toggle() }, colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent))
                }
            }
            TextButton(onClick = onRefresh, modifier = Modifier.padding(horizontal = 16.dp)) { Text(stringResource(R.string.conversation_refresh)) }
        }
    }
}
