package com.codex.quota.ui.feature.taskhistory

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.codex.quota.R
import com.codex.quota.notifications.task.*

/** The selected device owns the project catalog; names and paths come from that device. */
@Composable
internal fun ConversationEnvironmentSelector(computers: List<ComputerConnection>, computer: ComputerConnection?,
    hostName: String, projects: List<RemoteThreadSummary>, project: String, enabled: Boolean,
    now: Long, onComputer: (ComputerConnection) -> Unit, onProject: (String) -> Unit, onCloud: () -> Unit) {
    var computersOpen by remember { mutableStateOf(false) }
    var projectsOpen by remember(computer?.id) { mutableStateOf(false) }
    val computerName = computer?.name?.ifBlank { hostName }?.ifBlank { stringResource(R.string.conversation_paired_computer) }
        ?: stringResource(R.string.conversation_select_computer)
    Column(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Box {
            TextButton(onClick = { computersOpen = true }, enabled = enabled,
                modifier = Modifier.testTag("new-conversation-computer"), contentPadding = PaddingValues(horizontal = 12.dp)) {
                Icon(Icons.Outlined.Computer, null, Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text(computerName, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 240.dp),
                    color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyLarge)
                Icon(Icons.Outlined.KeyboardArrowDown, null, Modifier.padding(start = 8.dp).size(18.dp))
            }
            DropdownMenu(computersOpen, { computersOpen = false }, shape = RoundedCornerShape(24.dp)) {
                DropdownMenuItem(text = { Text(stringResource(R.string.conversation_cloud)) },
                    leadingIcon = { Icon(Icons.Outlined.CloudQueue, null) }, onClick = { computersOpen = false; onCloud() })
                if (computers.isNotEmpty()) HorizontalDivider(Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                computers.forEach { entry ->
                    DropdownMenuItem(text = { Column {
                        Text(entry.name.ifBlank { if (entry.id == computer?.id) hostName else "" }
                            .ifBlank { stringResource(R.string.conversation_paired_computer) }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(stringResource(if (entry.recentlyReachable(now)) R.string.conversation_environment_online else R.string.conversation_environment_offline),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } }, leadingIcon = { Icon(Icons.Outlined.Computer, null) },
                        trailingIcon = { if (entry.id == computer?.id) Icon(Icons.Outlined.Check, null, tint = MaterialTheme.colorScheme.primary) },
                        onClick = { onComputer(entry); computersOpen = false })
                }
            }
        }
        TextButton(onClick = { projectsOpen = true }, enabled = enabled && computer != null,
            modifier = Modifier.testTag("new-conversation-project"), contentPadding = PaddingValues(horizontal = 12.dp)) {
            Icon(if (project == ConversationEnvironmentRules.NO_PROJECT) Icons.Outlined.Computer else Icons.Outlined.FolderOpen,
                null, Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(projects.firstOrNull { it.project_id == project }?.project_name ?: stringResource(R.string.conversation_no_project),
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 240.dp),
                color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyLarge)
            Icon(Icons.Outlined.KeyboardArrowDown, null, Modifier.padding(start = 8.dp).size(18.dp))
        }
    }
    if (projectsOpen) AlertDialog(onDismissRequest = { projectsOpen = false }, shape = RoundedCornerShape(28.dp),
        containerColor = MaterialTheme.colorScheme.surface, title = { Text(stringResource(R.string.conversation_select_project)) },
        text = { Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()).selectableGroup()) {
            ProjectChoice(stringResource(R.string.conversation_no_project), "", project == ConversationEnvironmentRules.NO_PROJECT,
                noProject = true) { onProject(ConversationEnvironmentRules.NO_PROJECT); projectsOpen = false }
            if (projects.isNotEmpty()) {
                Text(stringResource(R.string.conversation_recent_projects), Modifier.padding(top = 20.dp, bottom = 8.dp),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                projects.forEach { entry -> key(entry.project_id) {
                    ProjectChoice(entry.project_name, entry.project_path, project == entry.project_id) {
                        onProject(entry.project_id); projectsOpen = false
                    }
                } }
            }
        } }, confirmButton = { TextButton(onClick = { projectsOpen = false }) { Text(stringResource(R.string.action_cancel)) } })
}

@Composable
private fun ProjectChoice(name: String, path: String, selected: Boolean, noProject: Boolean = false, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().selectable(selected, role = Role.RadioButton, onClick = onClick).heightIn(min = 64.dp).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(if (noProject) Icons.Outlined.Computer else Icons.Outlined.FolderOpen, null, Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f)) {
            Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
            if (path.isNotBlank()) Text(path, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (selected) Icon(Icons.Outlined.Check, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
    }
}
