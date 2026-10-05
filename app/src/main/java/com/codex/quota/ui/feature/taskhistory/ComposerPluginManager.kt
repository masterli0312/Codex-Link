package com.codex.quota.ui.feature.taskhistory

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.codex.quota.R
import com.codex.quota.notifications.task.RemoteSkill

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ComposerPluginManager(skills: List<RemoteSkill>, visible: Set<String>, loading: Boolean, failed: Boolean,
    onChange: (Set<String>) -> Unit, onRefresh: () -> Unit, onDismiss: () -> Unit) {
    var search by remember { mutableStateOf("") }
    val matches = remember(skills, search) {
        skills.filter { search.isBlank() || skillDisplayName(it).contains(search, ignoreCase = true) || it.name.contains(search, ignoreCase = true) }
            .sortedBy { skillDisplayName(it).lowercase() }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.composer_manage_plugins), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.composer_plugins_description), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(search, { search = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.composer_search_plugins)) })
            if (loading && skills.isEmpty()) Text(stringResource(R.string.composer_loading_plugins), style = MaterialTheme.typography.bodySmall)
            if (failed) Text(stringResource(R.string.remote_skills_unavailable), color = MaterialTheme.colorScheme.error)
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                items(matches, key = { it.id }) { skill ->
                    val checked = skill.id in visible
                    fun toggle() = onChange(if (checked) visible - skill.id else visible + skill.id)
                    ListItem(headlineContent = { Text(skillDisplayName(skill), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { if (skill.description.isNotBlank()) Text(skill.description, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        trailingContent = { Checkbox(checked, { toggle() }, colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.onSurface,
                            checkmarkColor = MaterialTheme.colorScheme.surface)) }, modifier = Modifier.clickable { toggle() },
                        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent))
                }
            }
            TextButton(onClick = onRefresh, enabled = !loading, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)) {
                Text(stringResource(R.string.conversation_refresh))
            }
        }
    }
}
