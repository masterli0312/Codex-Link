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
        Text(stringResource(status), style = MaterialTheme.typography.bodySmall,
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
        if (event?.status == "input_required" && controlsEnabled && !event.attachment_pending) event.user_input?.let { input ->
            RemoteInputCard(input) { answers -> client.answerInput(id, input.id, answers, library) }
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
    var error by remember { mutableStateOf(false) }
    var unreachable by remember { mutableStateOf(false) }
    var preparationError by remember { mutableStateOf<Int?>(null) }
    var stopping by remember { mutableStateOf(false) }
    var followMode by rememberSaveable(preferenceId) { mutableStateOf("steer") }
    var followMenu by remember { mutableStateOf(false) }
    val goalTask = state?.command?.action in RemoteGoalRules.rootActions
    LaunchedEffect(goalTask) { if (goalTask) followMode = "steer" }
    var showModels by remember { mutableStateOf(false) }
    var showEfforts by remember { mutableStateOf(false) }
    var showMode by remember { mutableStateOf(false) }
    var addMenu by remember { mutableStateOf(false) }
    var permissionSheet by remember { mutableStateOf(false) }
    var modelMenu by remember { mutableStateOf(false) }
    var skillsSheet by remember { mutableStateOf(false) }
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
            try { if (running) client.followUp(id, if (desktopTurn) "steer" else followMode, prompt) else client.send(id, prompt, model, effort, mode, sendingFiles, sendingSkills); files = emptyList(); selectedSkills = emptyList(); if (text == prompt || text.isBlank()) { text = ""; preferences.updateAsync(record.endpointHash, preferenceId) { it.copy(draft = "") }.await() } }
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
        if (record.snapshot.running && !running) Text(stringResource(R.string.remote_busy),
            Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        record.followUps.filter { FollowUpState.visible(it) && (it.command.queue_id.isBlank() || it.command.action != "steer") }.takeLast(4).forEach { entry ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(entry.command.text, Modifier.weight(1f), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (FollowUpState.queued(entry) && running && !desktopTurn) Row {
                    TextButton(onClick = { if (!sending) { sending = true; scope.launch {
                        try { client.steerQueued(id, entry.command.id); error = false }
                        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                        catch (_: Exception) { error = true }
                        finally { sending = false }
                    } } }, enabled = !sending) { Text(stringResource(R.string.remote_steer_send)) }
                    TextButton(onClick = { scope.launch {
                        error = runCatching { client.followUp(id, "cancel_queue", queueId = entry.command.id) }.isFailure
                    } }, enabled = !sending) { Text(stringResource(R.string.action_cancel)) }
                }
                else if (FollowUpState.recoverable(entry)) TextButton(onClick = {
                    if (text.isBlank()) text = entry.command.text
                }, enabled = text.isBlank()) { Text(stringResource(R.string.remote_restore_draft)) }
                else Text(stringResource(if (entry.event?.status == "queued") R.string.remote_queued else R.string.remote_waiting),
                    style = MaterialTheme.typography.labelSmall)
            }
        }
        if (running && !goalTask && !desktopTurn) Box {
            TextButton(onClick = { followMenu = true }) { Text(stringResource(if (followMode == "queue") R.string.remote_queue_send else R.string.remote_steer_send)) }
            DropdownMenu(followMenu, { followMenu = false }) {
                listOf("queue", "steer").forEach { mode -> DropdownMenuItem(text = {
                    Text(stringResource(if (mode == "queue") R.string.remote_queue_send else R.string.remote_steer_send))
                }, onClick = {
                    followMode = mode; followMenu = false
                    // Choosing immediate adjustment also applies the one existing queued message.
                    val queued = record.followUps.filter { FollowUpState.queued(it) }.singleOrNull()
                    if (mode == "steer" && queued != null && !sending) { sending = true; scope.launch {
                        try { client.steerQueued(id, queued.command.id); error = false }
                        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                        catch (_: Exception) { error = true }
                        finally { sending = false }
                    } }
                }) }
            }
        }
        Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
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
                    label = { Text(skillCatalog?.skills?.firstOrNull { it.id == skill }?.name.orEmpty(), maxLines = 1) }, trailingIcon = { Icon(Icons.Outlined.Close, stringResource(R.string.remote_attachment_remove), Modifier.size(16.dp)) }) }
            }
            if (!available && (files.isNotEmpty() || selectedSkills.isNotEmpty())) Text(stringResource(R.string.remote_attachments_wait),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (mode == "plan") Text(stringResource(R.string.remote_mode_plan), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
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
                        IconButton(onClick = { addMenu = true; if (skillCatalog == null) refreshSkills() }, enabled = !sending) { Icon(Icons.Outlined.Add, stringResource(R.string.conversation_add)) }
                        DropdownMenu(addMenu, { addMenu = false }, shape = RoundedCornerShape(24.dp), modifier = Modifier.widthIn(min = 240.dp, max = 280.dp), containerColor = MaterialTheme.colorScheme.surfaceContainerLowest) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.remote_upload_photo)) }, leadingIcon = { Icon(Icons.Outlined.PhotoLibrary, null) }, enabled = canPrepare,
                                onClick = { addMenu = false; photos.launch(arrayOf("image/*")) })
                            DropdownMenuItem(text = { Text(stringResource(R.string.remote_upload_file)) }, leadingIcon = { Icon(Icons.Outlined.InsertDriveFile, null) }, enabled = canPrepare,
                                onClick = { addMenu = false; documents.launch(arrayOf("*/*")) })
                            DropdownMenuItem(text = { Text(stringResource(R.string.remote_mode_plan)) }, leadingIcon = { Icon(Icons.Outlined.Checklist, null) },
                                trailingIcon = { if (mode == "plan") Icon(Icons.Outlined.Check, null) }, enabled = canPrepare,
                                onClick = { addMenu = false; showMode = true; if (catalog?.modes.isNullOrEmpty()) refreshModels() })
                            DropdownMenuItem(text = { Text(stringResource(R.string.remote_goal_title)) }, leadingIcon = { Icon(Icons.Outlined.Flag, null) }, onClick = { addMenu = false; onGoal() })
                            DropdownMenuItem(text = { Text(stringResource(R.string.conversation_fork)) }, leadingIcon = { Icon(Icons.Outlined.ForkRight, null) },
                                enabled = available && !sending && record.snapshot.remote_ref != null,
                                onClick = { addMenu = false; onFork() })
                            HorizontalDivider(Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                            DropdownMenuItem(text = { Column {
                                Text(stringResource(R.string.remote_skills_title))
                                Text(stringResource(R.string.remote_skills_source), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            } }, leadingIcon = { Icon(Icons.Outlined.Extension, null) }, enabled = canPrepare,
                                onClick = { addMenu = false; skillsSheet = true; refreshSkills() })
                        }
                    }
                    IconButton(onClick = { permissionSheet = true }, enabled = !sending) {
                        Icon(permissionIcon(record.selectedPermission), stringResource(R.string.remote_permission_title),
                            tint = if (record.selectedPermission == "full") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.weight(1f))
                Box {
                    TextButton(onClick = { modelMenu = true }, enabled = canPrepare, contentPadding = PaddingValues(horizontal = 6.dp)) {
                        Text((options.firstOrNull { it.model == model }?.name ?: model.ifBlank { stringResource(R.string.remote_model) }),
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 100.dp), style = MaterialTheme.typography.labelMedium)
                        if (effort.isNotBlank()) Text(" " + effortLabel(effort), style = MaterialTheme.typography.labelMedium)
                    }
                    DropdownMenu(modelMenu, { modelMenu = false }, shape = RoundedCornerShape(24.dp)) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.remote_model)) }, onClick = { modelMenu = false; showModels = true; if (catalog == null || catalog.error.isNotBlank()) refreshModels() })
                        DropdownMenuItem(text = { Text(stringResource(R.string.remote_effort)) }, onClick = { modelMenu = false; showEfforts = true; if (catalog == null) refreshModels() })
                    }
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
    if (skillsSheet) RemoteSkillsSheet(record, selectedSkills, canPrepare, { selectedSkills = it }, ::refreshSkills) { skillsSheet = false }
    if (showModels) RemoteModelPicker(options, model, modelLoading,
        modelError || modelExpired && catalog?.request_id != record.modelRequest?.id || catalog?.error?.isNotBlank() == true,
        ::refreshModels, { selected -> selectOptions(selected, options.firstOrNull { it.model == selected }?.default_effort.orEmpty()); showModels = false }, { showModels = false })
    if (showEfforts) RemoteEffortPicker(chosen?.efforts.orEmpty(), effort,
        { selected -> selectOptions(model, selected); showEfforts = false }, { showEfforts = false })
    if (showMode) RemoteModePicker(catalog?.modes.orEmpty(), mode, modelLoading, ::refreshModels, { selected ->
        mode = selected; showMode = false
        scope.launch { withContext(Dispatchers.IO) { TaskInbox(context).setOptions(id, model, effort, selected) } }
    }, { showMode = false })
}
