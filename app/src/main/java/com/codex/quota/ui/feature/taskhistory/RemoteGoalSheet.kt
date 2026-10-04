package com.codex.quota.ui.feature.taskhistory

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.codex.quota.R
import com.codex.quota.notifications.task.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Provider state only; entering this sheet reads a goal and never activates one. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RemoteGoalSheet(id: String, record: TaskInboxRecord, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val client = remember { RemoteConversationClient(context) }
    val scope = rememberCoroutineScope()
    var objective by rememberSaveable(id) { mutableStateOf("") }
    var budget by rememberSaveable(id) { mutableStateOf("") }
    var publishing by remember { mutableStateOf(false) }
    var networkError by remember { mutableStateOf(false) }
    var busyError by remember { mutableStateOf(false) }
    var confirmation by remember { mutableStateOf<String?>(null) }
    var timedOut by remember(record.goalRequest?.id) { mutableStateOf(false) }
    fun operate(action: String) {
        if (publishing) return
        publishing = true; networkError = false; busyError = false
        scope.launch {
            try { client.goal(id, action, if (action == "goal_start") objective.trim() else "", if (action == "goal_start") budget.toLongOrNull() else null) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (e: Exception) { networkError = true; busyError = e.message == "REMOTE_BUSY" }
            finally { publishing = false }
        }
    }
    LaunchedEffect(id) { operate("goal_read") }
    LaunchedEffect(record.goalRequest?.id) {
        record.goalRequest?.let { kotlinx.coroutines.delay((it.issued_at + 45_000 - System.currentTimeMillis()).coerceAtLeast(0)); timedOut = true }
    }
    val root = record.remoteState?.takeIf { it.command.action in RemoteGoalRules.rootActions }
    val queried = record.goalResult?.takeIf { it.request_id == record.goalRequest?.id }
    val event = listOfNotNull(root?.event, queried).filter { !it.partial && !it.attachment_pending && it.error.isBlank() }.maxByOrNull { it.seq }
    val goal = event?.goal
    val pending = publishing || record.goalRequest != null && queried == null && !timedOut || queried?.attachment_pending == true
    val editable = event != null && goal == null && !pending && !record.snapshot.running && !ConversationIndex.hasPendingTurn(record)
    val error = queried?.error?.takeIf { it.isNotBlank() }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.85f).dp)
            .verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.remote_goal_title), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, stringResource(R.string.conversation_menu_close)) }
            }
            Text(stringResource(R.string.remote_goal_description), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (pending) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (networkError || error != null || timedOut && queried == null) {
                Text(stringResource(if (busyError) R.string.remote_busy else when (error) {
                    "GOAL_UNAVAILABLE" -> R.string.remote_goal_unavailable
                    "DESKTOP_OWNS_THREAD" -> R.string.remote_desktop_owns_thread
                    "GOAL_NOT_OWNED" -> R.string.remote_goal_not_owned
                    "GOAL_CHANGED", "GOAL_ALREADY_EXISTS", "GOAL_NOT_PAUSED" -> R.string.remote_goal_changed
                    "GOAL_UNCONFIRMED", "DISCONNECTED" -> R.string.remote_goal_unconfirmed
                    "BUSY", "HOST_BUSY" -> R.string.remote_busy
                    "STALE" -> R.string.remote_stale
                    else -> R.string.remote_goal_read_failed
                }), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            if (goal != null) {
                Text(stringResource(goalStatusLabel(goal.status)), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                Text(goal.objective, style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.remote_goal_usage, goal.tokens_used, goal.time_used_seconds), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                goal.token_budget?.let { Text(stringResource(R.string.remote_goal_budget_value, it), style = MaterialTheme.typography.bodySmall) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (goal.status in setOf("active", "paused")) FilledTonalButton(onClick = { confirmation = if (goal.status == "paused") "goal_resume" else "goal_pause" },
                        enabled = !pending && root?.event?.attachment_pending != true) {
                        Text(stringResource(if (goal.status == "paused") R.string.remote_goal_resume else R.string.remote_goal_pause))
                    }
                    OutlinedButton(onClick = { confirmation = "goal_clear" }, enabled = !pending) { Text(stringResource(R.string.remote_goal_clear)) }
                }
            } else if (event != null) {
                OutlinedTextField(objective, { if (RemoteGoalRules.validObjective(it) || it.isBlank()) objective = it }, Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.remote_goal_objective)) }, minLines = 3, maxLines = 6, enabled = editable)
                OutlinedTextField(budget, { if (it.length <= 10 && it.all(Char::isDigit)) budget = it }, Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.remote_goal_budget_optional)) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), enabled = editable)
                Button(onClick = { confirmation = "goal_start" }, enabled = editable && RemoteGoalRules.validObjective(objective.trim()) &&
                    (budget.isBlank() || budget.toLongOrNull()?.let { it in 1..2_000_000_000 } == true), modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.remote_goal_start))
                }
            }
            TextButton(onClick = { operate("goal_read") }, enabled = !publishing, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.conversation_refresh)) }
        }
    }
    confirmation?.let { action -> AlertDialog(onDismissRequest = { confirmation = null }, title = { Text(stringResource(R.string.remote_goal_title)) },
        text = { Text(stringResource(if (action == "goal_start" || action == "goal_resume") R.string.remote_goal_start_confirm else
            if (action == "goal_clear") R.string.remote_goal_clear_confirm else R.string.remote_goal_pause_confirm)) },
        confirmButton = { TextButton(onClick = { confirmation = null; operate(action) }) { Text(stringResource(R.string.remote_goal_confirm)) } },
        dismissButton = { TextButton(onClick = { confirmation = null }) { Text(stringResource(R.string.action_cancel)) } }) }
}

internal fun goalStatusLabel(status: String): Int = when (status) {
    "active" -> R.string.remote_goal_active
    "paused" -> R.string.remote_goal_paused
    "blocked" -> R.string.remote_goal_blocked
    "budgetLimited" -> R.string.remote_goal_budget_limited
    "usageLimited" -> R.string.remote_goal_usage_limited
    "complete" -> R.string.remote_goal_complete
    else -> R.string.value_unavailable
}
