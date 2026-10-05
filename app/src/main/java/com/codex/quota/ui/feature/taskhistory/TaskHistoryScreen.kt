package com.codex.quota.ui.feature.taskhistory

import androidx.compose.foundation.clickable
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.automirrored.outlined.CallSplit
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.codex.quota.R
import com.codex.quota.notifications.task.*
import com.codex.quota.ui.components.rememberConversationClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collectLatest
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationListScreen(onSettings: () -> Unit, onOpen: (String) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val listViewModel: ConversationListViewModel = com.codex.quota.ui.util.scopedViewModel {
        ConversationListViewModel(context.applicationContext)
    }
    var cloudSelected by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var cloudNew by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    if (cloudSelected) {
        CloudConversationScreen(initialNew = cloudNew, onLocal = { cloudSelected = false })
        return
    }
    val inbox = remember { TaskInbox(context) }
    val records by listViewModel.records.collectAsStateWithLifecycle()
    var menu by remember { mutableStateOf(false) }
    var searching by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var query by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    var showArchived by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val store = remember { TaskNotificationStore(context) }
    val settings by store.settings.collectAsStateWithLifecycle(initialValue = remember(store) { store.read() })
    var anchorId by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    val computerStore = remember { ComputerConnectionStore(context) }
    val computers by listViewModel.computers.collectAsStateWithLifecycle()
    val syncSelections by listViewModel.syncSelections.collectAsStateWithLifecycle()
    var selectedComputerId by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    val selectedComputer = computers.firstOrNull { it.id == selectedComputerId }
    val syncSelection = selectedComputer?.let { syncSelections?.get(ConversationSyncRules.scope(it)) }
    var selectingSync by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var savingSync by remember { mutableStateOf(false) }
    var syncSelectionError by remember { mutableStateOf(false) }
    var computerMenu by remember { mutableStateOf(false) }
    val selectedProject = ConversationEnvironmentRules.NO_PROJECT
    var newConversation by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var inputFocused by remember { mutableStateOf(false) }
    val composing = newConversation || inputFocused
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val projectFilter = ""
    var computerOnly by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var syncError by remember { mutableStateOf(false) }
    var manualRequestId by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    var openingThread by remember { mutableStateOf(false) }
    val anchor = records.orEmpty().firstOrNull { it.id == anchorId }
    val selectedAnchor = anchor?.takeIf { selectedComputer?.matches(it) == true && it.libraryAnchor }
    val remoteEnabled = settings.enabled && settings.contentEnabled && settings.remoteEnabled
    LaunchedEffect(records, selectedComputerId, settings.endpoint) {
        val computer = selectedComputer ?: return@LaunchedEffect
        if (anchor == null || !computer.matches(anchor) || !anchor.libraryAnchor) {
            anchorId = records.orEmpty().firstOrNull { computer.matches(it) && it.libraryAnchor }?.id
                ?: if (computer.hostId.isNotBlank()) RemoteConversationClient(context).anchor(computer) else null
        }
    }
    LaunchedEffect(anchorId, remoteEnabled) { if (remoteEnabled && anchorId != null) RemoteConversationClient(context).listen(anchorId!!) }
    val result = anchor?.libraryResult
    val queryRequest = anchor?.libraryRequest
    val resultMatches = result?.request_id == queryRequest?.id && result != null
    var queryExpired by remember(queryRequest?.id) { mutableStateOf(false) }
    LaunchedEffect(queryRequest?.id) {
        queryRequest?.let { kotlinx.coroutines.delay((it.issued_at + 45_000 - System.currentTimeMillis()).coerceAtLeast(0)); queryExpired = true }
    }
    val creating = queryRequest?.action == "create" && (!resultMatches || result?.status !in setOf("completed", "interrupted", "failed"))
    val syncing = queryRequest?.action in setOf("threads", "read") && !resultMatches && !queryExpired
    val queryDelayed = queryRequest != null && !resultMatches && queryExpired
    fun query(action: String, thread: String = "", project: String = "", prompt: String = "", model: String = "", effort: String = "", cursor: String = "", manual: Boolean = true) {
        val target = anchorId ?: return
        if (manual) syncError = false
        scope.launch {
            try {
                val requestId = RemoteConversationClient(context).library(target, action, thread, project, prompt, model, effort, cursor,
                    archived = showArchived, search = if (action == "threads") query.trim().take(80) else "")
                if (manual) manualRequestId = requestId
            }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { if (manual) syncError = true }
        }
    }
    // Opening is owned by a current row click or the new-thread composer. Restored
    // catalog/read results update the cache; they must never navigate on their own.
    fun startConversation() { newConversation = true; showArchived = false; searching = false; query = "" }
    fun leaveComposer() { focusManager.clearFocus(force = true); keyboard?.hide(); inputFocused = false; newConversation = false }
    BackHandler(composing) { leaveComposer() }
    BackHandler(!composing) { onBack() }
    fun loadModels() { anchorId?.let { target -> scope.launch { runCatching { RemoteConversationClient(context).models(target) } } } }
    LaunchedEffect(anchorId, remoteEnabled) {
        if (remoteEnabled && RemoteCatalogRules.shouldLoadModels(selectedAnchor, System.currentTimeMillis())) loadModels()
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    val refreshList by rememberUpdatedState({
        if ((selectingSync || !syncSelection?.ids.isNullOrEmpty()) && RemoteCatalogRules.shouldRefresh(selectedAnchor, showArchived, query.trim().take(80), System.currentTimeMillis()))
            query("threads", manual = false)
    })
    LaunchedEffect(anchorId, remoteEnabled, lifecycleOwner) {
        if (remoteEnabled && anchorId != null) lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            refreshList()
            // Refresh an old catalog quietly. Returning to this screen is not a forced reload.
            while (true) { kotlinx.coroutines.delay(60_000); refreshList() }
        }
    }
    LaunchedEffect(selectedComputer?.id, selectedComputer?.hostId, remoteEnabled, lifecycleOwner) {
        val computer = selectedComputer ?: return@LaunchedEffect
        if (remoteEnabled) lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            val client = ComputerConnectionClient(context)
            while (true) {
                client.probe(computer, force = false)
                kotlinx.coroutines.delay(20_000)
            }
        }
    }
    val now = rememberConversationClock()
    LaunchedEffect(computers) {
        if (computers.none { it.id == selectedComputerId }) selectedComputerId = computers.firstOrNull()?.id.orEmpty()
    }
    val catalog = if (remoteEnabled) syncSelection?.conversations.orEmpty().filter { it.archived == showArchived }.map { it.thread } else emptyList()
    val syncedRecords = remember(records, computers, syncSelections) { records?.filter { record -> computers.any { computer ->
        syncSelections?.get(ConversationSyncRules.scope(computer))?.let { ConversationSyncRules.allows(record, computer, it) } == true
    } } }
    val selectionKey = remember(syncSelections) { syncSelections?.mapValues { (_, selection) ->
        selection.conversations.map { it.thread.thread_id to it.archived }.sortedBy { it.first }
    }?.hashCode().toString() }
    val presentationKey = listOf(selectedComputerId, anchor?.endpointHash.orEmpty(), query, showArchived.toString(), computerOnly.toString(), projectFilter, selectionKey)
    val presentation by key(presentationKey) {
        produceState(listViewModel.presentationFor(presentationKey), syncedRecords, catalog, anchor?.endpointHash,
            query, showArchived, selectedComputer?.id, computerOnly, projectFilter) {
            val built = withContext(Dispatchers.Default) {
                ConversationListPresentation.build(syncedRecords.orEmpty(), catalog, anchor?.endpointHash, query, showArchived,
                    selectedComputer, computerOnly, projectFilter)
            }
            value = built
            if (records != null && syncSelections != null) listViewModel.rememberPresentation(presentationKey, built)
        }
    }
    val manualSyncing = syncing && queryRequest?.id == manualRequestId
    val manualSyncFailed = manualRequestId.isNotBlank() && queryRequest?.id == manualRequestId &&
        (queryDelayed || resultMatches && (result?.error?.isNotBlank() == true || result?.partial == true))
    val rows = presentation.rows
    var revealedRow by remember(selectedComputerId, showArchived, query, projectFilter) { mutableStateOf<String?>(null) }
    var renameRow by remember(selectedComputerId) { mutableStateOf<DesktopRowTarget?>(null) }
    var pendingRow by remember { mutableStateOf<String?>(null) }
    var rowError by remember(selectedComputerId) { mutableStateOf<Int?>(null) }
    fun rowAction(key: String, target: DesktopRowTarget, action: String, name: String = "") {
        if (pendingRow != null) return
        pendingRow = key
        rowError = null
        scope.launch {
            try {
                desktopRowAction(context, target, action, name)
                if (action != "pin") query("threads", manual = false)
            }
            catch (_: kotlinx.coroutines.TimeoutCancellationException) { rowError = R.string.conversation_management_uncertain }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (error: Exception) { rowError = when (error.message) {
                "BUSY" -> R.string.remote_busy
                "LIBRARY_BUSY" -> R.string.conversation_management_busy
                "NO_ARCHIVABLE_RECORD" -> R.string.conversation_management_empty
                "ARCHIVE_RECORD_MISSING", "MISSING_THREAD" -> R.string.conversation_management_missing
                "MANAGEMENT_UNCONFIRMED" -> R.string.conversation_management_uncertain
                else -> R.string.conversation_management_failed
            } }
            finally { pendingRow = null }
        }
    }
    fun chooseSync() { leaveComposer(); showArchived = false; searching = false; query = ""; selectingSync = true; syncSelectionError = false }
    if (selectingSync && selectedComputer != null && syncSelection != null) {
        val available = anchor?.threadCatalog?.takeIf { anchor.threadCatalogArchived == false }.orEmpty()
        ConversationSyncPicker(ConversationSyncRules.scope(selectedComputer), selectedComputer.name, syncSelection, available,
            syncing, savingSync, syncSelectionError || manualSyncFailed,
            more = anchor?.threadCatalogArchived == false && !anchor.threadCursor.isNullOrBlank(),
            onSearch = { term -> query = term; query("threads") }, onMore = { query("threads", cursor = anchor?.threadCursor.orEmpty()) },
            onSave = { ids, known ->
                savingSync = true; syncSelectionError = false
                scope.launch {
                    try { listViewModel.saveSyncSelection(selectedComputer, ids, known); selectingSync = false; query = "" }
                    catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (_: Exception) { syncSelectionError = true }
                    finally { savingSync = false }
                }
            }, onBack = { selectingSync = false; query = "" })
        return
    }
    val pageColor = MaterialTheme.colorScheme.background
    val buttonColor = MaterialTheme.colorScheme.surface
    Scaffold(containerColor = pageColor, topBar = {
        CenterAlignedTopAppBar(title = { TextButton(onClick = { menu = true }) {
                Text(stringResource(if (showArchived) R.string.conversation_archived else R.string.conversation_codex), style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
                if (manualSyncing) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else Icon(Icons.Outlined.KeyboardArrowDown, null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(18.dp))
            } },
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = pageColor),
            navigationIcon = { IconButton(onClick = { if (composing) leaveComposer() else onBack() }, modifier = Modifier.size(48.dp).background(buttonColor, CircleShape)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back), Modifier.size(24.dp))
            } }, actions = {
            IconButton(onClick = {
                if (composing) { leaveComposer(); searching = true; query = "" }
                else { searching = !searching; if (!searching) query = "" }
            }, modifier = Modifier.size(48.dp).background(buttonColor, CircleShape)) {
                Icon(Icons.Outlined.Search, stringResource(R.string.conversation_search), Modifier.size(24.dp))
            }
        })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            if (!composing) {
            rowError?.let { message -> Text(stringResource(message), Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            if (syncError || manualSyncFailed) Text(stringResource(R.string.conversation_catalog_sync_failed),
                Modifier.padding(horizontal = 20.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            if (searching) OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true,
                placeholder = { Text(stringResource(R.string.conversation_search)) }, shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
                trailingIcon = { IconButton(onClick = { query("threads") }, enabled = remoteEnabled && anchor != null && !creating && !syncing) {
                    Icon(Icons.Outlined.Search, stringResource(R.string.conversation_search_computer))
                } },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ConversationFilter(selected = !computerOnly, onClick = { computerOnly = false }) {
                    Text(stringResource(R.string.conversation_all), fontSize = 14.sp)
                }
                ConversationFilter(selected = false, onClick = { cloudNew = false; cloudSelected = true }) {
                    Icon(Icons.Outlined.CloudQueue, null, Modifier.size(20.dp))
                    Text(stringResource(R.string.conversation_cloud), fontSize = 14.sp)
                }
                if (remoteEnabled && selectedComputer != null) Box(Modifier.weight(1f)) {
                ConversationFilter(selected = computerOnly, onClick = {
                    computerOnly = true
                    if (computers.size > 1) computerMenu = true else scope.launch { ComputerConnectionClient(context).probe(selectedComputer) }
                }) {
                        Box(Modifier.size(6.dp).background(when {
                            selectedComputer.recentlyReachable(now) -> Color(0xFF22C55E)
                            selectedComputer.confirmedUnreachable(now) -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                        }, CircleShape))
                        Icon(Icons.Outlined.Computer, null, Modifier.size(20.dp))
                        Text(selectedComputer.name.ifBlank { anchor?.modelCatalog?.host_name.orEmpty() }.ifBlank { stringResource(R.string.conversation_paired_computer) },
                            maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    }
                DropdownMenu(computerMenu, { computerMenu = false }) {
                    computers.forEach { computer -> DropdownMenuItem(text = { Text(computer.name.ifBlank { stringResource(R.string.conversation_paired_computer) }) },
                        onClick = {
                            selectedComputerId = computer.id; computerMenu = false
                            scope.launch { ComputerConnectionClient(context).probe(computer) }
                        }) }
                }
            }
            }
            if (selectedComputer?.confirmedUnreachable(now) == true) Text(stringResource(R.string.computer_unreachable),
                Modifier.padding(horizontal = 20.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (remoteEnabled && selectedComputer != null) TextButton(onClick = ::chooseSync, modifier = Modifier.padding(horizontal = 12.dp)) {
                Icon(Icons.Outlined.Checklist, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.conversation_sync))
            }
            LazyColumn(Modifier.weight(1f), state = listState, contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp)) {
                if (records == null) item { Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } }
                else if (records.isNullOrEmpty()) item {
                    Column(Modifier.fillMaxWidth().padding(vertical = 56.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.AutoMirrored.Outlined.Chat, null, Modifier.size(44.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(18.dp))
                        Text(stringResource(R.string.task_history_empty), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.task_history_empty_detail), Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.bodyMedium)
                        OutlinedButton(onClick = onSettings) { Text(stringResource(R.string.remote_configure)) }
                    }
                } else {
                    item { Text(stringResource(R.string.conversation_recent), Modifier.padding(top = 8.dp, bottom = 12.dp),
                        fontSize = 18.sp, fontWeight = FontWeight.SemiBold) }
                    if (!presentation.hasResults) item { Text(stringResource(if (query.isBlank() && !showArchived) R.string.conversation_sync_empty else R.string.conversation_no_results), Modifier.padding(vertical = 24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                items(rows, key = { it.key }, contentType = { "conversation" }) { row ->
                    val record = row.record
                    val thread = row.thread
                    val target = DesktopRowTarget(record, thread, selectedComputer, showArchived)
                    val canManageRow = remoteEnabled && pendingRow == null && (record?.snapshot?.thread_ref != null || record?.snapshot?.remote_ref != null || thread != null && selectedComputer != null)
                    ConversationSwipeRow(revealedRow == row.key, { revealedRow = row.key }, { if (revealedRow == row.key) revealedRow = null },
                        pinned = record?.pinned == true, archived = showArchived, enabled = !creating && pendingRow == null,
                        canManage = canManageRow, onPin = { rowAction(row.key, target, "pin") }, onRename = { renameRow = target },
                        onArchive = { rowAction(row.key, target, if (showArchived) "unarchive" else "archive") }) {
                    // A background list refresh must not disable opening a conversation.
                    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(enabled = !openingThread && (record != null || !creating)) {
                        if (openingThread) return@clickable
                        // The provider's row identifies the requested thread even if an
                        // older cached record happens to carry the same displayed title.
                        val remoteThread = thread
                        val computer = selectedComputer
                        if (remoteThread != null && computer != null && remoteEnabled && !creating) {
                            openingThread = true
                            scope.launch {
                                try { onOpen(RemoteConversationClient(context).openThread(computer, remoteThread, showArchived)) }
                                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                                catch (_: Exception) { syncError = true }
                                finally { openingThread = false }
                            }
                        } else if (record != null) {
                            openingThread = true
                            onOpen(record.id)
                        }
                    }.padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text((record?.snapshot?.title ?: thread!!.title).ifBlank { context.getString(R.string.task_history_unnamed) }, style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (record?.pinned == true) Icon(Icons.Outlined.PushPin, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        val ref = record?.snapshot?.thread_ref ?: record?.snapshot?.remote_ref
                        val selected = syncSelections?.values?.flatMap { it.conversations }?.firstOrNull {
                            it.thread.thread_id == (thread?.thread_id ?: ref?.thread_id)
                        }
                        val running = selected?.thread?.running ?: (thread?.running == true || record?.let(ConversationIndex::hasPendingTurn) == true)
                        ConversationActivityIndicator(running, selected?.unread == true)
                        val updatedAt = maxOf(row.updatedAt, selected?.completedAt ?: 0)
                        if (!running) Text(conversationTime(updatedAt, now), style = MaterialTheme.typography.labelMedium, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 96.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    }
                }
            }
            } else Spacer(Modifier.weight(1f))
            if (!showArchived) key(selectedComputerId) { ConversationStartComposer(selectedAnchor, records.orEmpty(), remoteEnabled && selectedAnchor != null, selectedProject,
                {}, ::loadModels, { leaveComposer(); onOpen(it) }, environmentSelector = { idle ->
                    if (composing) ConversationEnvironmentSelector(computers, selectedComputer, selectedAnchor?.modelCatalog?.host_name.orEmpty(),
                        emptyList(), selectedProject, idle, now,
                        onComputer = { computer ->
                            if (computer.id != selectedComputerId) selectedComputerId = computer.id
                            scope.launch { ComputerConnectionClient(context).probe(computer) }
                        }, onProject = {}, onCloud = { leaveComposer(); cloudNew = true; cloudSelected = true })
                }, onInputFocus = { focused -> inputFocused = focused; if (focused && !newConversation) startConversation() }) }
        }
    }
    renameRow?.let { target -> ConversationRenameDialog((target.record?.snapshot?.title ?: target.thread?.title).orEmpty(),
        onRename = { name -> renameRow = null; rowAction(target.record?.id ?: target.thread?.thread_id.orEmpty(), target, "rename", name) },
        onDismiss = { renameRow = null }) }
    if (menu) ConversationMenuSheet(
        showArchived = showArchived,
        canSync = remoteEnabled && selectedComputer != null && !creating && !savingSync,
        onDismiss = { menu = false },
        onSync = { menu = false; chooseSync() },
        onArchive = { menu = false; leaveComposer(); showArchived = !showArchived; query("threads") },
        onSettings = { menu = false; onSettings() }
    )

}

@Composable
private fun ConversationFilter(selected: Boolean, onClick: () -> Unit, content: @Composable RowScope.() -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(24.dp),
        color = if (selected) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent) {
        Row(Modifier.heightIn(min = 40.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp), content = content)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationScreen(id: String, onOpen: (String) -> Unit = {}, onBack: () -> Unit) {
    val context = LocalContext.current
    val inbox = remember { TaskInbox(context) }
    val settingsStore = remember { TaskNotificationStore(context) }
    val settings by settingsStore.settings.collectAsStateWithLifecycle(initialValue = remember(settingsStore) { settingsStore.read() })
    val computerStore = remember { ComputerConnectionStore(context) }
    val computerRevision by ComputerConnectionStore.changes.collectAsStateWithLifecycle()
    val client = remember { RemoteConversationClient(context) }
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    var record by remember(id) { mutableStateOf<TaskInboxRecord?>(null) }
    var loaded by remember(id) { mutableStateOf(false) }
    var pairedComputer by remember(id) { mutableStateOf<ComputerConnection?>(null) }
    var pairingResolved by remember(id) { mutableStateOf(false) }
    val openedAt = remember(id) { System.currentTimeMillis() }
    var openingConfirmed by remember(id) { mutableStateOf(false) }
    var info by remember { mutableStateOf(false) }
    var goalSheet by remember { mutableStateOf(false) }
    var managementMenu by remember { mutableStateOf(false) }
    var renameDialog by remember { mutableStateOf(false) }
    var archiveDialog by remember { mutableStateOf(false) }
    var forkDialog by remember(id) { mutableStateOf(false) }
    var requestedForkId by remember(id) { mutableStateOf<String?>(null) }
    var renameText by remember { mutableStateOf("") }
    var operationError by remember { mutableStateOf(false) }
    var downloading by remember { mutableStateOf(false) }
    var downloadError by remember { mutableStateOf(false) }
    val clock = rememberConversationClock()
    LaunchedEffect(id, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            TaskInbox.changes.collectLatest {
                val started = android.os.SystemClock.elapsedRealtime()
                record = withContext(Dispatchers.IO) {
                    // Resolve an old notification/cache ID before publishing the first frame.
                    val opened = if (!loaded) inbox.latestFor(id) ?: inbox.read(id)
                        else inbox.latestFor(record?.id ?: id) ?: inbox.read(id) ?: record
                    ConversationSyncTrace.record("screen-read", opened?.libraryResult?.seq ?: 0,
                        android.os.SystemClock.elapsedRealtime() - started, context)
                    ConversationOpeningRules.display(record, opened)
                }
                loaded = true
            }
        }
    }
    LaunchedEffect(id, computerRevision, record?.id, record?.snapshot?.remote_ref?.host_id, record?.remoteState?.command?.host_id) {
        pairedComputer = withContext(Dispatchers.IO) { record?.let { computerStore.find(it) } }
        pairingResolved = record != null
    }
    val activeId = record?.id ?: id
    val syncRevision by ConversationSyncSelectionStore.changes.collectAsStateWithLifecycle()
    LaunchedEffect(activeId, pairedComputer?.id, syncRevision, record?.hasFullSnapshot,
        record?.snapshot?.thread_ref?.baseline_turn, record?.snapshot?.remote_ref?.baseline_turn, lifecycleOwner) {
        val computer = pairedComputer ?: return@LaunchedEffect
        val opened = record?.takeIf { it.hasFullSnapshot && it.snapshot.messages.isNotEmpty() } ?: return@LaunchedEffect
        val ref = opened.snapshot.thread_ref ?: opened.snapshot.remote_ref ?: return@LaunchedEffect
        val thread = ref.thread_id
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            withContext(Dispatchers.IO) {
                val selectionStore = ConversationSyncSelectionStore(context)
                val entry = selectionStore.read(computer).conversations.firstOrNull { it.thread.thread_id == thread }
                if (entry?.completedTurn == ref.baseline_turn) selectionStore.markSeen(computer, thread)
            }
            kotlinx.coroutines.awaitCancellation()
        }
    }
    val canConnect = settings.enabled && settings.contentEnabled && settings.remoteEnabled && pairedComputer != null &&
        (record?.snapshot?.thread_ref != null || record?.snapshot?.remote_ref != null || record?.remoteState?.command?.action == "create")
    val remoteAvailable = canConnect && record?.archived != true
    val canManage = canConnect && record?.snapshot?.remote_ref != null
    LaunchedEffect(activeId, canConnect, lifecycleOwner) {
        if (canConnect) lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { client.listen(activeId) }
    }
    LaunchedEffect(activeId, canConnect) {
        val opened = record ?: return@LaunchedEffect
        if (canConnect && RemoteSessionCache.shouldRefresh(opened, System.currentTimeMillis())) runCatching { client.session(activeId) }
    }
    LaunchedEffect(pairedComputer?.id, canConnect, lifecycleOwner) {
        val computer = pairedComputer ?: return@LaunchedEffect
        if (canConnect) lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            val connectionClient = ComputerConnectionClient(context)
            while (true) { connectionClient.probe(computer, force = false); kotlinx.coroutines.delay(20_000) }
        }
    }
    val request = record?.libraryRequest
    val result = record?.libraryResult?.takeIf { it.request_id == request?.id }
    val operationPending = request != null && (result == null || result.attachment_pending) && clock - request.issued_at < 45_000
    LaunchedEffect(result?.id, requestedForkId) {
        if (requestedForkId != null && request != null && request.id == requestedForkId && result != null && RemoteForkRules.confirmed(request, result)) {
            val conversation = result.snapshot?.conversation_id ?: return@LaunchedEffect
            val childId = TaskInbox.hash(record!!.endpointHash + ":conversation:" + conversation)
            val child = withContext(Dispatchers.IO) { inbox.read(childId) }
            if (child != null) {
                requestedForkId = null
                withContext(Dispatchers.IO) { inbox.consumeLibrarySnapshot(activeId, result.id) }
                onOpen(child.id)
            }
        }
    }
    fun query(action: String, text: String = "", cursor: String = "") {
        val thread = (record?.snapshot?.thread_ref ?: record?.snapshot?.remote_ref)?.thread_id ?: return
        operationError = false
        scope.launch {
            try {
                if (action in setOf("rename", "archive", "unarchive")) {
                    client.manageThread(activeId, action, thread, text, record?.archived == true)
                } else {
                    val requestId = client.library(activeId, action, readThread = thread, text = text, cursor = cursor, archived = record?.archived == true)
                    if (action == "fork") requestedForkId = requestId
                }
            }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { operationError = true }
        }
    }
    fun download() {
        val current = record ?: return; val computer = pairedComputer ?: return
        val url = current.attachmentUrl ?: return
        downloading = true; downloadError = false
        scope.launch {
            try {
                val snapshot = NtfyTaskClient().downloadSnapshot(computer.endpoint, TaskAttachment(url, 0, current.attachmentExpiresAt), computer.key, current.identity)
                if (snapshot == null) downloadError = true else withContext(Dispatchers.IO) { inbox.replaceExisting(current.copy(snapshot = snapshot, hasFullSnapshot = true)) }
            } finally { downloading = false }
        }
    }
    LaunchedEffect(activeId) { if (record?.hasFullSnapshot == false && (record?.attachmentExpiresAt ?: 0) > clock) download() }
    val current = record
    val contentReady = openingConfirmed || pairingResolved && ConversationOpeningRules.contentReady(current, openedAt,
        remoteAvailable && pairedComputer?.confirmedUnreachable(clock) != true)
    LaunchedEffect(contentReady) { if (contentReady) openingConfirmed = true }
    val awaitingRemoteContent = current?.snapshot?.thread_ref?.baseline_turn == "unread"
    val state = current?.remoteState
    val continuityTurn = state?.event?.turn_id?.ifBlank { null } ?: state?.command?.id.orEmpty()
    val taskIsCurrent = state != null && current != null && ConversationSnapshotRules.currentTask(current, state)
    val nativeReplyVisible = state != null && current != null && ConversationSnapshotRules.hasNativeReply(current, state)
    val showLive = state != null && taskIsCurrent && !nativeReplyVisible &&
        (state.event?.status !in setOf("completed", "interrupted", "failed") || state.event?.attachment_pending == true) && state.event?.goal_waiting != true
    val messages = remember(current?.historyMessages, current?.snapshot?.messages, current?.snapshot?.reply,current?.followUps) {
        val values = ConversationHistory.merge(current?.historyMessages.orEmpty(), current?.snapshot?.messages.orEmpty())
        val persisted = if (values.isEmpty() && !current?.snapshot?.reply.isNullOrBlank()) listOf(TaskConversationMessage("assistant", current!!.snapshot.reply)) else values
        val turn = (current?.snapshot?.thread_ref ?: current?.snapshot?.remote_ref)?.baseline_turn.orEmpty()
        val accepted = current?.followUps.orEmpty().filter { it.event?.status == "steered" && it.event.turn_id == turn &&
            persisted.none { m -> m.role == "user" && ConversationDisplayText.userText(m.text) == it.command.text } }
            .map { TaskConversationMessage("user",it.command.text,"$turn:steer-${it.command.id}",position = Int.MAX_VALUE) }
        persisted + accepted
    }
    val timeline = remember(messages, current?.activities, current?.snapshot?.activities, state?.event?.activities, showLive, continuityTurn) {
        val turn = state?.event?.turn_id.orEmpty()
        val persisted = if (showLive && state != null) ConversationHistory.withLiveInput(messages,state,current?.followUps.orEmpty()) else messages
        ConversationActivityGroups.present(ConversationTimeline.userInputsFirst(ConversationTimeline.build(persisted, RemoteActivityRules.merge(RemoteActivityRules.merge(current?.activities.orEmpty(), current?.snapshot?.activities.orEmpty()), state?.event?.activities.orEmpty()), continuityTurn),
            if (showLive) turn.ifBlank { continuityTurn } else (current?.snapshot?.thread_ref ?: current?.snapshot?.remote_ref)?.baseline_turn.orEmpty()))
    }
    val loadEarlier = canConnect && current != null && !current.libraryAnchor && current.historyCursor != "" && !operationPending
    val limitedHistory = current?.historyLimited == true
    val previewOnly = current?.hasFullSnapshot == false && !awaitingRemoteContent
    val taskStatus = state != null && current != null && taskIsCurrent && ConversationTimeline.showsTaskStatus(current.snapshot, state)
    val desktopRunning = current?.snapshot?.running == true && state == null
    val emptyTimeline = timeline.isEmpty() && !showLive && loaded && !awaitingRemoteContent
    val timelineGroups = remember(timeline) { ConversationTimeline.turnGroups(timeline) }
    val itemCount = timelineGroups.size + listOf(loadEarlier, limitedHistory, previewOnly,
        taskStatus, desktopRunning, emptyTimeline).count { it }
    val expectedItemCount by rememberUpdatedState(itemCount)
    val listState = key(id) { rememberLazyListState() }
    var positioned by remember(id) { mutableStateOf(false) }
    var followLatest by remember(id) { mutableStateOf(true) }
    var userDragging by remember(id) { mutableStateOf(false) }
    val endTolerance = with(LocalDensity.current) { 48.dp.roundToPx() }
    fun nearEnd(): Boolean {
        val layout = listState.layoutInfo
        val last = layout.visibleItemsInfo.lastOrNull() ?: return false
        return ConversationScrollPolicy.nearEnd(layout.totalItemsCount, last.index,
            last.offset + last.size + layout.afterContentPadding, layout.viewportEndOffset, endTolerance)
    }
    LaunchedEffect(listState, id, endTolerance) {
        listState.interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is DragInteraction.Start -> userDragging = true
                is DragInteraction.Stop, is DragInteraction.Cancel -> {
                    followLatest = nearEnd()
                    userDragging = false
                }
            }
        }
    }
    LaunchedEffect(listState, id, endTolerance) { snapshotFlow { Triple(userDragging, listState.isScrollInProgress, nearEnd()) }.collect { (dragging, scrolling, atEnd) ->
        // Programmatic initial positioning and streaming scrolls must not opt out of following.
        if (dragging && scrolling) followLatest = atEnd
    } }
    LaunchedEffect(loaded, itemCount, contentReady) {
        if (loaded && contentReady && current != null && !positioned && itemCount > 0) {
            // Measure at the latest position before exposing content, rather than
            // briefly displaying the top/old restored scroll position.
            listState.scrollToItem(itemCount - 1, Int.MAX_VALUE)
            positioned = true
        }
    }
    val acceptedGuidance = current?.followUps?.lastOrNull { it.event?.status == "steered" }?.command?.id.orEmpty()
    LaunchedEffect(acceptedGuidance,contentReady) {
        if (acceptedGuidance.isNotBlank() && contentReady && itemCount > 0) {
            listState.scrollToItem(itemCount-1,Int.MAX_VALUE)
            followLatest = true
        }
    }
    LaunchedEffect(listState, id) {
        snapshotFlow {
            val layout = listState.layoutInfo
            val last = layout.visibleItemsInfo.lastOrNull()
            ConversationScrollPolicy.follow(expectedItemCount, layout.totalItemsCount, last?.index ?: -1,
                (last?.offset ?: 0) + (last?.size ?: 0) + layout.afterContentPadding, layout.viewportEndOffset,
                positioned && followLatest, userDragging || listState.isScrollInProgress)
        }.collect { step -> when (step) {
            is ConversationScrollPolicy.Step.Move -> listState.scrollBy(step.pixels.toFloat())
            is ConversationScrollPolicy.Step.Reveal -> listState.scrollToItem(step.index)
            ConversationScrollPolicy.Step.None -> Unit
        } }
    }
    Scaffold(topBar = {
        ConversationHeader(current?.snapshot?.title?.ifBlank { null } ?: stringResource(R.string.conversations_title), pairedComputer, clock, canConnect, onBack, { info = true }) {
            Box {
                IconButton(onClick = { managementMenu = true }) { Icon(Icons.Outlined.MoreVert, stringResource(R.string.conversation_actions)) }
                DropdownMenu(managementMenu, { managementMenu = false }, shape = RoundedCornerShape(24.dp), modifier = Modifier.widthIn(min = 200.dp, max = 260.dp), containerColor = MaterialTheme.colorScheme.surfaceContainerLowest) {
                    DropdownMenuItem(text = { Text(stringResource(if (current?.pinned == true) R.string.conversation_menu_unpin else R.string.conversation_menu_pin)) }, leadingIcon = { Icon(Icons.Outlined.PushPin, null) },
                        onClick = { managementMenu = false; scope.launch { withContext(Dispatchers.IO) { inbox.setPinned(activeId, current?.pinned != true) } } })
                    DropdownMenuItem(text = { Text(stringResource(R.string.conversation_menu_rename)) }, leadingIcon = { Icon(Icons.Outlined.Edit, null) },
                        enabled = canManage && !operationPending && current?.let(ConversationIndex::hasPendingTurn) != true,
                        onClick = { managementMenu = false; renameText = current?.snapshot?.title.orEmpty(); renameDialog = true })
                    DropdownMenuItem(text = { Text(stringResource(if (current?.archived == true) R.string.conversation_restore else R.string.conversation_menu_archive), color = MaterialTheme.colorScheme.error) },
                        leadingIcon = { Icon(Icons.Outlined.Archive, null, tint = MaterialTheme.colorScheme.error) }, enabled = canManage && !operationPending && current?.let(ConversationIndex::hasPendingTurn) != true,
                        onClick = { managementMenu = false; if (current?.archived == true) query("unarchive") else archiveDialog = true })
                }
            }
        }
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // Dialog ownership is outside lazy history: reading an older message must
            // not prevent a live question from appearing or recreate it on scroll.
            val ownInput = state?.event?.takeIf { it.status == "input_required" && !it.attachment_pending }?.user_input
            val watchedInput = result?.takeIf { request?.action == "read" && it.status == "input_required" && !it.attachment_pending }?.user_input
            if (canConnect) (ownInput ?: watchedInput)?.takeIf(RemoteInputRules::valid)?.let { input ->
                RemoteInputPrompt(input) { answers -> client.answerInput(activeId, input.id, answers, library = ownInput == null) }
            }
            if (current == null && loaded) Text(stringResource(R.string.task_history_missing), Modifier.padding(24.dp))
            if (operationError || result?.error?.isNotBlank() == true) Text(stringResource(R.string.conversation_sync_failed), Modifier.padding(horizontal = 20.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            Box(Modifier.weight(1f)) {
                if (loaded && current != null && !contentReady && clock - openedAt >= 500) {
                    ConversationLoadingPlaceholder(Modifier.fillMaxSize())
                }
                if (contentReady) LazyColumn(state = listState, modifier = Modifier.fillMaxSize().graphicsLayer { alpha = if (positioned) 1f else 0f }, contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                    if (loadEarlier) item("older") {
                        TextButton(onClick = { query("history", cursor = current.historyCursor.orEmpty()) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.conversation_load_earlier)) }
                    }
                    if (limitedHistory) item("limited") { Text(stringResource(R.string.conversation_history_limit), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    if (previewOnly) item("preview") {
                        Text(stringResource(R.string.task_history_preview_only), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (downloadError) Text(stringResource(R.string.task_history_download_failed), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        if (!current.attachmentUrl.isNullOrBlank() && current.attachmentExpiresAt > clock) TextButton(onClick = ::download, enabled = !downloading) { Text(stringResource(R.string.task_history_retry)) }
                    }
                    items(timelineGroups, key = { "turn:" + ConversationTimeline.turn(it.first()) }) { entries ->
                        val turn = ConversationTimeline.turn(entries.first())
                        val activeTurn = if (showLive) continuityTurn else (current?.snapshot?.thread_ref ?: current?.snapshot?.remote_ref)?.baseline_turn.orEmpty()
                        ConversationTurnBlock(entries,
                            completed = turn != activeTurn || current?.snapshot?.running != true && !showLive,
                            durationMs = current?.snapshot?.turn_durations?.get(turn),recordId = current?.id,onBrowseProcess = { followLatest = false },
                            startedAt = current?.snapshot?.turn_started_at?.get(turn))
                    }
                    if (taskStatus && state != null)
                        item("task-state") { RemoteStatusPanel(activeId, state, canConnect) }

                    if (emptyTimeline) item("empty") { Text(stringResource(R.string.task_history_no_messages), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                if (positioned && !followLatest) SmallFloatingActionButton(onClick = { followLatest = true; scope.launch { if (listState.layoutInfo.totalItemsCount > 0) listState.animateScrollToItem(listState.layoutInfo.totalItemsCount - 1) } },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)) { Icon(Icons.Outlined.KeyboardArrowDown, stringResource(R.string.conversation_jump_latest)) }
            }
            if (remoteAvailable && current != null) RemoteComposer(activeId, current, onFork = { forkDialog = true }, onGoal = { goalSheet = true })
            else if (current != null) Text(stringResource(R.string.task_history_read_only), Modifier.padding(horizontal = 20.dp, vertical = 12.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (info && current != null) RemoteSessionSheet(activeId, current, pairedComputer, clock) { info = false }
    if (goalSheet && current != null) RemoteGoalSheet(activeId, current) { goalSheet = false }
    if (forkDialog) AlertDialog(onDismissRequest = { forkDialog = false }, title = { Text(stringResource(R.string.conversation_fork)) },
        text = { Text(stringResource(R.string.conversation_fork_detail)) },
        confirmButton = { TextButton(onClick = { forkDialog = false; query("fork") }, enabled = canManage && !operationPending && current?.let(ConversationIndex::hasPendingTurn) != true && current?.snapshot?.running != true) { Text(stringResource(R.string.conversation_fork)) } },
        dismissButton = { TextButton(onClick = { forkDialog = false }) { Text(stringResource(R.string.action_cancel)) } })
    if (renameDialog) AlertDialog(onDismissRequest = { renameDialog = false }, title = { Text(stringResource(R.string.conversation_menu_rename)) },
        text = { OutlinedTextField(renameText, { if (it.toByteArray().size <= 240 && !it.contains('\n')) renameText = it }, singleLine = true, label = { Text(stringResource(R.string.conversation_name)) }) },
        confirmButton = { TextButton(onClick = { renameDialog = false; query("rename", text = renameText.trim()) }, enabled = renameText.isNotBlank()) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(onClick = { renameDialog = false }) { Text(stringResource(R.string.action_cancel)) } })
    if (archiveDialog) AlertDialog(onDismissRequest = { archiveDialog = false }, title = { Text(stringResource(R.string.conversation_menu_archive)) }, text = { Text(stringResource(R.string.conversation_archive_detail)) },
        confirmButton = { TextButton(onClick = { archiveDialog = false; query("archive") }) { Text(stringResource(R.string.conversation_menu_archive)) } },
        dismissButton = { TextButton(onClick = { archiveDialog = false }) { Text(stringResource(R.string.action_cancel)) } })
}

private fun recordTimestamp(record: TaskInboxRecord): Long = ConversationIndex.timestamp(record)

@Composable
private fun recordTime(record: TaskInboxRecord): String {
    val context = LocalContext.current
    if (recordTimestamp(record) <= 0) return stringResource(R.string.conversation_time_unknown)
    return DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, context.resources.configuration.locales[0]).format(Date(recordTimestamp(record)))
}

@Composable
internal fun conversationTime(timestamp: Long, now: Long): String = when {
    timestamp <= 0 -> stringResource(R.string.conversation_time_unknown)
    timestamp > now + 60_000 -> DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT,
        LocalContext.current.resources.configuration.locales[0]).format(Date(timestamp))
    now - timestamp < 60_000 -> stringResource(R.string.conversation_just_now)
    now - timestamp < 3_600_000 -> stringResource(R.string.conversation_minutes, (now - timestamp) / 60_000)
    now - timestamp < 86_400_000 -> stringResource(R.string.conversation_hours, (now - timestamp) / 3_600_000)
    else -> DateFormat.getDateInstance(DateFormat.SHORT, LocalContext.current.resources.configuration.locales[0]).format(Date(timestamp))
}
