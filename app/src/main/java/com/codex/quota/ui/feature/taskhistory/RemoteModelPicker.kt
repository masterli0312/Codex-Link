package com.codex.quota.ui.feature.taskhistory

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Check
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.codex.quota.R
import com.codex.quota.notifications.task.RemoteModelOption

@Composable
internal fun RemoteModelPicker(models: List<RemoteModelOption>, selected: String,
    loading: Boolean, failed: Boolean, onRefresh: () -> Unit, onSelect: (String) -> Unit, onDismiss: () -> Unit) {
    OptionSheet(stringResource(R.string.remote_model), onDismiss, footer = {
        if (failed || models.isEmpty()) TextButton(onClick = onRefresh, enabled = !loading,
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)) { Text(stringResource(R.string.remote_refresh_models)) }
    }) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (!loading && models.isEmpty()) Text(stringResource(R.string.remote_session_unknown),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            LazyColumn(Modifier.heightIn(max = optionListHeight()).selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(models, key = { it.model }) { model -> ModelChoice(model.name, selected == model.model) { onSelect(model.model) } }
            }
        }
    }
}

@Composable
internal fun RemoteEffortPicker(efforts: List<String>, selected: String, onSelect: (String) -> Unit, onDismiss: () -> Unit) {
    OptionSheet(stringResource(R.string.remote_effort), onDismiss) {
        LazyColumn(Modifier.heightIn(max = optionListHeight()).selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (efforts.isEmpty()) item { Text(stringResource(R.string.remote_effort_not_available), style = MaterialTheme.typography.bodySmall) }
            items(efforts.distinct().sortedBy { listOf("none", "minimal", "low", "medium", "high", "xhigh", "max", "ultra").indexOf(it).let { index -> if (index < 0) 100 else index } }) { effort -> ModelChoice(effortLabel(effort), selected == effort) { onSelect(effort) } }
        }
    }
}

@Composable
private fun ModelChoice(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLowest,
        contentColor = MaterialTheme.colorScheme.onSurface) {
        Row(Modifier.fillMaxWidth().selectable(selected, role = Role.RadioButton, onClick = onClick)
            .heightIn(min = 52.dp).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
            if (selected) Icon(Icons.Outlined.Check, null, Modifier.size(22.dp))
            else Spacer(Modifier.size(22.dp))
        }
    }
}

@Composable
internal fun RemoteModePicker(modes: List<String>, selected: String, loading: Boolean,
    onRefresh: () -> Unit, onSelect: (String) -> Unit, onDismiss: () -> Unit) {
    OptionSheet(stringResource(R.string.remote_mode), onDismiss, footer = {
        TextButton(onClick = onRefresh, enabled = !loading) { Text(stringResource(R.string.remote_refresh_models)) }
    }) {
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.remote_mode_description), style = MaterialTheme.typography.bodySmall)
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            else if (modes.isEmpty()) Text(stringResource(R.string.remote_mode_unavailable), style = MaterialTheme.typography.bodySmall)
            modes.forEach { mode -> ModelChoice(modeLabel(mode), selected == mode) { onSelect(mode) } }
        }
    }
}

@Composable
private fun optionListHeight() = (LocalConfiguration.current.screenHeightDp * 0.5f).dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OptionSheet(title: String, onDismiss: () -> Unit, footer: @Composable RowScope.() -> Unit = {}, content: @Composable () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest) {
        Column(Modifier.fillMaxWidth().heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.85f).dp)
            .padding(horizontal = 20.dp).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleLarge)
                IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, stringResource(R.string.conversation_menu_close)) }
            }
            Column(Modifier.weight(1f, fill = false)) { content() }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                footer()

            }
        }
    }
}

@Composable
internal fun modeLabel(mode: String) = stringResource(when (mode) {
    "plan" -> R.string.remote_mode_plan
    "default" -> R.string.remote_mode_default
    else -> R.string.remote_mode
})

@Composable
internal fun effortLabel(value: String): String = when (value) {
    "none" -> stringResource(R.string.effort_none)
    "minimal" -> stringResource(R.string.effort_minimal)
    "low" -> stringResource(R.string.effort_low)
    "medium" -> stringResource(R.string.effort_medium)
    "high" -> stringResource(R.string.effort_high)
    "xhigh" -> stringResource(R.string.effort_xhigh)
    "max" -> stringResource(R.string.effort_max)
    "ultra" -> stringResource(R.string.effort_ultra)
    else -> value
}
