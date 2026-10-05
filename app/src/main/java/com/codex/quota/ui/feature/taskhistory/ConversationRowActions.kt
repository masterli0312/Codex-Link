package com.codex.quota.ui.feature.taskhistory

import android.content.Context
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import com.codex.quota.R
import com.codex.quota.notifications.task.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class DesktopRowTarget(val record: TaskInboxRecord?, val thread: RemoteThreadSummary?,
    val computer: ComputerConnection?, val archived: Boolean)

internal suspend fun desktopRowAction(context: Context, target: DesktopRowTarget, action: String, name: String = "") = withContext(Dispatchers.IO) {
    val client = RemoteConversationClient(context)
    val inbox = TaskInbox(context)
    val id = target.record?.id ?: client.openThread(requireNotNull(target.computer), requireNotNull(target.thread), target.archived)
    if (action == "pin") {
        inbox.setPinned(id, inbox.read(id)?.pinned != true)
    } else {
        val record = requireNotNull(inbox.read(id))
        val thread = target.thread?.thread_id ?: requireNotNull(record.snapshot.thread_ref ?: record.snapshot.remote_ref).thread_id
        client.manageThread(id, action, thread, name, target.archived)
    }
}

@Composable
internal fun ConversationRenameDialog(initialName: String, onRename: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember(initialName) { mutableStateOf(initialName) }
    val trimmed = name.trim()
    val valid = trimmed.isNotEmpty() && trimmed.toByteArray().size <= 240 && trimmed.none { it in "\r\n\u0000" }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.conversation_menu_rename)) },
        text = { OutlinedTextField(name, { name = it }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onRename(trimmed) }, enabled = valid,
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)) {
            Text(stringResource(R.string.action_save))
        } }, dismissButton = { TextButton(onClick = onDismiss,
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)) { Text(stringResource(R.string.action_cancel)) } })
}
