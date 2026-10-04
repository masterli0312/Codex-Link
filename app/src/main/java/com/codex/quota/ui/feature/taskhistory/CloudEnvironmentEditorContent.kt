package com.codex.quota.ui.feature.taskhistory

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.codex.quota.R
import com.codex.quota.notifications.task.TaskConversationMessage
import com.codex.quota.notifications.task.RemoteModelOption
import com.codex.quota.notifications.task.ConversationTimelineEntry
import com.codex.quota.data.cloud.DurableCloudWire

/** Native configuration and preparation. Browser authorization is not presented as environment creation. */
@Composable
internal fun CloudEnvironmentEditorContent(e: CloudEditorState, busy: Boolean, onName: (String)->Unit,
    onRepo: (String)->Unit, onInstall: (String)->Unit, onSkill: (String)->Unit, onNetwork: (String)->Unit,
    onCreate: ()->Unit, onPrepare: ()->Unit, onSave: ()->Unit, onPublish: ()->Unit,
    onAnswer: (String)->Unit, onSendAnswer: ()->Unit, onUse: ()->Unit,
    onModel: (String)->Unit, onEffort: (String)->Unit, onRefreshModels: ()->Unit, modifier: Modifier = Modifier,
    queued: com.codex.quota.data.cloud.CloudPendingReply? = null,onQueuedEdit: ()->Unit = {},onQueuedSteer: ()->Unit = {},onQueuedCancel: ()->Unit = {},onStop: ()->Unit = {}) {
    var advanced by rememberSaveable(e.config?.id,e.creating) { mutableStateOf(false) }
    var confirmPreparation by rememberSaveable(e.config?.id,e.creating) { mutableStateOf(false) }
    var modelPicker by remember { mutableStateOf(false) }
    var effortPicker by remember { mutableStateOf(false) }
    val conversationScroll = rememberCloudConversationScroll(e.session?.threadId.orEmpty(),e.details != null,e.details?.messages?.lastOrNull { it.role == "user" }?.id.orEmpty())
    val selectedModel = e.models.firstOrNull { it.model == e.choice.model }
    val ready = DurableCloudWire.validChoice(e.choice,e.models)
    if(confirmPreparation) AlertDialog(onDismissRequest = { if(!busy) confirmPreparation = false },
        title = { Text(stringResource(R.string.cloud_native_prepare_options)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.cloud_native_prepare_confirm_hint),style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = { modelPicker = true },enabled = !busy && e.models.isNotEmpty(),modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.remote_model),Modifier.weight(1f))
                Text(selectedModel?.name ?: stringResource(R.string.cloud_native_models_missing)); Icon(Icons.Outlined.KeyboardArrowDown,null)
            }
            OutlinedButton(onClick = { effortPicker = true },enabled = !busy && selectedModel != null,modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.remote_effort),Modifier.weight(1f)); Text(effortLabel(e.choice.effort)); Icon(Icons.Outlined.KeyboardArrowDown,null)
            }
            if(!ready) TextButton(onClick = onRefreshModels,enabled = !busy) { Text(stringResource(R.string.remote_refresh_models)) }
        } },
        confirmButton = { TextButton(onClick = { confirmPreparation = false; if(e.creating) onCreate() else onPrepare() },enabled = !busy && ready) {
            Text(stringResource(R.string.cloud_native_confirm_prepare))
        } },dismissButton = { TextButton(onClick = { confirmPreparation = false }) { Text(stringResource(R.string.action_cancel)) } })
    if(modelPicker) RemoteModelPicker(e.models.map { RemoteModelOption(it.model,it.name,it.efforts,it.defaultEffort) },e.choice.model,
        loading = busy,failed = e.models.isEmpty(),onRefresh = onRefreshModels,onSelect = { onModel(it); modelPicker = false },onDismiss = { modelPicker = false })
    if(effortPicker) RemoteEffortPicker(selectedModel?.efforts.orEmpty(),e.choice.effort,
        onSelect = { onEffort(it); effortPicker = false },onDismiss = { effortPicker = false })
    Column(modifier.fillMaxWidth().imePadding()) {
    LazyColumn(Modifier.weight(1f), state = conversationScroll.state, contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Text(stringResource(if(e.creating) R.string.cloud_native_create else R.string.cloud_native_configuration), style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.cloud_native_simple_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if(e.creating) {
            item { OutlinedTextField(e.name,onName,label = { Text(stringResource(R.string.cloud_native_name)) },singleLine = true,enabled = !busy,modifier = Modifier.fillMaxWidth(),shape = RoundedCornerShape(16.dp)) }
            item { Text(stringResource(R.string.cloud_native_repositories),style = MaterialTheme.typography.titleSmall) }
            if(e.repositories.isEmpty()) item { Text(stringResource(R.string.cloud_native_repositories_empty),color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(e.repositories,key = { it.id }) { r ->
                Surface(onClick = { onRepo(r.id) },enabled = !busy,shape = RoundedCornerShape(16.dp),color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Row(Modifier.fillMaxWidth().padding(14.dp),verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.FolderOpen,null,Modifier.size(22.dp)); Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) { Text(r.name,style = MaterialTheme.typography.bodyLarge); Text(r.branch,style = MaterialTheme.typography.bodySmall,color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        Checkbox(r.id in e.selectedRepos,null)
                    }
                }
            }
            item { Button(onClick = { confirmPreparation = true },enabled = !busy && e.name.isNotBlank() && e.selectedRepos.isNotEmpty(),modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.cloud_native_create_prepare))
            } }
        } else if(e.config != null) {
            item {
                Surface(shape = RoundedCornerShape(20.dp),color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.fillMaxWidth().padding(18.dp),verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.CloudQueue,null,Modifier.size(22.dp)); Spacer(Modifier.width(10.dp)); Text(e.config.name,style = MaterialTheme.typography.titleMedium)
                        }
                        Text(stringResource(when(e.session?.stage) {
                            "published" -> R.string.cloud_native_published
                            "publishing", "completing", "beginPending" -> R.string.cloud_native_publishing
                            "allocating", "setupPending" -> if(busy) R.string.cloud_native_preparing else R.string.cloud_native_unconfirmed
                            else -> if(e.details?.task?.status == "running") R.string.cloud_native_preparing else R.string.cloud_native_draft
                        }),color = MaterialTheme.colorScheme.onSurfaceVariant,style = MaterialTheme.typography.bodyMedium)
                        e.config.repositories.forEachIndexed { index,ref -> Text("${e.repositories.firstOrNull { it.id == ref.repository_id }?.name ?: stringResource(R.string.cloud_native_repository_index,index+1)} · ${ref.ref}",style = MaterialTheme.typography.bodySmall,color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
            item {
                if(e.config.published && e.session?.stage == "published") {
                    Button(onClick = onUse,enabled = !busy,modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.cloud_native_use)) }
                } else {
                    FilledTonalButton(onClick = { confirmPreparation = true },enabled = !busy && !e.dirty && e.details?.task?.status != "running",modifier = Modifier.fillMaxWidth()) {
                        if(e.details?.task?.status == "running") { Icon(Icons.Outlined.HourglassEmpty,null,Modifier.size(16.dp)); Spacer(Modifier.width(8.dp)) }
                        Text(stringResource(if(e.details?.task?.status == "running") R.string.cloud_native_preparing else R.string.cloud_native_auto_prepare))
                    }
                    Button(onClick = onPublish,enabled = !busy && !e.dirty && e.details?.task?.status != "running" &&
                        e.session?.stage != "published" && (e.config.draft != null || e.session?.operationId?.isNotBlank() == true),modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(if(e.session?.operationId?.isNotBlank() == true && e.session.stage != "published") R.string.cloud_native_resume_publish else R.string.cloud_native_publish))
                    }
                }
                TextButton(onClick = { advanced = !advanced },modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.cloud_native_advanced)); Icon(if(advanced) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,null,Modifier.size(18.dp))
                }
            }
            if(advanced) {
                item { OutlinedTextField(e.install,onInstall,label = { Text(stringResource(R.string.cloud_native_install)) },enabled = !busy,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp),minLines = 2,maxLines = 8,shape = RoundedCornerShape(16.dp)) }
                item { OutlinedTextField(e.skill,onSkill,label = { Text(stringResource(R.string.cloud_native_start)) },enabled = !busy,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp),minLines = 2,maxLines = 8,shape = RoundedCornerShape(16.dp)) }
                item {
                    Text(stringResource(R.string.cloud_native_network),style = MaterialTheme.typography.titleSmall)
                    Row(Modifier.fillMaxWidth(),horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("restricted" to R.string.cloud_native_packages,"unrestricted" to R.string.cloud_native_internet,"disabled" to R.string.cloud_native_offline).forEach { (value,label) ->
                            FilterChip(selected = e.network == value,onClick = { onNetwork(value) },enabled = !busy,label = { Text(stringResource(label)) })
                        }
                    }
                    FilledTonalButton(onClick = onSave,enabled = !busy && e.dirty,modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.cloud_native_save)) }
                }
            }
            e.details?.messages?.filterNot { it.role == "user" && it.text.contains("cloud-environment-onboarding:") }?.let { messages ->
                item { Text(stringResource(R.string.cloud_native_setup_conversation),style = MaterialTheme.typography.titleSmall) }
                items(com.codex.quota.notifications.task.ConversationTimeline.turnGroups(cloudTranscript(e.details.copy(messages = messages))),
                    key = { "setup-turn:" + com.codex.quota.notifications.task.ConversationTimeline.turn(it.first()) }) { entries ->
                    val turn = com.codex.quota.notifications.task.ConversationTimeline.turn(entries.first())
                    val info = e.details.turns.firstOrNull { it.id == turn }
                    ConversationTurnBlock(entries,
                        completed = info?.status?.let { it != "inProgress" } ?: (turn != e.details.latestTurnId || e.details.task.status != "running"),
                        durationMs = info?.durationMs,onBrowseProcess = conversationScroll.browse)
                }
            }
        }
    }
    if(e.session?.threadId?.isNotBlank() == true && !e.creating) Column(Modifier.padding(horizontal = 20.dp,vertical = 12.dp)) {
        queued?.let { QueuedMessageChip(it.text,!busy,e.details?.task?.status == "running",onQueuedEdit,onQueuedSteer,onQueuedCancel); Spacer(Modifier.height(10.dp)) }
        CloudMessageComposer(e.answer,onAnswer,onSendAnswer,
            e.models,e.choice,busy,queued == null && ready && e.details != null && (e.details.task.status != "running" || e.details.activeTurnId.isNotBlank()),
            onModel,onEffort,onRefreshModels,running = e.details?.task?.status == "running",onStop = onStop)
    }
    }
}
