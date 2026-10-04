package com.codex.quota.ui.feature.taskhistory

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.testTag
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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
    var permissionSheet by remember { mutableStateOf(false) }
    var modelMenu by remember { mutableStateOf(false) }
    var loaded by remember(draftScope) { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    var unreachable by remember { mutableStateOf(false) }
    var busyError by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var showModels by remember { mutableStateOf(false) }
    var showEfforts by remember { mutableStateOf(false) }
    var showMode by remember { mutableStateOf(false) }
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
            onProject(saved.project)
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
    val label = stringResource(R.string.conversation_ask)
    Column(Modifier.fillMaxWidth().imePadding().padding(horizontal = 20.dp).padding(top = 12.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        environmentSelector(!sending)
        if (error) Text(stringResource(if (busyError) R.string.remote_busy else if (unreachable) R.string.computer_unreachable else R.string.remote_network_error), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        voiceError?.let { Text(stringResource(R.string.remote_voice_error, it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        if (listening) Text(stringResource(voiceStage), style = MaterialTheme.typography.bodySmall)
        Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerLowest) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp)) {
                BasicTextField(text, { if (it.toByteArray().size <= 16_384) text = it },
                    Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 10.dp, vertical = 12.dp)
                        .testTag("new-conversation-input").semantics { contentDescription = label },
                    enabled = !sending && loaded, maxLines = 5,
                    interactionSource = inputInteraction,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface), decorationBox = { field ->
                        Box { if (text.isEmpty()) Text(label, style = MaterialTheme.typography.bodyLarge, maxLines = 1,
                            color = MaterialTheme.colorScheme.onSurfaceVariant); field() }
                    })
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box {
                        IconButton(onClick = { menu = true }, enabled = enabled && !sending) {
                            Icon(Icons.Outlined.Add, stringResource(R.string.conversation_actions), Modifier.size(26.dp))
                        }
                        DropdownMenu(menu, { menu = false }, shape = RoundedCornerShape(24.dp)) {
                            DropdownMenuItem(text = { Text(modeLabel(mode)) }, onClick = { menu = false; showMode = true; onLoadModels() })
                        }
                    }
                    IconButton(onClick = { permissionSheet = true }, enabled = enabled && !sending) {
                        Icon(permissionIcon(permission), stringResource(R.string.remote_permission_title), Modifier.size(22.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.weight(1f))
                    Box {
                        TextButton(onClick = { modelMenu = true }, enabled = enabled && !sending,
                            contentPadding = PaddingValues(horizontal = 4.dp)) {
                            Text((options.firstOrNull { it.model == model }?.name ?: model.ifBlank { stringResource(R.string.remote_model) })
                                .substringAfterLast('/').removePrefix("GPT-").removePrefix("gpt-").replace("-"," "),
                                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 150.dp),
                                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
                            if (effort.isNotBlank()) Text(" " + effortLabel(effort), style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurface)
                        }
                        DropdownMenu(modelMenu, { modelMenu = false }, shape = RoundedCornerShape(24.dp)) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.remote_model)) },
                                onClick = { modelMenu = false; showModels = true; if (catalog == null) onLoadModels() })
                            DropdownMenuItem(text = { Text(stringResource(R.string.remote_effort)) },
                                onClick = { modelMenu = false; showEfforts = true; if (catalog == null) onLoadModels() })
                        }
                    }
                    VoiceInputButton(enabled = enabled && !sending, onStage = { voiceStage = it },
                        onListening = { listening = it; if (it) voiceError = null }, onError = { voiceError = it }, onResult = {
                            val draft = listOf(text.trimEnd(), it.trim()).filter { s -> s.isNotBlank() }.joinToString(" ")
                            if (draft.toByteArray().size <= 16_384) text = draft else voiceError = -1
                        })
                    if (text.isNotBlank()) FilledIconButton(onClick = {
                        if (sending) return@FilledIconButton
                        val source = anchor ?: return@FilledIconButton
                        val prompt = text; sending = true; error = false; unreachable = false; busyError = false
                        scope.launch {
                            try {
                                val id = RemoteConversationClient(context).create(source.id, project, prompt, model, effort, mode, permission)
                                if (text == prompt) { text = ""; preferences.updateAsync(source.endpointHash, draftId) { it.copy(draft = "") }.await() }
                                onOpen(id)
                            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                            catch (e: Exception) { error = true; unreachable = e.message == "COMPUTER_UNREACHABLE"; busyError = e.message == "REMOTE_BUSY" }
                            finally { sending = false }
                        }
                    }, enabled = enabled && !sending && !listening && ConversationEnvironmentRules.validProject(project), shape = CircleShape,
                        modifier = Modifier.testTag("new-conversation-send"),
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.onSurface,
                            contentColor = MaterialTheme.colorScheme.surface)) {
                        if (sending) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.surface)
                        else Icon(Icons.Default.ArrowUpward, stringResource(R.string.remote_send))
                    }
                }
            }
        }
    }
    if (permissionSheet) RemotePermissionSheet(permission, enabled && !sending, { permission = it; permissionSheet = false }, { permissionSheet = false })
    if (showModels) RemoteModelPicker(options, model, false, catalog?.error?.isNotBlank() == true, onLoadModels,
        { model = it; effort = options.firstOrNull { m -> m.model == it }?.default_effort.orEmpty(); showModels = false }, { showModels = false })
    if (showEfforts) RemoteEffortPicker(options.firstOrNull { it.model == model }?.efforts.orEmpty(), effort,
        { effort = it; showEfforts = false }, { showEfforts = false })
    if (showMode) RemoteModePicker(catalog?.modes.orEmpty(), mode,
        anchor?.modelRequest != null && catalog?.request_id != anchor.modelRequest.id,
        onLoadModels, { mode = it; showMode = false }, { showMode = false })
}
