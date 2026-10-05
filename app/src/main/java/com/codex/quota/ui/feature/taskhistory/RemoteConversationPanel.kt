package com.codex.quota.ui.feature.taskhistory

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.codex.quota.R
import com.codex.quota.notifications.task.*
import com.codex.quota.ui.components.rememberConversationClock
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun RemoteStatusPanel(id: String, state: RemoteConversationState, controlsEnabled: Boolean, library: Boolean = false) {
    val context = LocalContext.current
    val client = remember { RemoteConversationClient(context) }
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf(false) }
    var answeringApproval by remember(state.event?.approval_id) { mutableStateOf(false) }
    var expired by remember(state.command.id) { mutableStateOf(false) }
    LaunchedEffect(state.command.id) {
        delay((state.command.issued_at + 30_000 - System.currentTimeMillis()).coerceAtLeast(0)); expired = true
    }
    val event = state.event
    val now = rememberConversationClock()
    val status = when {
        event?.attachment_pending == true -> R.string.remote_downloading_result
        event?.status == "completed" -> R.string.remote_completed
        event?.status == "interrupted" -> R.string.remote_stopped
        event?.status in setOf("running", "approval", "input_required", "accepted") && event != null && now - event.at > 120_000 -> R.string.remote_delayed
        event?.status == "input_required" -> R.string.remote_input_required
        event?.status == "approval" -> R.string.remote_approval
        event?.status == "running" -> R.string.remote_running
        event?.status == "failed" -> when (event.error) {
            "BUSY", "HOST_BUSY" -> R.string.remote_busy
            "STALE" -> R.string.remote_stale
            "DESKTOP_OWNS_THREAD" -> R.string.remote_desktop_owns_thread
            "UNSUPPORTED_TOOL" -> R.string.remote_unsupported
            "INVALID_MODEL_SELECTION" -> R.string.remote_invalid_selection
            "MODE_UNAVAILABLE" -> R.string.remote_mode_unavailable
            "RATE_LIMIT" -> R.string.remote_rate_limit
            "AUTH_REQUIRED" -> R.string.remote_auth_required
            "ACCESS_DENIED" -> R.string.remote_access_denied
            "CONTEXT_FULL" -> R.string.remote_context_full
            "PROVIDER_UNAVAILABLE", "PROVIDER_CONNECTION" -> R.string.remote_provider_connection
            else -> R.string.remote_codex_error
        }
        event?.status == "unknown" || expired || state.transport == "unconfirmed" -> R.string.remote_unconfirmed
        else -> R.string.remote_waiting
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (status != R.string.remote_running) Text(stringResource(status), style = MaterialTheme.typography.bodySmall,
            color = if (event?.status == "failed") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        if (event?.partial == true) Text(stringResource(R.string.remote_partial), style = MaterialTheme.typography.bodySmall)
        if (error) Text(stringResource(R.string.remote_network_error), color = MaterialTheme.colorScheme.error)
        if (event?.status == "approval" && controlsEnabled) {
            Text(stringResource(if (event.approval_kind == "fileChange") R.string.remote_file_approval else R.string.remote_command_approval), style = MaterialTheme.typography.titleSmall)
            SelectionContainer { Text(event.approval_text) }
            if (!event.approval_can_allow) Text(stringResource(R.string.remote_approval_desktop_required), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(false, true).forEach { allow ->
                    OutlinedButton(onClick = {
                        answeringApproval = true
                        scope.launch {
                        try { client.approve(id, event.approval_id, allow, library); error = false }
                        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                        catch (_: Exception) { error = true; answeringApproval = false }
                    } }, enabled = !answeringApproval && !event.attachment_pending && (!allow || !event.partial && event.approval_can_allow)) {
                        Text(stringResource(if (allow) R.string.remote_allow else R.string.remote_deny))
                    }
                }
            }
        }
        if (controlsEnabled && !library && state.transport == "unconfirmed" && event == null) {
            TextButton(onClick = {
                scope.launch { error = runCatching { client.retry(id) }.isFailure }
            }) { Text(stringResource(R.string.remote_retry)) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RemoteComposer(id: String, record: TaskInboxRecord, onFork: () -> Unit = {}, onGoal: () -> Unit = {}) {
    val state = record.remoteState
    val context = LocalContext.current
    val client = remember { RemoteConversationClient(context) }
    val scope = rememberCoroutineScope()
    val preferences = remember { ConversationPreferenceStore(context.applicationContext) }
    val preferenceId = record.snapshot.conversation_id.ifBlank { id }
    var text by rememberSaveable(record.endpointHash, id) { mutableStateOf("") }
    var draftLoaded by remember(record.endpointHash, id) { mutableStateOf(false) }
    LaunchedEffect(preferenceId) {
        if (!draftLoaded) {
            text = preferences.readAsync(record.endpointHash, preferenceId).await().draft
            draftLoaded = true
        } else {
            // Creating a thread promotes its temporary identity. Keep text typed while the
            // first response is running and persist it under the actual conversation ID.
            val draft = text
            preferences.updateAsync(record.endpointHash, preferenceId) { it.copy(draft = draft) }.await()
        }
    }
    LaunchedEffect(preferenceId, text, draftLoaded) {
        if (draftLoaded) { delay(300); val draft = text; preferences.updateAsync(record.endpointHash, preferenceId) { it.copy(draft = draft) }.await() }
    }
    val latestDraft by rememberUpdatedState(text)
    val latestLoaded by rememberUpdatedState(draftLoaded)
    DisposableEffect(preferenceId) {
        onDispose { if (latestLoaded) {
            val draft = latestDraft
            preferences.updateAsync(record.endpointHash, preferenceId) { it.copy(draft = draft) }
        } }
    }
    var sending by remember { mutableStateOf(false) }
    var steeringIds by remember(id) { mutableStateOf<Set<String>>(emptySet()) }
    var error by remember { mutableStateOf(false) }
    var unreachable by remember { mutableStateOf(false) }
    fun pendingAction(action: suspend () -> Unit) { scope.launch {
        try { action(); error = false }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { error = true }
    } }
    var preparationError by remember { mutableStateOf<Int?>(null) }
    var stopping by remember { mutableStateOf(false) }
    val followMode = "steer"
    val goalTask = state?.command?.action in RemoteGoalRules.rootActions || state?.event?.goal != null



    var addMenu by remember { mutableStateOf(false) }
    var permissionSheet by remember { mutableStateOf(false) }
    var modelMenu by remember { mutableStateOf(false) }
    var skillsSheet by remember { mutableStateOf(false) }
    val pluginStore = remember { ComposerPluginStore(context.applicationContext) }
    val pluginHost = (record.snapshot.thread_ref ?: record.snapshot.remote_ref)?.host_id ?: record.computerHostId
    var visiblePluginIds by remember(record.endpointHash, pluginHost) { mutableStateOf<Set<String>?>(null) }
    var pluginPreferencesLoaded by remember(record.endpointHash, pluginHost) { mutableStateOf(false) }
    LaunchedEffect(record.endpointHash, pluginHost) {
        visiblePluginIds = withContext(Dispatchers.IO) { pluginStore.read(record.endpointHash, pluginHost) }
        pluginPreferencesLoaded = true
    }
    var attachmentError by remember { mutableStateOf<String?>(null) }
    val fileSaver = remember {
        listSaver<List<SelectedRemoteFile>, String>(
            save = { it.flatMap { file -> listOf(file.uri.toString(), file.name, file.mime) } },
            restore = { it.chunked(3).mapNotNull { parts ->
                if (parts.size == 3) SelectedRemoteFile(android.net.Uri.parse(parts[0]), parts[1], parts[2]) else null
            } })
    }
    var files by rememberSaveable(preferenceId, stateSaver = fileSaver) { mutableStateOf<List<SelectedRemoteFile>>(emptyList()) }
    var selectedSkills by rememberSaveable(preferenceId) { mutableStateOf<List<String>>(emptyList()) }
    fun picked(uris: List<android.net.Uri>, photo: Boolean) {
        if (uris.isEmpty()) return
        scope.launch {
            try {
                val selected = withContext(Dispatchers.IO) { uris.distinct().map { RemoteAttachmentUpload.select(context, it, photo) } }
                val updated = SelectedAttachmentRules.merge(files, selected) { it.uri }
                withContext(Dispatchers.IO) { selected.forEach { file ->
                    runCatching { context.contentResolver.takePersistableUriPermission(file.uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                } }
                files = updated; attachmentError = null
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (e: Exception) { attachmentError = e.message ?: "ATTACHMENT_INVALID" }
        }
    }
    val photos = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { picked(it, true) }
    val documents = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { picked(it, false) }
    val skillCatalog = record.skillsResult?.takeIf { it.request_id == record.skillsRequest?.id && !it.attachment_pending }
    fun refreshSkills() { scope.launch { try { client.session(id, "skills") }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { attachmentError = "SKILLS_UNAVAILABLE" } } }

    var model by rememberSaveable(preferenceId) { mutableStateOf(record.selectedModel) }
    var effort by rememberSaveable(preferenceId) { mutableStateOf(record.selectedEffort) }
    var mode by rememberSaveable(preferenceId) { mutableStateOf(record.selectedMode) }
    LaunchedEffect(record.selectedMode) { mode = record.selectedMode }
    LaunchedEffect(record.selectedModel, record.selectedEffort) { model = record.selectedModel; effort = record.selectedEffort }
    var modelError by remember { mutableStateOf(false) }
    var modelExpired by remember(record.modelRequest?.id) { mutableStateOf(false) }
    LaunchedEffect(record.modelRequest?.id) {
        record.modelRequest?.let { delay((it.issued_at + 30_000 - System.currentTimeMillis()).coerceAtLeast(0)); modelExpired = true }
    }
    val catalog = record.modelCatalog
    val modelLoading = record.modelRequest != null && catalog?.request_id != record.modelRequest.id && !modelExpired
    val options = catalog?.models.orEmpty()
    LaunchedEffect(catalog?.id) {
        if (catalog != null) {
            val resolved = ConversationDefaults.explicit(options, catalog.model, catalog.effort, model, effort, mode, catalog.modes)
            if (resolved != Triple(model, effort, mode)) {
                model = resolved.first; effort = resolved.second; mode = resolved.third
                withContext(Dispatchers.IO) { TaskInbox(context).setOptions(id, model, effort, mode) }
            }
        }
    }
    val chosen = options.firstOrNull { it.model == (model.ifBlank { catalog?.model.orEmpty() }) }
    var voiceError by remember { mutableStateOf<Int?>(null) }
    var listening by remember { mutableStateOf(false) }
    var voiceStage by remember { mutableIntStateOf(R.string.remote_listening) }
    fun refreshModels() {
        modelError = false
        scope.launch { try { client.models(id) } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { modelError = true } }
    }
    fun selectOptions(newModel: String, newEffort: String) {
        model = newModel; effort = newEffort
        scope.launch { withContext(Dispatchers.IO) { TaskInbox(context).setOptions(id, newModel, newEffort) } }
    }
    val available = RemoteComposerRules.idle(record)
    val canPrepare = !sending && draftLoaded
    val sendReady = RemoteComposerRules.canSend(record, files.isNotEmpty(), selectedSkills.isNotEmpty())
    val running = RemoteComposerRules.followUp(record)
    val desktopTurn = RemoteFollowUpRules.desktop(record)
    val preparing = state?.event?.status == "accepted" && !goalTask
    val inputLabel = stringResource(R.string.remote_input)
    fun send() {
        if (sending || !sendReady) return
        val prompt = text.ifBlank { context.getString(R.string.remote_attachment_prompt) }
        val sendingFiles = files
        val sendingSkills = selectedSkills
        sending = true; error = false; unreachable = false; preparationError = null
        scope.launch {
            try { if (running) client.queueReply(id,prompt,sendingFiles) else client.send(id, prompt, model, effort, mode, sendingFiles, sendingSkills); files = emptyList(); selectedSkills = emptyList(); if (text == prompt || text.isBlank()) { text = ""; preferences.updateAsync(record.endpointHash, preferenceId) { it.copy(draft = "") }.await() } }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (e: Exception) {
                error = true; unreachable = e.message == "COMPUTER_UNREACHABLE"
                preparationError = when (e.message) { "REMOTE_BUSY" -> R.string.remote_busy; "REMOTE_STALE" -> R.string.remote_stale; else -> null }
                if (e.message?.startsWith("ATTACHMENT") == true) attachmentError = e.message
            }
            finally { sending = false }
        }
    }
    Column(Modifier.fillMaxWidth().imePadding().padding(horizontal = 12.dp, vertical = 10.dp)) {
        record.localQueuedReplies.forEach { entry ->
            key(entry.id) {
                val followUp = record.followUps.firstOrNull { it.command.id == entry.id }
                val waiting = entry.id in steeringIds || followUp?.let(FollowUpState::pending) == true
                fun discard() = scope.launch {
                    try {
                        if (followUp?.let(FollowUpState::recoverable) == true) client.dismissUnconfirmedReply(id,entry.id)
                        else client.cancelQueuedReply(id,entry.id)
                        error = false
                    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (_: Exception) { error = true }
                }
                QueuedMessageChip(entry.text, !waiting, running && (followUp == null || FollowUpState.retryable(followUp)),
                    onEdit = { scope.launch {
                        try {
                            val restored = SelectedAttachmentRules.merge(files, entry.files.map { SelectedRemoteFile(android.net.Uri.parse(it.uri), it.name, it.mime) }) { it.uri }
                            if (followUp?.let(FollowUpState::recoverable) == true) client.dismissUnconfirmedReply(id,entry.id)
                            else client.cancelQueuedReply(id,entry.id)
                            files = restored; text = listOf(entry.text,text).filter { it.isNotBlank() }.joinToString("\n"); error = false
                        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                        catch (_: Exception) { error = true }
                    } },
                    onSteer = {
                        if (entry.id !in steeringIds) {
                            steeringIds = steeringIds + entry.id
                            scope.launch {
                                try { client.steerLocalReply(id,entry.id); error = false }
                                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                                catch (_: Exception) { preparationError = R.string.remote_delivery_unconfirmed }
                                finally { steeringIds = steeringIds - entry.id }
                            }
                        }
                    }, onCancel = { discard() })
                if (waiting) Text(stringResource(R.string.remote_delivery_pending), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp))
                Spacer(Modifier.height(8.dp))
            }
        }
        record.followUps.filter { FollowUpState.visible(it) && it.command.id !in record.localQueuedReplies.map { p -> p.id } &&
            (it.command.queue_id.isBlank() || it.command.action != "steer") }.takeLast(4).forEach { entry ->
            if (FollowUpState.queued(entry)) QueuedMessageChip(entry.command.text, !sending, running && !desktopTurn,
                onEdit = { pendingAction { client.followUp(id,"cancel_queue",queueId = entry.command.id); text = listOf(entry.command.text,text).filter { it.isNotBlank() }.joinToString("\n") } },
                onSteer = { pendingAction { client.steerQueued(id,entry.command.id) } },
                onCancel = { pendingAction { client.followUp(id,"cancel_queue",queueId = entry.command.id) } })
            else if (FollowUpState.recoverable(entry)) {
                QueuedMessageChip(entry.command.text, true, false,
                    onEdit = { scope.launch {
                        try { client.dismissUnconfirmedReply(id,entry.command.id); text = listOf(entry.command.text,text).filter { it.isNotBlank() }.joinToString("\n"); error = false }
                        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                        catch (_: Exception) { error = true }
                    } }, onSteer = {}, onCancel = { scope.launch {
                        try { client.dismissUnconfirmedReply(id,entry.command.id) }
                        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                        catch (_: Exception) { error = true }
                    } })
                Text(stringResource(R.string.remote_delivery_unconfirmed), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp))
            } else Text(stringResource(R.string.remote_delivery_pending), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Surface(shape = RoundedCornerShape(com.codex.quota.ui.theme.UiMetrics.ComposerRadius), color = MaterialTheme.colorScheme.surface,
            border = androidx.compose.foundation.BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            attachmentError?.let { Text(stringResource(when (it) {
                "ATTACHMENT_TOO_LARGE" -> R.string.remote_attachment_too_large
                "ATTACHMENT_TOO_MANY" -> R.string.remote_attachment_too_many
                "SKILLS_UNAVAILABLE" -> R.string.remote_skills_unavailable
                "ATTACHMENT_UNSUPPORTED" -> R.string.remote_attachment_unsupported
                else -> R.string.remote_attachment_failed
            }), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            if (files.isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                files.forEach { file -> key(file.uri.toString()) { SelectedAttachmentPreview(file, !sending) { files = files - file } } }
            }
            if (selectedSkills.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                selectedSkills.forEach { skill -> InputChip(selected = true, enabled = !sending, onClick = { selectedSkills = selectedSkills - skill },
                    label = { Text(skillCatalog?.skills?.firstOrNull { it.id == skill }?.let(::skillDisplayName).orEmpty(), maxLines = 1) }, trailingIcon = { Icon(Icons.Outlined.Close, stringResource(R.string.remote_attachment_remove), Modifier.size(16.dp)) }) }
            }
            if (!available && selectedSkills.isNotEmpty()) Text(stringResource(R.string.remote_attachments_wait),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (mode == "plan" || goalTask) ComposerModeTag(goalTask, !sending) {
                if (goalTask) scope.launch {
                    try { client.goal(id, "goal_pause") }
                    catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (_: Exception) { error = true }
                } else {
                    mode = "default"
                    scope.launch { withContext(Dispatchers.IO) { TaskInbox(context).setOptions(id, model, effort, "default") } }
                }
            }
            voiceError?.let { Text(stringResource(R.string.remote_voice_error, it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            if (listening) Text(stringResource(voiceStage), style = MaterialTheme.typography.bodySmall)
            if (error) Text(stringResource(preparationError ?: if (unreachable) R.string.computer_unreachable else R.string.remote_network_error), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            BasicTextField(value = text, onValueChange = { if (it.toByteArray().size <= 16_384) text = it },
                modifier = Modifier.fillMaxWidth().heightIn(min = 28.dp).semantics { contentDescription = inputLabel },
                minLines = 1, maxLines = 5, enabled = !sending && draftLoaded,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface), decorationBox = { field ->
                    Box { if (text.isEmpty()) Text(inputLabel, color = MaterialTheme.colorScheme.onSurfaceVariant); field() }
                })
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box {
                        IconButton(onClick = { addMenu = true; if (skillCatalog == null && record.skillsRequest == null) refreshSkills(); if (catalog == null && !modelLoading) refreshModels() }, enabled = !sending) { Icon(Icons.Outlined.Add, stringResource(R.string.conversation_add)) }
                        DropdownMenu(addMenu, { addMenu = false }, shape = RoundedCornerShape(24.dp), modifier = Modifier.widthIn(min = 240.dp, max = 280.dp), containerColor = MaterialTheme.colorScheme.surfaceContainerLowest) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.remote_upload_photo)) }, leadingIcon = { Icon(ComposerIcons.Photo, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }, enabled = canPrepare,
                                onClick = { addMenu = false; photos.launch(arrayOf("image/*")) })
                            DropdownMenuItem(text = { Text(stringResource(R.string.remote_upload_file)) }, leadingIcon = { Icon(ComposerIcons.File, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }, enabled = canPrepare,
                                onClick = { addMenu = false; documents.launch(arrayOf("*/*")) })
                            DropdownMenuItem(text = { Text(stringResource(R.string.remote_mode_plan)) }, leadingIcon = { Icon(ComposerIcons.Plan, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                                trailingIcon = { if (mode == "plan") Icon(Icons.Outlined.Check, null) }, enabled = canPrepare && "plan" in catalog?.modes.orEmpty(),
                                onClick = {
                                    addMenu = false; mode = if (mode == "plan") "default" else "plan"
                                    val selected = mode
                                    scope.launch { withContext(Dispatchers.IO) { TaskInbox(context).setOptions(id, model, effort, selected) } }
                                })
                            DropdownMenuItem(text = { Text(stringResource(R.string.remote_goal_title)) }, leadingIcon = { Icon(ComposerIcons.Goal, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }, onClick = { addMenu = false; onGoal() })
                            DropdownMenuItem(text = { Text(stringResource(R.string.conversation_fork)) }, leadingIcon = { Icon(ComposerIcons.Branch, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                                enabled = available && !sending && record.snapshot.remote_ref != null,
                                onClick = { addMenu = false; onFork() })
                            HorizontalDivider(Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(R.string.remote_plugins_heading), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                TextButton(onClick = { addMenu = false; skillsSheet = true; if (skillCatalog == null) refreshSkills() },
                                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)) {
                                    Text(stringResource(R.string.composer_manage_short))
                                }
                            }
                            val visibleSkills = visiblePluginIds?.let { ids -> skillCatalog?.skills.orEmpty().filter { it.id in ids } }
                                ?: ComposerCatalog.skills(skillCatalog?.skills.orEmpty())
                            visibleSkills.forEach { skill ->
                                ComposerSkillItem(skill, skill.id in selectedSkills, canPrepare && (skill.id in selectedSkills || selectedSkills.size < 4)) {
                                    selectedSkills = if (skill.id in selectedSkills) selectedSkills - skill.id else selectedSkills + skill.id
                                    addMenu = false
                                }
                            }

                        }
                    }
                    IconButton(onClick = { permissionSheet = true }, enabled = !sending) {
                        Icon(permissionIcon(record.selectedPermission), stringResource(R.string.remote_permission_title),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.weight(1f))
                Box {
                    TextButton(onClick = { modelMenu = true; if (catalog == null && !modelLoading) refreshModels() }, enabled = canPrepare, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface), contentPadding = PaddingValues(horizontal = 6.dp)) {
                        Text((options.firstOrNull { it.model == model }?.name ?: model.ifBlank { stringResource(R.string.remote_model) }),
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 100.dp), style = MaterialTheme.typography.labelMedium)
                        if (effort.isNotBlank()) Text(" " + effortLabel(effort), style = MaterialTheme.typography.labelMedium)
                    }
                    ComposerModelMenu(modelMenu, { modelMenu = false }, chosen?.efforts.orEmpty(), effort,
                        chosen?.name ?: model, { selectOptions(model, it) },
                        models = options, selectedModel = model, onModel = { selected -> selectOptions(selected, options.firstOrNull { it.model == selected }?.default_effort.orEmpty()) },
                        loading = modelLoading, failed = modelError || catalog?.error?.isNotBlank() == true, onRefresh = ::refreshModels)
                }
                if (!running && !preparing && text.isBlank() && files.isEmpty()) VoiceInputButton(enabled = !sending, onStage = { voiceStage = it }, onListening = { listening = it; if (it) voiceError = null }, onError = { voiceError = it }, onResult = { recognized ->
                    val draft = listOf(text.trimEnd(), recognized.trim()).filter { it.isNotBlank() }.joinToString(" ")
                    if (draft.toByteArray().size <= 16_384) { text = draft; voiceError = null } else voiceError = -1
                })
                if (running && !desktopTurn || preparing) FilledIconButton(onClick = {
                    if (stopping) return@FilledIconButton
                    stopping = true
                    scope.launch {
                        try { client.control(id, "stop") }
                        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                        catch (_: Exception) { error = true }
                        finally { stopping = false }
                    }
                }, enabled = !stopping, shape = CircleShape, colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.onSurface, contentColor = MaterialTheme.colorScheme.surface)) { Icon(Icons.Default.Stop, stringResource(R.string.remote_stop)) }
                if (!running || text.isNotBlank() || files.isNotEmpty()) { if (text.isNotBlank() || files.isNotEmpty()) FilledIconButton(onClick = ::send, enabled = sendReady && !sending && !listening && (text.isNotBlank() || files.isNotEmpty()),
                    shape = CircleShape, colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.onSurface, contentColor = MaterialTheme.colorScheme.surface)) {
                    Icon(Icons.Default.ArrowUpward, stringResource(if (running) {
                        if (!desktopTurn && followMode == "queue") R.string.remote_queue_send else R.string.remote_steer_send
                    } else R.string.remote_send))
                } }
            }
        }
        }
    }
    if (permissionSheet) RemotePermissionSheet(record.selectedPermission, canPrepare, { selected ->
        scope.launch { withContext(Dispatchers.IO) { TaskInbox(context).setPermission(id, selected) }; permissionSheet = false }
    }) { permissionSheet = false }
    if (skillsSheet) ComposerPluginManager(skillCatalog?.skills.orEmpty(),
        visiblePluginIds ?: ComposerCatalog.skills(skillCatalog?.skills.orEmpty()).map { it.id }.toSet(),
        loading = record.skillsRequest != null && skillCatalog?.request_id != record.skillsRequest.id,
        failed = skillCatalog?.error?.isNotBlank() == true, onChange = { ids ->
            if (pluginPreferencesLoaded) {
                visiblePluginIds = ids
                pluginStore.save(record.endpointHash, pluginHost, ids)
            }
        }, onRefresh = ::refreshSkills, onDismiss = { skillsSheet = false })


}
