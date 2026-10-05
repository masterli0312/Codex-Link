package com.codex.quota.ui.feature.taskhistory

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Compress
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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
internal fun RemoteActivityInline(activity: RemoteActivity, recordId: String? = null) {
    var expanded by rememberSaveable(activity.id) { mutableStateOf(false) }
    val label = stringResource(when (activity.type) {
        "imageView" -> R.string.remote_activity_image
        "commandExecution" -> R.string.remote_activity_command
        "fileChange" -> R.string.remote_activity_files
        "fileRead" -> R.string.remote_activity_read
        "mcpToolCall" -> R.string.remote_activity_tool
        "plan" -> R.string.remote_activity_plan
        "compaction" -> if (activity.status == "inProgress") R.string.remote_activity_compacting else R.string.remote_activity_compaction
        else -> R.string.remote_activity_diff
    })
    val status = stringResource(when (activity.status) {
        "inProgress" -> R.string.remote_activity_running
        "failed" -> R.string.remote_activity_failed
        "declined" -> R.string.remote_activity_denied
        else -> R.string.remote_activity_completed
    })
    val color = if (activity.status in setOf("failed", "declined")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    Column {
    Row(Modifier.fillMaxWidth().heightIn(min = if (activity.type == "imageView") 48.dp else 0.dp)
        .clickable(enabled = activity.type == "imageView") { expanded = !expanded }.semantics { stateDescription = status }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(when (activity.type) {
            "imageView" -> Icons.Outlined.Image
            "commandExecution" -> Icons.Outlined.Terminal
            "fileChange" -> Icons.Outlined.Edit
            "mcpToolCall" -> Icons.Outlined.Extension
            "compaction" -> Icons.Outlined.Compress
            else -> Icons.Outlined.Description
        }, null, Modifier.size(18.dp), tint = color)
        Text(label, style = MaterialTheme.typography.bodyMedium, color = color)
        if (activity.type == "imageView") Icon(if (expanded) Icons.Outlined.ExpandMore else Icons.Outlined.ChevronRight, null, Modifier.size(16.dp), tint = color)
    }
    if (expanded) activity.images.forEach { image -> key(image.id) { ConversationImage(image, recordId, compact = true) } }
    }
}

@Composable
internal fun RemoteActivityGroupInline(activities: List<RemoteActivity>, recordId: String? = null) {
    activities.firstOrNull()?.let { RemoteActivityInline(it, recordId) }
}
