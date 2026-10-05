package com.codex.quota.ui.feature.taskhistory

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudQueue
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.codex.quota.R
import com.codex.quota.data.cloud.CloudEnvironment

/** Native environment catalog. Published selection and editable configs are distinct. */
@Composable
internal fun CloudEnvironmentManagementContent(environments: List<CloudEnvironment>, selectedId: String,
    accountName: String, loading: Boolean, hasAccount: Boolean, browserUnavailable: Boolean,
    onSelect: (String) -> Unit, onConfigure: () -> Unit, onRefresh: () -> Unit,
    onEdit: (String) -> Unit = {}, modifier: Modifier = Modifier) {
    LazyColumn(modifier.fillMaxWidth(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.cloud_native_simple_hint), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = onConfigure, enabled = hasAccount,
                        modifier = Modifier.fillMaxWidth().testTag("cloud-configure-official")) {
                        Text(stringResource(R.string.cloud_native_create))
                    }
                    if (browserUnavailable) Text(stringResource(R.string.cloud_browser_unavailable), color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.cloud_available_environments), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                if (loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else IconButton(onClick = onRefresh, enabled = hasAccount, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Outlined.Refresh, stringResource(R.string.cloud_sync_environments), Modifier.size(20.dp))
                }
            }
        }
        if (environments.isEmpty() && !loading) item {
            Text(stringResource(R.string.cloud_no_environments), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            if (environments.isNotEmpty()) com.codex.quota.ui.components.SectionSurface {
                Column(Modifier.fillMaxWidth().selectableGroup()) {
                    environments.forEachIndexed { index, environment ->
                        Row(Modifier.fillMaxWidth().selectable(selectedId == environment.id, role = Role.RadioButton,
                            onClick = { onEdit(environment.id) }).padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            com.codex.quota.ui.components.SectionIcon(Icons.Outlined.CloudQueue)
                            Text(environment.label.ifBlank { stringResource(R.string.cloud_native_configuration) }, Modifier.weight(1f), maxLines = 2,
                                overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                            IconButton(onClick = { onEdit(environment.id) }) { Icon(Icons.Outlined.Tune, stringResource(R.string.cloud_native_configuration), Modifier.size(18.dp)) }
                        }
                        if (index < environments.lastIndex) HorizontalDivider(Modifier.padding(horizontal = 16.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
                    }
                }
            }
        }
    }
}
