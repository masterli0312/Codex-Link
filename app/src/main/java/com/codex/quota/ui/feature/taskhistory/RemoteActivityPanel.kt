package com.codex.quota.ui.feature.taskhistory

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.codex.quota.R
import com.codex.quota.notifications.task.RemoteActivity

/** Public progress uses a single quiet label; paths, commands and output stay out of the transcript. */
@Composable
internal fun RemoteActivityInline(activity: RemoteActivity) {
    val label = stringResource(when (activity.type) {
        "commandExecution" -> R.string.remote_activity_command
        "fileChange" -> R.string.remote_activity_files
        "fileRead" -> R.string.remote_activity_read
        "mcpToolCall" -> R.string.remote_activity_tool
        "plan" -> R.string.remote_activity_plan
        "compaction" -> R.string.remote_activity_compaction
        else -> R.string.remote_activity_diff
    })
    val status = stringResource(when (activity.status) {
        "inProgress" -> R.string.remote_activity_running
        "failed" -> R.string.remote_activity_failed
        "declined" -> R.string.remote_activity_denied
        else -> R.string.remote_activity_completed
    })
    val color = if (MaterialTheme.colorScheme.onSurface.luminance() > 0.5f) Color(0xFFAAAAAA) else Color(0xFF969696)
    Row(Modifier.fillMaxWidth().semantics { stateDescription = status }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(when (activity.type) {
            "commandExecution" -> Icons.Outlined.Terminal
            "fileChange" -> Icons.Outlined.Edit
            "mcpToolCall" -> Icons.Outlined.Extension
            else -> Icons.Outlined.Description
        }, null, Modifier.size(18.dp), tint = color)
        Text(label, style = MaterialTheme.typography.bodyMedium, color = color)
    }
}

@Composable
internal fun RemoteActivityGroupInline(activities: List<RemoteActivity>) {
    activities.firstOrNull()?.let { RemoteActivityInline(it) }
}
