package com.codex.quota.ui.feature.taskhistory

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.codex.quota.R
import com.codex.quota.notifications.task.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Load state shared by the agent, task and overview views; never spins forever. */
internal sealed interface HostLoad<out T> {
    data object Loading : HostLoad<Nothing>
    data object NeedsUpdate : HostLoad<Nothing>
    data class Failed(val code: String) : HostLoad<Nothing>
    data class Ready<T>(val value: T) : HostLoad<T>
}

@Composable
internal fun hostErrorText(code: String): String = stringResource(when (code) {
    "SHARED_REQUIRED" -> R.string.agents_error_shared_required
    "STALE_ACTIVE_TURN" -> R.string.agents_error_stale
    "STOP_UNVERIFIED" -> R.string.agents_error_unverified
    "STOP_TIMEOUT" -> R.string.agents_error_stop_timeout
    "TASKS_DISABLED" -> R.string.agents_error_tasks_disabled
    "OCX_UNAVAILABLE" -> R.string.agents_error_ocx_unavailable
    "TASK_NOT_FOUND", "INVALID_THREAD", "ROLLOUT_UNAVAILABLE" -> R.string.agents_error_not_found
    else -> R.string.agents_error_unreachable
})

private fun errorCode(e: Exception) = (e as? HostCommandException)?.code ?: "UNREACHABLE"

/** Polls one host command while visible. A missing capability is reported once, not retried. */
@Composable
internal fun <T> rememberHostPoll(computer: ComputerConnection?, capability: String, key: Any?, intervalMs: Long,
    fetch: suspend HostCommandClient.(ComputerConnection) -> T): Pair<HostLoad<T>, () -> Unit> {
    val context = LocalContext.current
    val client = remember { HostCommandClient(context) }
    var state by remember(computer?.id, key) { mutableStateOf<HostLoad<T>>(HostLoad.Loading) }
    var generation by remember { mutableIntStateOf(0) }
    LaunchedEffect(computer?.id, key, generation) {
        val target = computer ?: return@LaunchedEffect
        while (true) {
            try {
                if (capability !in client.capabilities(target)) { state = HostLoad.NeedsUpdate; return@LaunchedEffect }
                state = HostLoad.Ready(client.fetch(target))
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (state !is HostLoad.Ready) state = HostLoad.Failed(errorCode(e)) }
            if (intervalMs <= 0) return@LaunchedEffect
            delay(intervalMs)
        }
    }
    return state to { generation++ }
}

internal fun elapsedText(start: Long, end: Long, now: Long): String {
    if (start <= 0) return ""
    val seconds = (((if (end > 0) end else now) - start) / 1000).coerceAtLeast(0)
    val h = seconds / 3600; val m = seconds % 3600 / 60; val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

internal fun shortModel(model: String) = model.substringAfterLast('/')

@Composable
private fun statusText(status: String) = stringResource(when (status) {
    "running" -> R.string.agents_status_running
    "starting" -> R.string.agents_status_starting
    "completed", "succeeded" -> R.string.agents_status_completed
    "interrupted", "stopped" -> R.string.agents_status_stopped
    "errored", "failed" -> R.string.agents_status_failed
    "idle" -> R.string.agents_status_idle
    else -> R.string.agents_status_unknown
})

@Composable
private fun StatusChip(status: String) {
    val colors = MaterialTheme.colorScheme
    val (bg, fg) = when (status) {
        "running", "starting" -> colors.primaryContainer to colors.onPrimaryContainer
        "errored", "failed" -> colors.errorContainer to colors.onErrorContainer
        else -> colors.surfaceVariant to colors.onSurfaceVariant
    }
    Surface(color = bg, contentColor = fg, shape = RoundedCornerShape(50)) {
        Text(statusText(status), Modifier.padding(horizontal = 8.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun StopConfirm(title: String, detail: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = { Text(detail) },
        confirmButton = { TextButton(onClick = { onDismiss(); onConfirm() }) { Text(stringResource(R.string.agents_stop)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } })
}

/** Compact row above the composer; hidden when the conversation never spawned a subagent. */
@Composable
internal fun SubagentBar(computer: ComputerConnection?, parentThreadId: String?, active: Boolean) {
    if (computer == null || parentThreadId.isNullOrBlank()) return
    var open by remember { mutableStateOf(false) }
    val (load, _) = rememberHostPoll(computer, RemoteAgentRules.AGENTS, parentThreadId, if (active) 10_000 else 0) {
        request(it, "agents") { c -> c.copy(read_thread_id = parentThreadId) }.agents
    }
    val agents = (load as? HostLoad.Ready)?.value.orEmpty()
    if (agents.isEmpty()) return
    val running = RemoteAgentRules.runningAgents(agents)
    Surface(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).clickable { open = true },
        shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.AccountTree, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.agents_bar, agents.size) + if (running > 0) " · " + stringResource(R.string.agents_bar_running, running) else "",
                Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            Icon(Icons.Outlined.ChevronRight, null, Modifier.size(18.dp))
        }
    }
    if (open) SubagentSheet(computer, parentThreadId) { open = false }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SubagentSheet(computer: ComputerConnection, parentThreadId: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val client = remember { HostCommandClient(context) }
    val (load, reload) = rememberHostPoll(computer, RemoteAgentRules.AGENTS, parentThreadId, 5_000) {
        request(it, "agents") { c -> c.copy(read_thread_id = parentThreadId) }.agents
    }
    var viewing by remember { mutableStateOf<RemoteAgent?>(null) }
    var confirm by remember { mutableStateOf<RemoteAgent?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val now by produceState(System.currentTimeMillis()) { while (true) { delay(1000); value = System.currentTimeMillis() } }
    val stopped = stringResource(R.string.agents_stopped); val finished = stringResource(R.string.agents_already_finished)
    val errors = mapOf("SHARED_REQUIRED" to stringResource(R.string.agents_error_shared_required), "STALE_ACTIVE_TURN" to stringResource(R.string.agents_error_stale))
    val fallback = stringResource(R.string.agents_error_stop_failed)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text(stringResource(R.string.agents_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.agents_stop_hint), Modifier.padding(top = 4.dp, bottom = 12.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            message?.let { Text(it, Modifier.padding(bottom = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
            HostLoadContent(load) { agents ->
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(agents, key = { it.thread_id }) { agent ->
                        AgentRow(agent, now, onView = { viewing = agent }, onStop = { confirm = agent })
                    }
                }
            }
        }
    }
    confirm?.let { agent ->
        StopConfirm(stringResource(R.string.agents_stop_title, agent.nickname.ifBlank { agent.thread_id.takeLast(8) }),
            stringResource(R.string.agents_stop_detail), onDismiss = { confirm = null }) {
            scope.launch {
                message = try {
                    val event = client.request(computer, "agent_stop") { it.copy(read_thread_id = agent.thread_id, expected_turn_id = agent.turn_id) }
                    if (event.reply == "already_finished") finished else stopped
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { errors[errorCode(e)] ?: fallback }
                reload()
            }
        }
    }
    viewing?.let { agent ->
        ReadOnlyTranscriptSheet(computer, agent.nickname.ifBlank { stringResource(R.string.agents_unnamed) },
            stringResource(R.string.agents_from_parent), agent.status == "running", onDismiss = { viewing = null }) {
            it.copy(mode = "subagent", read_thread_id = agent.thread_id)
        }
    }
}

@Composable
private fun AgentRow(agent: RemoteAgent, now: Long, onView: () -> Unit, onStop: () -> Unit) {
    Surface(Modifier.fillMaxWidth().clickable(onClick = onView), shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.padding(start = (12 + (agent.depth - 1).coerceIn(0, 3) * 12).dp, end = 12.dp, top = 10.dp, bottom = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(agent.nickname.ifBlank { stringResource(R.string.agents_unnamed) }, fontWeight = FontWeight.SemiBold)
                if (agent.role.isNotBlank()) Text("  " + agent.role, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f)); StatusChip(agent.status)
            }
            val meta = listOf(shortModel(agent.model), agent.effort, elapsedText(agent.started_at,
                if (agent.status == "running") 0 else agent.updated_at, now)).filter { it.isNotBlank() }.joinToString(" · ")
            if (meta.isNotBlank()) Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (agent.last_reply.isNotBlank()) Text(agent.last_reply, Modifier.padding(top = 4.dp), maxLines = 2,
                overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
            if (RemoteAgentRules.stoppable(agent)) TextButton(onClick = onStop, contentPadding = PaddingValues(0.dp)) {
                Icon(Icons.Outlined.StopCircle, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.agents_stop))
            }
        }
    }
}

@Composable
private fun <T> HostLoadContent(load: HostLoad<T>, content: @Composable (T) -> Unit) {
    when (load) {
        HostLoad.Loading -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
        }
        HostLoad.NeedsUpdate -> Text(stringResource(R.string.agents_needs_update), Modifier.padding(vertical = 16.dp),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        is HostLoad.Failed -> Text(hostErrorText(load.code), Modifier.padding(vertical = 16.dp),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        is HostLoad.Ready -> content(load.value)
    }
}

/** Read-only transcript of a subagent or background task; refreshes while it runs, never shows a composer. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReadOnlyTranscriptSheet(computer: ComputerConnection, title: String, subtitle: String, running: Boolean,
    onDismiss: () -> Unit, configure: (RemoteCommand) -> RemoteCommand) {
    val (load, _) = rememberHostPoll(computer, RemoteAgentRules.AGENTS, title, if (running) 5_000 else 0) {
        request(it, "agent_read", 40_000, configure).snapshot
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 20.dp).fillMaxHeight(0.92f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(subtitle, Modifier.padding(bottom = 12.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            HostLoadContent(load) { snapshot ->
                val messages = snapshot?.messages.orEmpty(); val activities = snapshot?.activities.orEmpty()
                LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                    if (snapshot?.truncated == true) item { Text(stringResource(R.string.agents_truncated), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    items(messages, key = { it.id.ifBlank { it.position.toString() + it.text.hashCode() } }) { ConversationMessage(it, showCopy = true) }
                    if (activities.isNotEmpty()) item { RemoteActivityGroupInline(activities.takeLast(12)) }
                    if (messages.isEmpty() && activities.isEmpty()) item { Text(stringResource(R.string.task_history_no_messages),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
    }
}

/** Entry on the conversation list: background codex exec tasks and the opencodex overview of this computer. */
@Composable
internal fun BackgroundTasksEntry(computer: ComputerConnection) {
    var open by remember { mutableStateOf(false) }
    val (load, _) = rememberHostPoll(computer, RemoteAgentRules.TASKS, "entry", 30_000) { request(it, "tasks").tasks }
    val running = (load as? HostLoad.Ready)?.value.orEmpty().count { it.status in RemoteAgentRules.running }
    TextButton(onClick = { open = true }, modifier = Modifier.padding(horizontal = 12.dp)) {
        Icon(Icons.Outlined.Dns, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.tasks_entry) + if (running > 0) " · " + stringResource(R.string.agents_bar_running, running) else "")
    }
    if (open) BackgroundTasksSheet(computer) { open = false }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BackgroundTasksSheet(computer: ComputerConnection, onDismiss: () -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxHeight(0.92f)) {
            TabRow(selectedTabIndex = tab) {
                Tab(tab == 0, onClick = { tab = 0 }, text = { Text(stringResource(R.string.tasks_tab_tasks)) })
                Tab(tab == 1, onClick = { tab = 1 }, text = { Text(stringResource(R.string.tasks_tab_ocx)) })
            }
            Box(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                if (tab == 0) TasksTab(computer) else OcxTab(computer)
            }
        }
    }
}

@Composable
private fun TasksTab(computer: ComputerConnection) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val client = remember { HostCommandClient(context) }
    val (load, reload) = rememberHostPoll(computer, RemoteAgentRules.TASKS, "tasks", 5_000) { request(it, "tasks").tasks }
    var viewing by remember { mutableStateOf<RemoteBackgroundTask?>(null) }
    var confirm by remember { mutableStateOf<RemoteBackgroundTask?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val now by produceState(System.currentTimeMillis()) { while (true) { delay(1000); value = System.currentTimeMillis() } }
    val stopped = stringResource(R.string.agents_stopped); val finished = stringResource(R.string.agents_already_finished)
    val unverified = stringResource(R.string.agents_error_unverified); val timeout = stringResource(R.string.agents_error_stop_timeout)
    val fallback = stringResource(R.string.agents_error_stop_failed)
    Column {
        Text(stringResource(R.string.tasks_hint), Modifier.padding(bottom = 8.dp), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        message?.let { Text(it, Modifier.padding(bottom = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
        HostLoadContent(load) { tasks ->
            if (tasks.isEmpty()) Text(stringResource(R.string.tasks_empty), Modifier.padding(vertical = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            val groups = RemoteAgentRules.sortTasks(tasks).groupBy { it.worktree_name.ifBlank { it.branch } }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                groups.forEach { (group, rows) ->
                    item(key = "g:$group") { Text(group.ifBlank { stringResource(R.string.tasks_group_other) }, Modifier.padding(top = 8.dp),
                        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    items(rows, key = { it.id }) { task -> TaskRow(task, now, onView = { viewing = task }, onStop = { confirm = task }) }
                }
            }
        }
    }
    confirm?.let { task ->
        StopConfirm(stringResource(R.string.tasks_stop_title), task.title, onDismiss = { confirm = null }) {
            scope.launch {
                message = try {
                    val event = client.request(computer, "task_stop", 40_000) { it.copy(target_id = task.id) }
                    if (event.reply == "already_finished") finished else stopped
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { when (errorCode(e)) { "STOP_UNVERIFIED" -> unverified; "STOP_TIMEOUT" -> timeout; else -> fallback } }
                reload()
            }
        }
    }
    viewing?.let { task ->
        ReadOnlyTranscriptSheet(computer, task.title.ifBlank { task.id }, listOf(shortModel(task.model), task.branch)
            .filter { it.isNotBlank() }.joinToString(" · "), task.status in RemoteAgentRules.running, onDismiss = { viewing = null }) {
            it.copy(mode = "task", target_id = task.id)
        }
    }
}

@Composable
private fun TaskRow(task: RemoteBackgroundTask, now: Long, onView: () -> Unit, onStop: () -> Unit) {
    Surface(Modifier.fillMaxWidth().clickable(onClick = onView), shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Text(task.title.ifBlank { task.id }, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(8.dp)); StatusChip(task.status)
            }
            val meta = listOf(shortModel(task.model), task.effort, elapsedText(task.started_at, task.ended_at, now),
                if (task.runs > 1) stringResource(R.string.tasks_runs, task.runs) else "").filter { it.isNotBlank() }.joinToString(" · ")
            Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (task.branch.isNotBlank() && task.worktree_name.isNotBlank()) Text(task.branch, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (task.last_step.isNotBlank()) Text(task.last_step, Modifier.padding(top = 4.dp), maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall)
            if (task.last_reply.isNotBlank()) Text(task.last_reply, Modifier.padding(top = 2.dp), maxLines = 2, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (task.can_stop) TextButton(onClick = onStop, contentPadding = PaddingValues(0.dp)) {
                Icon(Icons.Outlined.StopCircle, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.agents_stop))
            }
        }
    }
}

@Composable
private fun OcxTab(computer: ComputerConnection) {
    val (load, _) = rememberHostPoll(computer, RemoteAgentRules.OCX, "ocx", 30_000) { request(it, "ocx_overview", 30_000).overview }
    HostLoadContent(load) { overview ->
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            item { Text(stringResource(R.string.ocx_accounts), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(overview?.accounts.orEmpty(), key = { "a:" + it.label }) { account ->
                Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(account.label, fontWeight = FontWeight.SemiBold)
                            if (account.plan.isNotBlank()) Text("  " + account.plan, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.weight(1f))
                            if (account.selected) Text(stringResource(R.string.ocx_selected), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }
                        Text(stringResource(R.string.ocx_left, percentText(account.short_left_percent), percentText(account.weekly_left_percent)),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item { Text(stringResource(R.string.ocx_requests, overview?.window_minutes ?: 0), Modifier.padding(top = 12.dp),
                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(overview?.requests.orEmpty(), key = { "r:" + it.provider + "/" + it.model + "/" + it.inbound }) { row ->
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(shortModel(row.model) + "  ·  " + stringResource(if (row.inbound == "messages") R.string.ocx_inbound_external else R.string.ocx_inbound_codex),
                        style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.ocx_request_line, row.count, row.failures, row.tokens), style = MaterialTheme.typography.bodySmall,
                        color = if (row.failures > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                    if (row.last_error.isNotBlank()) Text(row.last_error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

private fun percentText(value: Int) = if (value in 0..100) "$value%" else "—"
