package com.codex.quota.ui.feature.taskhistory

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.codex.quota.R

@Composable
internal fun QueuedMessageChip(text: String, enabled: Boolean, canSteer: Boolean,
    onEdit: () -> Unit, onSteer: () -> Unit, onCancel: () -> Unit) {
    var menu by remember(text) { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(),horizontalArrangement = Arrangement.End) {
        Surface(shape = RoundedCornerShape(24.dp),color = MaterialTheme.colorScheme.surfaceContainerLowest,
            border = BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant),modifier = Modifier.widthIn(max = 340.dp)) {
            Row(Modifier.padding(start = 14.dp),verticalAlignment = Alignment.CenterVertically,horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Outlined.PlaylistPlay,stringResource(R.string.remote_queued),Modifier.size(18.dp),tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(text,Modifier.weight(1f,fill = false),maxLines = 1,overflow = TextOverflow.Ellipsis,style = MaterialTheme.typography.bodyMedium)
                Box {
                    IconButton(onClick = { menu = true },enabled = enabled) { Icon(Icons.Outlined.MoreHoriz,stringResource(R.string.conversation_actions),Modifier.size(20.dp)) }
                    DropdownMenu(menu,{ menu = false },shape = RoundedCornerShape(24.dp),containerColor = MaterialTheme.colorScheme.surfaceContainerLowest) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.conversation_edit_queued)) },leadingIcon = { Icon(Icons.Outlined.Edit,null) },onClick = { menu = false; onEdit() })
                        DropdownMenuItem(text = { Text(stringResource(R.string.conversation_steer_queued)) },leadingIcon = { Icon(Icons.Outlined.SubdirectoryArrowRight,null) },enabled = canSteer,onClick = { menu = false; onSteer() })
                        DropdownMenuItem(text = { Text(stringResource(R.string.conversation_cancel_queued),color = MaterialTheme.colorScheme.error) },
                            leadingIcon = { Icon(Icons.Outlined.DeleteOutline,null,tint = MaterialTheme.colorScheme.error) },onClick = { menu = false; onCancel() })
                    }
                }
            }
        }
    }
}
