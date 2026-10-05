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
import com.codex.quota.notifications.task.ConversationTimelineEntry
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
                    val active = latest.editor?.details?.task?.status == "running" || latest.awaitingTurnId.isNotBlank() ||
                        latest.cache?.details?.firstOrNull { it.task.id == latest.selectedTask }?.task?.status == "running"
                    delay(if (active) 2500 else if (latest.selectedTask.isNotBlank() || latest.editor?.session?.threadId?.isNotBlank() == true) 15_000 else 30_000)
                    if (!latest.sending && (latest.editor?.session?.threadId?.isNotBlank() == true || !latest.managingEnvironments && !latest.newTask))
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
        onOfficial = {}, onSetup = vm::manageEnvironments, onConfigure = vm::configureEnvironment,
        browserUnavailable = browserUnavailable,onEditEnvironment = vm::editEnvironment,
        onEditorName = vm::editorName,onEditorRepo = vm::editorRepo,onEditorInstall = vm::editorInstall,
        onEditorSkill = vm::editorSkill,onEditorNetwork = vm::editorNetwork,onCreateEnvironment = vm::createEnvironment,
        onPrepareEnvironment = vm::prepareEnvironment,onSaveEnvironment = vm::saveEnvironment,onPublishEnvironment = vm::publishEnvironment,
        onSetupAnswer = vm::editorAnswer,onSendSetupAnswer = vm::sendSetupAnswer,onUseEnvironment = vm::useEditorEnvironment,
        onPreparationModel = vm::preparationModel,onPreparationEffort = vm::preparationEffort,onRefreshPreparationModels = vm::refreshPreparationModels,
        onReplyDraft = vm::replyDraft,onSendReply = vm::sendReply,
        onPin = vm::pinThread, onRename = vm::renameThread, onArchive = vm::archiveThread,
        onRestore = vm::restoreThread, onArchived = vm::showArchived, onHistory = vm::loadHistory,
        onQueuedEdit = vm::editQueuedReply,onQueuedSteer = vm::steerQueuedReply,onQueuedCancel = vm::cancelQueuedReply,onStop = vm::stop,
        onRowPin = vm::pinThread, onRowRename = vm::renameThread, onRowArchive = vm::archiveThread, onRowRestore = vm::restoreThread)
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
    onReplyDraft: (String)->Unit = {},onSendReply: ()->Unit = {},
    onPin: ()->Unit = {}, onRename: (String)->Unit = {}, onArchive: ()->Unit = {},
    onRestore: ()->Unit = {}, onArchived: (Boolean)->Unit = {}, onHistory: ()->Unit = {},
    onQueuedEdit: ()->Unit = {},onQueuedSteer: ()->Unit = {},onQueuedCancel: ()->Unit = {},onStop: ()->Unit = {},
    onRowPin: (String)->Unit = {}, onRowRename: (String,String)->Unit = { _,_ -> },
    onRowArchive: (String)->Unit = {}, onRowRestore: (String)->Unit = {}) {
    val context = LocalContext.current
    var revealedRow by remember(state.accountId, state.showArchived, state.selectedTask) { mutableStateOf<String?>(null) }
    var renameRow by remember(state.accountId) { mutableStateOf<CloudTask?>(null) }
    var accountsOpen by remember(state.accountId) { mutableStateOf(false) }
    var environmentsOpen by remember(state.accountId) { mutableStateOf(false) }
    var menuOpen by remember(state.accountId, state.selectedTask) { mutableStateOf(false) }
    var statusOpen by remember(state.accountId, state.selectedTask) { mutableStateOf(false) }
    var renameOpen by remember(state.accountId, state.selectedTask) { mutableStateOf(false) }
    var archiveOpen by remember(state.accountId, state.selectedTask) { mutableStateOf(false) }
    var renameText by remember(state.accountId, state.selectedTask) { mutableStateOf("") }
    val cache = state.cache
    val page = if (state.showArchived) cache?.archivedPage else cache?.page
    val details = cache?.details?.firstOrNull { it.task.id == state.selectedTask }
    val task = details?.task ?: page?.items?.firstOrNull { it.id == state.selectedTask }
    val selectedEnvironment = cache?.environments?.firstOrNull { it.id == cache.selectedEnvironment }
    val account = state.accounts.firstOrNull { it.id == state.accountId }
    val accountName = account?.let { localizedAccountNickname(context, it) } ?: stringResource(R.string.cloud_account)
    val now = com.codex.quota.ui.components.rememberQuotaClock()
    val title = when {
        state.editor != null -> state.editor.config?.name?.ifBlank { null } ?: stringResource(R.string.cloud_native_configuration)
        state.managingEnvironments -> stringResource(R.string.cloud_manage_environments)
        state.selectedTask.isNotBlank() -> cloudConversationTitle(task, details)
        state.newTask -> stringResource(R.string.cloud_new_conversation)
        else -> stringResource(R.string.conversation_cloud)
    }
    Scaffold(containerColor = MaterialTheme.colorScheme.background, topBar = {
        CloudConversationHeader(title,
            if (state.newTask) (selectedEnvironment?.label?.ifBlank { null } ?: stringResource(R.string.cloud_choose_environment)) + " · Cloud"
            else "$accountName · Cloud",
            synced = cache?.fetchedAt?.let { it > 0 && now - it in 0..300_000L } == true && state.error == null,
            onBack = onBack, onEnvironment = { if (state.newTask) environmentsOpen = true else accountsOpen = true },
            onStatus = { statusOpen = true },
            onMenu = if (state.selectedTask.isNotBlank() && state.editor == null && !state.managingEnvironments) ({ menuOpen = true }) else null,
            menu = {
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }, shape = RoundedCornerShape(20.dp)) {
                    if (!state.showArchived) DropdownMenuItem(text = { Text(stringResource(if (state.selectedTask in cache?.pinnedThreads.orEmpty()) R.string.conversation_menu_unpin else R.string.conversation_menu_pin)) },
                        leadingIcon = { Icon(Icons.Outlined.PushPin, null, Modifier.size(20.dp)) }, enabled = !state.sending,
                        onClick = { menuOpen = false; onPin() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.conversation_menu_rename)) },
                        leadingIcon = { Icon(Icons.Outlined.Edit, null, Modifier.size(20.dp)) }, enabled = !state.sending,
                        onClick = { menuOpen = false; renameText = task?.title.orEmpty(); renameOpen = true })
                    DropdownMenuItem(text = { Text(stringResource(if (state.showArchived) R.string.conversation_restore else R.string.conversation_menu_archive)) },
                        leadingIcon = { Icon(Icons.Outlined.Archive, null, Modifier.size(20.dp)) }, enabled = !state.sending,
                        onClick = { menuOpen = false; if (state.showArchived) onRestore() else archiveOpen = true })
                }
            })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            state.error?.let { error ->
                Surface(Modifier.padding(horizontal = 20.dp, vertical = 6.dp), shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.errorContainer) {
                    Row(Modifier.padding(start = 14.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(cloudError(error)), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer)
                        IconButton(onClick = onRefresh, enabled = !state.loading && !state.sending) {
                            Icon(Icons.Outlined.Refresh, stringResource(R.string.cloud_refresh), Modifier.size(20.dp))
                        }
                    }
                }
            }
            when {
                state.editor != null -> CloudEnvironmentEditorContent(state.editor,
                    busy = state.sending || state.loading && state.editor.config == null,
                    onName = onEditorName,onRepo = onEditorRepo,onInstall = onEditorInstall,onSkill = onEditorSkill,
                    onNetwork = onEditorNetwork,onCreate = onCreateEnvironment,onPrepare = onPrepareEnvironment,
                    onSave = onSaveEnvironment,onPublish = onPublishEnvironment,onAnswer = onSetupAnswer,onSendAnswer = onSendSetupAnswer,onUse = onUseEnvironment,
                    onModel = onPreparationModel,onEffort = onPreparationEffort,onRefreshModels = onRefreshPreparationModels,modifier = Modifier.weight(1f),
                    queued = cache?.pendingReplies?.firstOrNull { it.threadId == state.editor.session?.threadId },
                    onQueuedEdit = onQueuedEdit,onQueuedSteer = onQueuedSteer,onQueuedCancel = onQueuedCancel,onStop = onStop)
                state.managingEnvironments -> CloudEnvironmentManagementContent(
                    environments = cache?.environments.orEmpty(), selectedId = cache?.selectedEnvironment.orEmpty(),
                    accountName = accountName, loading = state.loading, hasAccount = account != null,
                    browserUnavailable = browserUnavailable, onSelect = onEnvironment, onConfigure = onConfigure,
                    onRefresh = onRefresh,onEdit = onEditEnvironment, modifier = Modifier.weight(1f))
                state.accounts.isEmpty() -> CloudEmpty(stringResource(R.string.cloud_no_account), onLocal, Modifier.weight(1f))
                state.newTask -> {
                    Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        com.codex.quota.ui.components.SectionIcon(Icons.Outlined.CloudQueue, Modifier.size(56.dp))
                        Text(stringResource(R.string.cloud_new_conversation), Modifier.padding(top = 18.dp),
                            style = MaterialTheme.typography.headlineSmall, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                        Text(accountName, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Column(Modifier.fillMaxWidth().imePadding().padding(horizontal = 16.dp, vertical = 12.dp)) {
                        cache?.pendingReplies?.firstOrNull { it.threadId == state.selectedTask }?.let { pending ->
                            QueuedMessageChip(pending.text,!state.sending,task?.status == "running",onQueuedEdit,onQueuedSteer,onQueuedCancel)
                            Spacer(Modifier.height(8.dp))
                        }
                        TextButton(onClick = { environmentsOpen = true },enabled = !state.sending,
                            modifier = Modifier.testTag("cloud-environment")) {
                            Icon(Icons.Outlined.FolderOpen, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                            Text(selectedEnvironment?.label?.ifBlank { null } ?: stringResource(R.string.cloud_choose_environment),
                                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                            Icon(Icons.Outlined.KeyboardArrowDown, null, Modifier.size(18.dp))
                        }
                        CloudMessageComposer(cache?.draft.orEmpty(),onDraft,onSend,state.models,state.choice,state.sending,
                            !state.loading && selectedEnvironment?.published == true && DurableCloudWire.validChoice(state.choice,state.models),
                            onPreparationModel,onPreparationEffort,onRefreshPreparationModels, onOptions = { environmentsOpen = true })
                    }
                }
                state.selectedTask.isNotBlank() -> {
                    key(state.accountId, state.selectedTask) {
                        CloudConversationMessages(details.takeUnless { state.openingTask && state.error == null }, state.loading || state.openingTask,
                            Modifier.weight(1f), onHistory = onHistory, historyLoading = state.historyLoading)
                    }
                    Column(Modifier.fillMaxWidth().imePadding().padding(horizontal = 16.dp, vertical = 12.dp)) {
                        if (state.showArchived) Text(stringResource(R.string.conversation_archived),
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.CenterHorizontally))
                        else CloudMessageComposer(state.reply,onReplyDraft,onSendReply,state.models,state.choice,state.sending,
                            !state.openingTask && details != null && cache?.pendingReplies?.none { it.threadId == state.selectedTask } != false && DurableCloudWire.validChoice(state.choice,state.models) && (task?.status != "running" || details.activeTurnId.isNotBlank()),
                            onPreparationModel,onPreparationEffort,onRefreshPreparationModels,running = task?.status == "running",onStop = onStop)
                    }
                }
                else -> {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.conversations_title), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                        FilledTonalIconButton(onClick = onNew, enabled = !state.sending) {
                            Icon(Icons.Outlined.EditNote, stringResource(R.string.cloud_new_conversation))
                        }
                    }
                    TextButton(onClick = onSetup, enabled = !state.sending,
                        modifier = Modifier.padding(horizontal = 18.dp)) {
                        Icon(Icons.Outlined.Tune, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.cloud_manage_environments))
                    }
                    Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = !state.showArchived, onClick = { onArchived(false) }, enabled = !state.sending,
                            label = { Text(stringResource(R.string.conversations_title)) })
                        FilterChip(selected = state.showArchived, onClick = { onArchived(true) }, enabled = !state.sending,
                            label = { Text(stringResource(R.string.conversation_archived)) })
                    }
                    if (page?.items.isNullOrEmpty() && !state.loading)
                        CloudEmpty(stringResource(R.string.cloud_empty_tasks), onNew, Modifier.weight(1f))
                    else LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(page?.items.orEmpty().sortedWith(compareByDescending<CloudTask> { it.id in cache?.pinnedThreads.orEmpty() }
                            .thenByDescending { it.updatedAt }), key = { it.id }) { item ->
                            ConversationSwipeRow(revealedRow == item.id, { revealedRow = item.id }, { if (revealedRow == item.id) revealedRow = null },
                                pinned = item.id in cache?.pinnedThreads.orEmpty(), archived = state.showArchived, enabled = !state.sending,
                                onPin = { onRowPin(item.id) }, onRename = { renameRow = item },
                                onArchive = { if (state.showArchived) onRowRestore(item.id) else onRowArchive(item.id) }) {
                            Surface(onClick = { onOpen(item.id) }, shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surface,
                                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))) {
                                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                        Text(cloudConversationTitle(item, cache?.details?.firstOrNull { it.task.id == item.id }),
                                            maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                                        if (item.status == "failed") Text(stringResource(cloudStatus(item.status)), style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    if (item.id in cache?.pinnedThreads.orEmpty()) Icon(Icons.Outlined.PushPin, null,
                                        Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    if (item.updatedAt > 0) Text(conversationTime(item.updatedAt, System.currentTimeMillis()),
                                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            }
                        }
                        if (!page?.cursor.isNullOrBlank()) item {
                            TextButton(onClick = onMore, enabled = !state.loading, modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(R.string.conversation_load_more))
                            }
                        }
                        if (state.loading && page?.items.isNullOrEmpty()) item {
                            Text(stringResource(R.string.cloud_loading_task), Modifier.padding(12.dp),
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
    renameRow?.let { row -> ConversationRenameDialog(row.title,
        onRename = { name -> renameRow = null; onRowRename(row.id, name) }, onDismiss = { renameRow = null }) }
    if (accountsOpen) CloudSelectionSheet(stringResource(R.string.cloud_account), { accountsOpen = false }) {
        state.accounts.forEach { entry -> CloudSheetAction(localizedAccountNickname(context, entry), Icons.Outlined.PersonOutline,
            onClick = { accountsOpen = false; onAccount(entry.id) }, selected = entry.id == state.accountId,
            enabled = !state.sending, supporting = localizedShortPlanName(context, entry.planType)) }
        HorizontalDivider(Modifier.padding(horizontal = 18.dp, vertical = 8.dp))
        CloudSheetAction(stringResource(R.string.cloud_local), Icons.Outlined.Computer, onClick = { accountsOpen = false; onLocal() })
    }
    if (environmentsOpen) CloudSelectionSheet(stringResource(R.string.cloud_choose_environment), { environmentsOpen = false }) {
        cache?.environments?.forEach { env -> CloudSheetAction(env.label.ifBlank { stringResource(R.string.cloud_native_configuration) },
            Icons.Outlined.FolderOpen, selected = env.id == cache.selectedEnvironment, enabled = !state.sending,
            supporting = if (!env.published) stringResource(R.string.cloud_environment_needs_publish) else null,
            onClick = { environmentsOpen = false; if (env.published) onEnvironment(env.id) else onEditEnvironment(env.id) }) }
        CloudSheetAction(stringResource(R.string.cloud_manage_environments), Icons.Outlined.Tune,
            onClick = { environmentsOpen = false; onSetup() }, enabled = !state.sending)
    }
    if (renameOpen) AlertDialog(onDismissRequest = { renameOpen = false },
        title = { Text(stringResource(R.string.conversation_menu_rename)) },
        text = { OutlinedTextField(renameText, { if (it.toByteArray().size <= 240 && it.none { c -> c == '\n' || c == '\r' }) renameText = it },
            singleLine = true, label = { Text(stringResource(R.string.conversation_name)) }) },
        confirmButton = { TextButton(onClick = { renameOpen = false; onRename(renameText.trim()) },
            enabled = renameText.isNotBlank() && !state.sending) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(onClick = { renameOpen = false }) { Text(stringResource(R.string.action_cancel)) } })
    if (archiveOpen) AlertDialog(onDismissRequest = { archiveOpen = false },
        title = { Text(stringResource(R.string.conversation_menu_archive)) },
        text = { Text(stringResource(R.string.cloud_archive_detail)) },
        confirmButton = { TextButton(onClick = { archiveOpen = false; onArchive() }, enabled = !state.sending) {
            Text(stringResource(R.string.conversation_menu_archive)) } },
        dismissButton = { TextButton(onClick = { archiveOpen = false }) { Text(stringResource(R.string.action_cancel)) } })
    if (statusOpen) CloudQuotaStatus(state.usage, state.usage?.fetchedAtEpochMs ?: 0, state.editor?.details?.task ?: task,
        state.editor?.details ?: details, onDismiss = { statusOpen = false })
}

/** Reverse layout starts at the latest reply; history readers retain their position during polling. */
@Composable
internal fun CloudConversationMessages(details: CloudDetails?, loading: Boolean, modifier: Modifier = Modifier,
    onHistory: (() -> Unit)? = null, historyLoading: Boolean = false) {
    val scroll = rememberCloudConversationScroll(details?.task?.id.orEmpty(),details != null,
        details?.messages?.lastOrNull { it.role == "user" }?.id.orEmpty())
    var showDiff by remember(details?.task?.id) { mutableStateOf(false) }
    val messages = remember(details?.olderMessages, details?.messages) { details?.let(CloudHistory::messages).orEmpty() }
    val groups = remember(details, messages) { details?.let { com.codex.quota.notifications.task.ConversationTimeline.turnGroups(cloudTranscript(it.copy(messages = messages))) }.orEmpty() }
    LazyColumn(modifier.fillMaxWidth(), state = scroll.state,
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
        if (details != null && onHistory != null && CloudHistory.cursor(details).isNotBlank()) item(key = "earlier") {
            TextButton(onClick = onHistory, enabled = !historyLoading, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(if (historyLoading) R.string.cloud_loading_task else R.string.conversation_load_earlier))
            }
        }
        items(groups,key = { "cloud-turn:" + com.codex.quota.notifications.task.ConversationTimeline.turn(it.first()) }) { entries ->
            val turn = com.codex.quota.notifications.task.ConversationTimeline.turn(entries.first())
            val info = details?.turns?.firstOrNull { it.id == turn }
            ConversationTurnBlock(entries,
                completed = info?.status?.let { it != "inProgress" } ?: (turn != details?.latestTurnId || details.task.status != "running"),
                durationMs = info?.durationMs,onBrowseProcess = scroll.browse, startedAt = info?.startedAt)
        }

        if (!details?.diff.isNullOrBlank()) item(key = "diff") {
            Surface(onClick = { showDiff = !showDiff }, shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Difference, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                        Text(stringResource(R.string.cloud_diff), Modifier.weight(1f).padding(start = 10.dp), style = MaterialTheme.typography.titleSmall)
                        Icon(if (showDiff) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
                    }
                    if (showDiff) Text(details!!.diff.take(48_000), Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (details == null || messages.isEmpty()) item(key = "empty") {
            Text(stringResource(if (loading) R.string.cloud_loading_task else R.string.cloud_no_result), color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium)
        }
        if (messages.any { it.text.length > 48_000 } || (details?.diff?.length ?: 0) > 48_000) item(key = "shortened") {
            Text(stringResource(R.string.cloud_content_shortened), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (details?.historyLimited == true) item(key = "history-limit") {
            Text(stringResource(R.string.cloud_history_limit), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
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
internal fun cloudStatus(status: String?) = when(status) {
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
