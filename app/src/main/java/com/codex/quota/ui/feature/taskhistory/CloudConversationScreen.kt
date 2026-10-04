package com.codex.quota.ui.feature.taskhistory

import android.content.Intent
import android.content.ActivityNotFoundException
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.codex.quota.CodexQuotaApplication
import com.codex.quota.R
import com.codex.quota.data.cloud.*
import com.codex.quota.notifications.task.TaskConversationMessage
import com.codex.quota.ui.util.localizedAccountNickname
import com.codex.quota.ui.util.localizedShortPlanName
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@Composable
internal fun CloudConversationScreen(initialNew: Boolean, onLocal: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as CodexQuotaApplication
    val vm: CloudConversationViewModel = viewModel(key = "official-cloud", factory = object : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            @Suppress("UNCHECKED_CAST") return CloudConversationViewModel(app) as T
        }
    })
    val state by vm.state.collectAsStateWithLifecycle()
    var browserUnavailable by rememberSaveable { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current
    val latest by rememberUpdatedState(state)
    LaunchedEffect(vm, initialNew) { if (initialNew) vm.newTask() }
    LaunchedEffect(vm, lifecycle) {
        lifecycle.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            vm.resumeReads()
            try {
                while (isActive) {
                    delay(if (latest.selectedTask.isNotBlank() || latest.editor?.session?.threadId?.isNotBlank() == true) 2500 else 30_000)
                    if (!latest.sending && (latest.editor?.awaitingTurnId?.isNotBlank() == true || latest.editor?.details?.task?.status == "running" || !latest.managingEnvironments && !latest.newTask &&
                        (latest.selectedTask.isBlank() || latest.cache?.details?.firstOrNull { it.task.id == latest.selectedTask }?.task?.status != "completed"))
                        )
                        vm.refresh(showProgress = false)
                }
            } finally { vm.pauseReads() }
        }
    }
    DisposableEffect(vm) { onDispose { vm.pauseReads() } }
    val back = { if (state.managingEnvironments) vm.closeEnvironments()
        else if (state.newTask || state.selectedTask.isNotBlank()) vm.backToList() else onLocal() }
    BackHandler(onBack = back)
    CloudConversationContent(state, onBack = back, onLocal = onLocal, onAccount = vm::selectAccount,
        onEnvironment = vm::environment, onBranch = vm::branch, onDraft = vm::draft, onSend = vm::send,
        onRefresh = { vm.refresh() }, onNew = vm::newTask, onOpen = vm::open, onMore = vm::more,
        onOfficial = { id ->
            if (CloudWire.validId(id)) context.startActivity(Intent(Intent.ACTION_VIEW,
                Uri.Builder().scheme("https").authority("chatgpt.com").appendPath("codex").appendPath("tasks").appendPath(id).build()))
        }, onSetup = vm::manageEnvironments, onConfigure = vm::configureEnvironment,
        browserUnavailable = browserUnavailable,onEditEnvironment = vm::editEnvironment,
        onEditorName = vm::editorName,onEditorRepo = vm::editorRepo,onEditorInstall = vm::editorInstall,
        onEditorSkill = vm::editorSkill,onEditorNetwork = vm::editorNetwork,onCreateEnvironment = vm::createEnvironment,
        onPrepareEnvironment = vm::prepareEnvironment,onSaveEnvironment = vm::saveEnvironment,onPublishEnvironment = vm::publishEnvironment,
        onSetupAnswer = vm::editorAnswer,onSendSetupAnswer = vm::sendSetupAnswer,onUseEnvironment = vm::useEditorEnvironment,
        onPreparationModel = vm::preparationModel,onPreparationEffort = vm::preparationEffort,onRefreshPreparationModels = vm::refreshPreparationModels,
        onReplyDraft = vm::replyDraft,onSendReply = vm::sendReply)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CloudConversationContent(state: CloudUiState, onBack: () -> Unit, onLocal: () -> Unit,
    onAccount: (String) -> Unit, onEnvironment: (String) -> Unit, onBranch: (String) -> Unit, onDraft: (String) -> Unit,
    onSend: () -> Unit, onRefresh: () -> Unit, onNew: () -> Unit, onOpen: (String) -> Unit, onMore: () -> Unit,
    onOfficial: (String) -> Unit, onSetup: () -> Unit, onConfigure: () -> Unit,
    browserUnavailable: Boolean = false, onEditEnvironment: (String)->Unit = {},
    onEditorName: (String)->Unit = {},onEditorRepo: (String)->Unit = {},onEditorInstall: (String)->Unit = {},
    onEditorSkill: (String)->Unit = {},onEditorNetwork: (String)->Unit = {},onCreateEnvironment: ()->Unit = {},
    onPrepareEnvironment: ()->Unit = {},onSaveEnvironment: ()->Unit = {},onPublishEnvironment: ()->Unit = {},
    onSetupAnswer: (String)->Unit = {},onSendSetupAnswer: ()->Unit = {},onUseEnvironment: ()->Unit = {},
    onPreparationModel: (String)->Unit = {},onPreparationEffort: (String)->Unit = {},onRefreshPreparationModels: ()->Unit = {},
    onReplyDraft: (String)->Unit = {},onSendReply: ()->Unit = {}) {
    val context = LocalContext.current
    var accountsOpen by remember { mutableStateOf(false) }
    var environmentsOpen by remember { mutableStateOf(false) }
    var branchOpen by remember { mutableStateOf(false) }
    var showDiff by remember(state.selectedTask) { mutableStateOf(false) }
    var voiceError by remember { mutableStateOf<Int?>(null) }
    val cache = state.cache
    val details = cache?.details?.firstOrNull { it.task.id == state.selectedTask }
    val task = details?.task ?: cache?.page?.items?.firstOrNull { it.id == state.selectedTask }
    val selectedEnvironment = cache?.environments?.firstOrNull { it.id == cache.selectedEnvironment }
    val account = state.accounts.firstOrNull { it.id == state.accountId }
    Scaffold(containerColor = MaterialTheme.colorScheme.background, topBar = {
        CenterAlignedTopAppBar(title = { Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (state.managingEnvironments) stringResource(R.string.cloud_manage_environments)
                else if (state.selectedTask.isNotBlank()) task?.title?.ifBlank { null } ?: stringResource(R.string.cloud_task)
                else stringResource(R.string.conversation_cloud), maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleMedium)
            if (!state.managingEnvironments && state.selectedTask.isNotBlank()) Text(stringResource(cloudStatus(task?.status)), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        } }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back)) } },
            actions = {
                IconButton(onClick = onRefresh, enabled = !state.loading && !state.sending) {
                    if (state.loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Outlined.Refresh, stringResource(if (state.managingEnvironments) R.string.cloud_sync_environments else R.string.cloud_refresh))
                }
                if (!state.managingEnvironments) IconButton(onClick = onSetup, enabled = !state.sending,
                    modifier = Modifier.testTag("cloud-manage-environments")) { Icon(Icons.Outlined.Tune, stringResource(R.string.cloud_manage_environments)) }
                if (!state.newTask && !state.managingEnvironments) IconButton(onClick = onNew, enabled = !state.sending) { Icon(Icons.Outlined.EditNote, stringResource(R.string.cloud_new_task)) }
            })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onLocal) { Icon(Icons.Outlined.Computer, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.cloud_local)) }
                Spacer(Modifier.weight(1f))
                Box {
                    TextButton(onClick = { accountsOpen = true }, enabled = !state.sending, modifier = Modifier.testTag("cloud-account")) {
                        Text(account?.let { localizedAccountNickname(context, it) } ?: stringResource(R.string.cloud_account), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 180.dp))
                        Icon(Icons.Outlined.KeyboardArrowDown, null, Modifier.size(18.dp))
                    }
                    DropdownMenu(accountsOpen, { accountsOpen = false }) {
                        state.accounts.forEach { entry -> DropdownMenuItem(text = { Column {
                            Text(localizedAccountNickname(context, entry))
                            Text(localizedShortPlanName(context, entry.planType), style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } },
                            trailingIcon = { if (entry.id == state.accountId) Icon(Icons.Outlined.Check, null) },
                            onClick = { accountsOpen = false; onAccount(entry.id) }) }
                    }
                }
            }
            state.error?.let { error -> Text(stringResource(cloudError(error)), Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            if(state.editor != null) {
                CloudEnvironmentEditorContent(state.editor,busy = state.sending || state.loading && state.editor.config == null,
                    onName = onEditorName,onRepo = onEditorRepo,onInstall = onEditorInstall,onSkill = onEditorSkill,
                    onNetwork = onEditorNetwork,onCreate = onCreateEnvironment,onPrepare = onPrepareEnvironment,
                    onSave = onSaveEnvironment,onPublish = onPublishEnvironment,onAnswer = onSetupAnswer,onSendAnswer = onSendSetupAnswer,onUse = onUseEnvironment,
                    onModel = onPreparationModel,onEffort = onPreparationEffort,onRefreshModels = onRefreshPreparationModels,modifier = Modifier.weight(1f))
            } else if (state.managingEnvironments) {
                CloudEnvironmentManagementContent(environments = cache?.environments.orEmpty(), selectedId = cache?.selectedEnvironment.orEmpty(),
                    accountName = account?.let { localizedAccountNickname(context, it) }.orEmpty(), loading = state.loading,
                    hasAccount = account != null, browserUnavailable = browserUnavailable, onSelect = onEnvironment,
                    onConfigure = onConfigure, onRefresh = onRefresh,onEdit = onEditEnvironment, modifier = Modifier.weight(1f))
            } else if (state.accounts.isEmpty()) {
                CloudEmpty(stringResource(R.string.cloud_no_account), onSetup, Modifier.weight(1f))
            } else if (state.newTask) {
                Spacer(Modifier.weight(1f))
                Column(Modifier.fillMaxWidth().imePadding().padding(horizontal = 20.dp, vertical = 16.dp)) {
                    TextButton(onClick = onLocal, enabled = !state.sending) {
                        Icon(Icons.Outlined.CloudQueue, null, Modifier.size(20.dp)); Spacer(Modifier.width(10.dp))
                        Text(stringResource(R.string.conversation_cloud)); Icon(Icons.Outlined.KeyboardArrowDown, null, Modifier.size(18.dp))
                    }
                    Box {
                        TextButton(onClick = { environmentsOpen = true },
                            enabled = !state.sending, modifier = Modifier.testTag("cloud-environment")) {
                            Icon(Icons.Outlined.FolderOpen, null, Modifier.size(20.dp)); Spacer(Modifier.width(10.dp))
                            Text(selectedEnvironment?.label?.ifBlank { selectedEnvironment.id } ?: stringResource(R.string.cloud_choose_environment),
                                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 230.dp))
                            Icon(Icons.Outlined.KeyboardArrowDown, null, Modifier.size(18.dp))
                        }
                        DropdownMenu(environmentsOpen, { environmentsOpen = false }) {
                            cache?.environments?.forEach { env -> DropdownMenuItem(text = { Column {
                                Text(env.label.ifBlank { env.id })
                                if(!env.published) Text(stringResource(R.string.cloud_environment_needs_publish),style = MaterialTheme.typography.labelSmall,color = MaterialTheme.colorScheme.onSurfaceVariant)
                            } },
                                trailingIcon = { if (env.id == cache.selectedEnvironment) Icon(Icons.Outlined.Check, null) },
                                onClick = { environmentsOpen = false; if(env.published) onEnvironment(env.id) else onEditEnvironment(env.id) }) }
                            HorizontalDivider()
                            DropdownMenuItem(text = { Text(stringResource(R.string.cloud_manage_environments)) },
                                leadingIcon = { Icon(Icons.Outlined.Tune, null) }, onClick = { environmentsOpen = false; onSetup() })
                        }
                    }
                    if (!state.loading && cache?.environments.isNullOrEmpty()) TextButton(onClick = onSetup) { Text(stringResource(R.string.cloud_setup_environment)) }
                    CloudMessageComposer(cache?.draft.orEmpty(),onDraft,onSend,state.models,state.choice,state.sending,
                        !state.loading && selectedEnvironment?.published == true && DurableCloudWire.validChoice(state.choice,state.models),
                        onPreparationModel,onPreparationEffort,onRefreshPreparationModels)
                }
            } else if (state.selectedTask.isNotBlank()) {
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    if (details == null) item { Text(stringResource(if (state.loading) R.string.cloud_loading_task else R.string.cloud_no_result), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    details?.messages?.let { messages -> items(messages.size, key = { "cloud-message:$it" }) { index ->
                        val message = messages[index]
                        ConversationMessage(TaskConversationMessage(message.role, message.text.take(48_000)), showCopy = task?.status == "completed")
                    } }
                    if (!details?.diff.isNullOrBlank()) item {
                        TextButton(onClick = { showDiff = !showDiff }) { Icon(Icons.Outlined.Difference, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.cloud_diff)) }
                        if (showDiff) Text(details!!.diff.take(48_000), style = MaterialTheme.typography.bodySmall)
                    }
                    if (details?.messages?.any { it.text.length > 48_000 } == true || (details?.diff?.length ?: 0) > 48_000) item {
                        Text(stringResource(R.string.cloud_content_shortened), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    item { OutlinedButton(onClick = { onOfficial(state.selectedTask) }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.cloud_open_official))
                    } }
                }
                Column(Modifier.fillMaxWidth().imePadding().padding(horizontal = 20.dp,vertical = 12.dp)) {
                    CloudMessageComposer(state.reply,onReplyDraft,onSendReply,state.models,state.choice,state.sending,
                        details != null && DurableCloudWire.validChoice(state.choice,state.models) && (task?.status != "running" || details.activeTurnId.isNotBlank()),
                        onPreparationModel,onPreparationEffort,onRefreshPreparationModels,running = task?.status == "running")
                }
            } else {
                if (cache?.page?.items.isNullOrEmpty() && !state.loading) CloudEmpty(stringResource(R.string.cloud_empty_tasks), onNew, Modifier.weight(1f))
                else LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(cache?.page?.items.orEmpty(), key = { it.id }) { item ->
                        Surface(onClick = { onOpen(item.id) }, shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Icon(Icons.Outlined.CloudQueue, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Column(Modifier.weight(1f)) {
                                    Text(item.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                                    Text(stringResource(cloudStatus(item.status)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (item.updatedAt > 0) Text(conversationTime(item.updatedAt, System.currentTimeMillis()), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    if (!cache?.page?.cursor.isNullOrEmpty()) item { TextButton(onClick = onMore, enabled = !state.loading, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.conversation_load_more)) } }
                }
            }
        }
    }
    if (branchOpen) {
        var value by remember { mutableStateOf(cache?.branch ?: "main") }
        AlertDialog(onDismissRequest = { branchOpen = false }, title = { Text(stringResource(R.string.cloud_branch)) },
            text = { OutlinedTextField(value, { value = it.take(255) }, singleLine = true, isError = !CloudWire.validBranch(value)) },
            confirmButton = { TextButton(onClick = { onBranch(value); branchOpen = false }, enabled = CloudWire.validBranch(value)) { Text(stringResource(R.string.action_save)) } },
            dismissButton = { TextButton(onClick = { branchOpen = false }) { Text(stringResource(R.string.action_cancel)) } })
    }
}

@Composable
private fun CloudEmpty(text: String, onAction: () -> Unit, modifier: Modifier) {
    Column(modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Outlined.CloudQueue, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text, Modifier.padding(vertical = 16.dp), style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = onAction) { Text(stringResource(R.string.cloud_get_started)) }
    }
}
private fun cloudStatus(status: String?) = when(status) {
    "completed" -> R.string.cloud_completed
    "failed" -> R.string.cloud_failed
    "running" -> R.string.cloud_running
    else -> R.string.cloud_unknown_status
}
private fun cloudError(kind: CloudErrorKind) = when(kind) {
    CloudErrorKind.LOGIN -> R.string.cloud_login_required
    CloudErrorKind.NODE_BLOCKED -> R.string.cloud_node_blocked
    CloudErrorKind.FORBIDDEN -> R.string.cloud_forbidden
    CloudErrorKind.RATE_LIMIT -> R.string.cloud_rate_limit
    CloudErrorKind.NETWORK -> R.string.cloud_network
    CloudErrorKind.UNCERTAIN -> R.string.cloud_uncertain
    CloudErrorKind.RESPONSE -> R.string.cloud_response
}
