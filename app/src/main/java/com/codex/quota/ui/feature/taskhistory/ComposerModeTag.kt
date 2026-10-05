package com.codex.quota.ui.feature.taskhistory

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.codex.quota.R

@Composable
internal fun ComposerModeTag(goal: Boolean, enabled: Boolean, onRemove: () -> Unit) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)) {
        Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(if (goal) ComposerIcons.Goal else ComposerIcons.Plan, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurface)
            Text(stringResource(if (goal) R.string.composer_goal_label else R.string.composer_plan_label), style = MaterialTheme.typography.labelLarge)
            IconButton(onClick = onRemove, enabled = enabled, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Outlined.Close, stringResource(R.string.action_cancel), Modifier.size(16.dp))
            }
        }
    }
}
