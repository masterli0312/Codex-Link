package com.codex.quota.ui.feature.taskhistory

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.codex.quota.R

/** A title action belongs to the conversation page, rather than the search action's anchor. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ConversationMenuSheet(
    showArchived: Boolean, canSync: Boolean,
    onDismiss: () -> Unit, onSync: () -> Unit, onArchive: () -> Unit,
    onSettings: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth().padding(start = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.conversation_codex), style = MaterialTheme.typography.headlineSmall)
                    Text(stringResource(R.string.conversation_menu_subtitle), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, stringResource(R.string.conversation_menu_close)) }
            }
            MenuAction(Icons.Outlined.Sync, stringResource(R.string.conversation_sync), canSync, onClick = onSync)
            MenuAction(if (showArchived) Icons.AutoMirrored.Outlined.Chat else Icons.Outlined.Archive,
                stringResource(if (showArchived) R.string.conversation_recent else R.string.conversation_archived), canSync, onClick = onArchive)
            HorizontalDivider(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
            MenuAction(Icons.Outlined.Settings, stringResource(R.string.conversation_connection_manage), onClick = onSettings)
        }
    }
}

@Composable
private fun MenuAction(icon: ImageVector, title: String, enabled: Boolean = true, destructive: Boolean = false, onClick: () -> Unit) {
    val foreground = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    Surface(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
        color = if (destructive) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f) else MaterialTheme.colorScheme.surface,
        contentColor = if (enabled) foreground else foreground.copy(alpha = 0.38f)) {
        Row(Modifier.heightIn(min = 56.dp).padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
            Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            if (!destructive) Icon(Icons.Outlined.ChevronRight, contentDescription = null, modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 0.7f else 0.3f))
        }
    }
}
