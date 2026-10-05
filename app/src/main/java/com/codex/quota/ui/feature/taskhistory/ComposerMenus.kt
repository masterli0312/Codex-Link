package com.codex.quota.ui.feature.taskhistory

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.codex.quota.R
import com.codex.quota.notifications.task.RemoteSkill
import com.codex.quota.notifications.task.RemoteModelOption
import com.codex.quota.notifications.task.ComposerCatalog

/** Both menus use the provider's real choices; labels never change protocol identities. */
@Composable
internal fun ComposerModelMenu(expanded: Boolean, onDismiss: () -> Unit, efforts: List<String>, selected: String,
    modelName: String, onEffort: (String) -> Unit, models: List<RemoteModelOption>, selectedModel: String,
    onModel: (String) -> Unit, loading: Boolean = false, failed: Boolean = false, onRefresh: () -> Unit = {}) {
    var modelPage by remember(expanded) { mutableStateOf(false) }
    var otherModels by remember(expanded) { mutableStateOf(false) }
    DropdownMenu(expanded, onDismiss, shape = RoundedCornerShape(24.dp),
        modifier = Modifier.width(280.dp).heightIn(max = 440.dp), containerColor = MaterialTheme.colorScheme.surfaceContainerLowest) {
        if (modelPage) {
            DropdownMenuItem(text = { Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(stringResource(R.string.remote_model))
                Text(modelName, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } }, trailingIcon = { Icon(Icons.Outlined.KeyboardArrowDown, null) }, onClick = { modelPage = false; otherModels = false })
            HorizontalDivider(Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            val common = ComposerCatalog.commonModels(models, selectedModel)
            val visible = if (otherModels) models else common
            visible.forEach { model ->
                DropdownMenuItem(text = { Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(model.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (model.description.isNotBlank()) Text(model.description, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } }, trailingIcon = { if (selectedModel == model.model) Icon(Icons.Outlined.Check, null, Modifier.size(22.dp)) },
                    colors = MenuDefaults.itemColors(textColor = MaterialTheme.colorScheme.onSurface, trailingIconColor = MaterialTheme.colorScheme.onSurface),
                    onClick = { onModel(model.model); onDismiss() })
            }
            if (!otherModels && common.size < models.size) {
                HorizontalDivider(Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                DropdownMenuItem(text = { Text(stringResource(R.string.composer_other_models)) },
                    trailingIcon = { Icon(Icons.Outlined.ChevronRight, null) }, onClick = { otherModels = true })
            }
            if (models.isEmpty()) Text(stringResource(if (loading) R.string.composer_loading_models else R.string.remote_session_unknown),
                Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (failed || models.isEmpty() && !loading) DropdownMenuItem(text = { Text(stringResource(R.string.remote_refresh_models)) },
                enabled = !loading, onClick = onRefresh)
        } else {
        Text(stringResource(R.string.remote_effort), Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (efforts.isEmpty()) Text(stringResource(R.string.remote_effort_not_available), Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        efforts.distinct().sortedBy { listOf("none", "minimal", "low", "medium", "high", "xhigh", "max", "ultra").indexOf(it).let { n -> if (n < 0) 100 else n } }.forEach { effort ->
            DropdownMenuItem(text = { Text(effortLabel(effort)) },
                trailingIcon = { if (selected == effort) Icon(Icons.Outlined.Check, null, Modifier.size(22.dp)) },
                colors = MenuDefaults.itemColors(textColor = MaterialTheme.colorScheme.onSurface, trailingIconColor = MaterialTheme.colorScheme.onSurface),
                onClick = { onEffort(effort); onDismiss() })
        }
        HorizontalDivider(Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        DropdownMenuItem(text = { Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(stringResource(R.string.remote_model))
            Text(modelName, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        } }, trailingIcon = { Icon(Icons.Outlined.ChevronRight, null) }, onClick = { modelPage = true })
        }
    }
}

internal fun skillDisplayName(skill: RemoteSkill): String {
    if (skill.display_name.isNotBlank()) return skill.display_name
    val name = skill.name.substringAfterLast(':')
    if (name.lowercase() in setOf("pdf", "csv", "json", "api")) return name.uppercase()
    return name.replace('-', ' ').replace('_', ' ').replaceFirstChar { it.titlecase() }
}

@Composable
internal fun ComposerSkillItem(skill: RemoteSkill, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(text = { Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(skillDisplayName(skill), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (skill.description.isNotBlank()) Text(skill.description, maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } }, leadingIcon = { Icon(Icons.Outlined.Extension, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) },
        trailingIcon = { if (selected) Icon(Icons.Outlined.Check, null, Modifier.size(20.dp)) }, enabled = enabled, onClick = onClick)
}
