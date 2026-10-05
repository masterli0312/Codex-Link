package com.codex.quota.ui.feature.taskhistory

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.testTag
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.listSaver
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.outlined.Close
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.codex.quota.R
import com.codex.quota.notifications.task.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun ConversationStartComposer(anchor: TaskInboxRecord?, records: List<TaskInboxRecord>, enabled: Boolean,
    project: String, onProject: (String) -> Unit, onLoadModels: () -> Unit, onOpen: (String) -> Unit,
    environmentSelector: @Composable (Boolean) -> Unit = {}, onInputFocus: (Boolean)->Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val host = anchor?.snapshot?.remote_ref?.host_id ?: anchor?.computerHostId.orEmpty()
    val draftScope = anchor?.endpointHash.orEmpty() + ":" + host
    val preferences = remember { ConversationPreferenceStore(context.applicationContext) }
    val draftId = "new-conversation:" + host
    val inputInteraction = remember { MutableInteractionSource() }
    val inputFocused by inputInteraction.collectIsFocusedAsState()
    val latestOnInputFocus by rememberUpdatedState(onInputFocus)
    LaunchedEffect(inputFocused) { latestOnInputFocus(inputFocused) }
    var text by rememberSaveable(draftScope) { mutableStateOf("") }
    var model by rememberSaveable(draftScope) { mutableStateOf("") }
    var effort by rememberSaveable(draftScope) { mutableStateOf("") }
    var mode by rememberSaveable(draftScope) { mutableStateOf("") }
    var permission by rememberSaveable(draftScope) { mutableStateOf("default") }
    var addMenu by remember { mutableStateOf(false) }
    var goalMode by rememberSaveable(draftScope) { mutableStateOf(false) }
    var attachmentError by remember { mutableStateOf<String?>(null) }
    val fileSaver = remember { listSaver<List<SelectedRemoteFile>, String>(
        save = { it.flatMap { f -> listOf(f.uri.toString(), f.name, f.mime) } },
        restore = { it.chunked(3).mapNotNull { f -> if (f.size == 3) SelectedRemoteFile(android.net.Uri.parse(f[0]), f[1], f[2]) else null } }) }
    var files by rememberSaveable(draftScope, stateSaver = fileSaver) { mutableStateOf<List<SelectedRemoteFile>>(emptyList()) }
    fun picked(uris: List<android.net.Uri>, photo: Boolean) {
        if (uris.isEmpty()) return
        scope.launch {
            try {
                val selected = withContext(Dispatchers.IO) { uris.distinct().map { RemoteAttachmentUpload.select(context, it, photo) } }
                val merged = SelectedAttachmentRules.merge(files, selected) { it.uri }
                withContext(Dispatchers.IO) { selected.forEach { f -> runCatching { context.contentResolver.takePersistableUriPermission(f.uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) } } }
                files = merged; attachmentError = null
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (e: Exception) { attachmentError = e.message ?: "ATTACHMENT_INVALID" }
        }
    }
    val photos = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { picked(it, true) }
    val documents = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { picked(it, false) }
    var loaded by remember(draftScope) { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    var unreachable by remember { mutableStateOf(false) }
    var busyError by remember { mutableStateOf(false) }

    var voiceError by remember { mutableStateOf<Int?>(null) }
    var listening by remember { mutableStateOf(false) }
    var voiceStage by remember { mutableIntStateOf(R.string.remote_listening) }
    val catalog = anchor?.modelCatalog
    val options = catalog?.models.orEmpty()
    LaunchedEffect(draftScope) {
        if (anchor != null) {
            val saved = preferences.readAsync(anchor.endpointHash, draftId).await()
            val defaults = withContext(Dispatchers.Default) { ConversationDefaults.options(records, anchor.endpointHash, host) }
            text = saved.draft
            model = saved.model.ifBlank { defaults.first.ifBlank { catalog?.model.orEmpty() } }
            effort = saved.effort.ifBlank { defaults.second.ifBlank { catalog?.effort.orEmpty() } }
            mode = saved.mode
            permission = saved.permission
            onProject(ConversationEnvironmentRules.NO_PROJECT)
            loaded = true
        }
    }
    LaunchedEffect(loaded, catalog?.id) {
        if (loaded && catalog != null) {
            val resolved = ConversationDefaults.explicit(options, catalog.model, catalog.effort, model, effort, mode, catalog.modes)
            model = resolved.first; effort = resolved.second; mode = resolved.third
        }
    }
    LaunchedEffect(text, model, effort, mode, permission, project, loaded) {
        if (loaded && anchor != null) {
            kotlinx.coroutines.delay(300)
            val saved = ConversationPreferences(draft = text, model = model, effort = effort, mode = mode, permission = permission, project = project)
            preferences.updateAsync(anchor.endpointHash, draftId) { it.copy(draft = saved.draft, model = saved.model, effort = saved.effort, mode = saved.mode, permission = saved.permission, project = saved.project) }.await()
        }
    }
    val latestDraft by rememberUpdatedState(ConversationPreferences(draft = text, model = model, effort = effort, mode = mode, permission = permission, project = project))
    val latestLoaded by rememberUpdatedState(loaded)
    DisposableEffect(draftScope) { onDispose {
        if (latestLoaded && anchor != null) {
            val saved = latestDraft
            preferences.updateAsync(anchor.endpointHash, draftId) { it.copy(draft = saved.draft, model = saved.model, effort = saved.effort, mode = saved.mode, permission = saved.permission, project = saved.project) }
        }
    } }
    fun submit() {
        if (!enabled || sending || listening || !loaded || text.isBlank() && files.isEmpty()) return
        val source = anchor ?: return
        val prompt = text.ifBlank { context.getString(R.string.remote_attachment_prompt) }; val sendingFiles = files; sending = true; error = false; unreachable = false; busyError = false
        scope.launch {
            try {
                val id = RemoteConversationClient(context).create(source.id, ConversationEnvironmentRules.NO_PROJECT, prompt, model, effort, mode, permission, sendingFiles, objective = if (goalMode) prompt else "")
                files = emptyList()
                if (text == prompt || text.isBlank()) { text = ""; preferences.updateAsync(source.endpointHash, draftId) { it.copy(draft = "") }.await() }
                onOpen(id)
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (e: Exception) { error = true; unreachable = e.message == "COMPUTER_UNREACHABLE"; busyError = e.message == "REMOTE_BUSY" }
            finally { sending = false }
        }
    }
    val label = stringResource(R.string.conversation_ask)
    Column(Modifier.fillMaxWidth().imePadding().padding(horizontal = 20.dp).padding(top = 12.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        environmentSelector(!sending)
        if (error) Text(stringResource(if (busyError) R.string.remote_busy else if (unreachable) R.string.computer_unreachable else R.string.remote_network_error), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        voiceError?.let { Text(stringResource(R.string.remote_voice_error, it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        attachmentError?.let { Text(stringResource(R.string.remote_attachment_failed), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        if (listening) Text(stringResource(voiceStage), style = MaterialTheme.typography.bodySmall)
        Surface(shape = RoundedCornerShape(com.codex.quota.ui.theme.UiMetrics.ComposerRadius), color = MaterialTheme.colorScheme.surface,
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                if (mode == "plan" || goalMode) ComposerModeTag(goalMode, !sending) { mode = "default"; goalMode = false }
                if (files.isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    files.forEach { f -> key(f.uri.toString()) { SelectedAttachmentPreview(f, !sending) { files = files - f } } }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box {
                        IconButton(onClick = { addMenu = true; if (catalog == null) onLoadModels() }, enabled = enabled && !sending) {
                            Icon(Icons.Outlined.Add, stringResource(R.string.conversation_actions), Modifier.size(24.dp))
                        }
                        DropdownMenu(addMenu, { addMenu = false }, shape = RoundedCornerShape(24.dp), modifier = Modifier.width(240.dp), containerColor = MaterialTheme.colorScheme.surfaceContainerLowest) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.remote_upload_photo)) }, leadingIcon = { Icon(ComposerIcons.Photo, null, Modifier.size(20.dp)) },
                                enabled = !goalMode, onClick = { addMenu = false; photos.launch(arrayOf("image/*")) })
                            DropdownMenuItem(text = { Text(stringResource(R.string.remote_upload_file)) }, leadingIcon = { Icon(ComposerIcons.File, null, Modifier.size(20.dp)) },
                                enabled = !goalMode, onClick = { addMenu = false; documents.launch(arrayOf("*/*")) })
                            DropdownMenuItem(text = { Text(stringResource(R.string.remote_mode_plan)) }, leadingIcon = { Icon(ComposerIcons.Plan, null, Modifier.size(20.dp)) },
                                trailingIcon = { if (mode == "plan") Icon(Icons.Outlined.Check, null) }, enabled = "plan" in catalog?.modes.orEmpty(),
                                onClick = { addMenu = false; mode = if (mode == "plan") "default" else "plan"; goalMode = false })
                            DropdownMenuItem(text = { Text(stringResource(R.string.remote_goal_title)) }, leadingIcon = { Icon(ComposerIcons.Goal, null, Modifier.size(20.dp)) },
                                trailingIcon = { if (goalMode) Icon(Icons.Outlined.Check, null) }, enabled = files.isEmpty(),
                                onClick = { addMenu = false; goalMode = !goalMode; mode = "default" })
                        }
                    }
                BasicTextField(text, { if (it.toByteArray().size <= 16_384) text = it },
                    Modifier.weight(1f).heightIn(min = 48.dp).padding(horizontal = 8.dp, vertical = 12.dp)
                        .testTag("new-conversation-input").semantics { contentDescription = label },
                    enabled = !sending && loaded, maxLines = 5,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { submit() }),
                    interactionSource = inputInteraction,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface), decorationBox = { field ->
                        Box { if (text.isEmpty()) Text(label, style = MaterialTheme.typography.bodyLarge, maxLines = 1,
                            color = MaterialTheme.colorScheme.onSurfaceVariant); field() }
                    })
                    VoiceInputButton(enabled = enabled && !sending, onStage = { voiceStage = it },
                        onListening = { listening = it; if (it) voiceError = null }, onError = { voiceError = it }, onResult = {
                            val draft = listOf(text.trimEnd(), it.trim()).filter { s -> s.isNotBlank() }.joinToString(" ")
                            if (draft.toByteArray().size <= 16_384) text = draft else voiceError = -1
                        })
                    if (text.isNotBlank() || files.isNotEmpty()) FilledIconButton(onClick = ::submit,
                        enabled = enabled && !sending && !listening && loaded, modifier = Modifier.testTag("new-conversation-send"),
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.onSurface,
                            contentColor = MaterialTheme.colorScheme.surface)) {
                        Icon(Icons.Default.ArrowUpward, stringResource(R.string.remote_send))
                    }
                }
            }
        }
    }
}
