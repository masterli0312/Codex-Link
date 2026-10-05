package com.codex.quota.ui.feature.taskhistory

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.codex.quota.R
import com.codex.quota.notifications.task.*
import kotlinx.coroutines.delay

/** Plain rendering: the parent owns authenticated catalog reads and durable selection writes. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ConversationSyncPicker(scopeId: String, computerName: String, selection: ConversationSyncSelection,
    catalog: List<RemoteThreadSummary>, loading: Boolean, saving: Boolean, error: Boolean, more: Boolean,
    onSearch: (String) -> Unit, onMore: () -> Unit, onSave: (Set<String>, List<RemoteThreadSummary>) -> Unit, onBack: () -> Unit) {
    var chosen by rememberSaveable(scopeId) { mutableStateOf(selection.ids.toList()) }
    var search by rememberSaveable(scopeId) { mutableStateOf("") }
    // Retain metadata from every visited page/search until Save; selected IDs outside the current page stay selected.
    var known by remember(scopeId) { mutableStateOf(selection.conversations.map { it.thread }.associateBy { it.thread_id }) }
    LaunchedEffect(catalog) { known = known + catalog.associateBy { it.thread_id } }
    LaunchedEffect(search, scopeId) { delay(300); onSearch(search) }
    BackHandler(!saving) { onBack() }
    val rows = (selection.conversations.map { it.thread } + catalog).associateBy { it.thread_id }.values
        .filter { search.isBlank() || it.title.contains(search, true) }.sortedByDescending { it.updated_at }
    fun toggle(id: String) { chosen = if (id in chosen) chosen - id else chosen + id }
    Scaffold(topBar = { CenterAlignedTopAppBar(title = { Text(stringResource(R.string.conversation_sync)) },
        navigationIcon = { IconButton(onClick = onBack, enabled = !saving) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back)) } },
        actions = { TextButton(onClick = { onSave(chosen.toSet(), (known + catalog.associateBy { it.thread_id }).values.toList()) }, enabled = !saving) {
            Text(stringResource(R.string.action_save))
        } }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Text(computerName, Modifier.padding(horizontal = 20.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(search, { search = it }, singleLine = true, enabled = !saving,
                leadingIcon = { Icon(Icons.Outlined.Search, null) }, placeholder = { Text(stringResource(R.string.conversation_search)) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp), shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.conversation_sync_selected, chosen.size), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                TextButton(onClick = { chosen = emptyList() }, enabled = chosen.isNotEmpty() && !saving) { Text(stringResource(R.string.conversation_sync_clear)) }
            }
            if (error) Text(stringResource(R.string.conversation_sync_save_failed), Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.error)
            if (loading) Text(stringResource(R.string.conversation_sync_loading), Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
                items(rows, key = { it.thread_id }) { thread ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(enabled = !saving) { toggle(thread.thread_id) }
                        .padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).padding(end = 12.dp)) {
                            Text(thread.title.ifBlank { stringResource(R.string.conversation_sync_untitled) }, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyLarge)
                            if (thread.project_name.isNotBlank()) Text(thread.project_name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Checkbox(thread.thread_id in chosen, onCheckedChange = { toggle(thread.thread_id) }, enabled = !saving,
                            colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.onSurface))
                    }
                }
                if (rows.isEmpty() && !loading) item { Text(stringResource(R.string.conversation_no_results), Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (more) item { TextButton(onClick = onMore, enabled = !loading && !saving, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.conversation_load_more)) } }
            }
        }
    }
}
