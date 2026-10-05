package com.codex.quota.ui.feature.taskhistory

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.codex.quota.R
import com.codex.quota.notifications.task.RemoteInputRequest

/** A new authenticated question opens once; streaming message updates do not reopen it. */
@Composable
internal fun RemoteInputPrompt(request: RemoteInputRequest, onSubmit: suspend (Map<String, List<String>>) -> Unit) {
    var open by rememberSaveable(request.id) { mutableStateOf(true) }
    TextButton(onClick = { open = true }) { Text(stringResource(R.string.remote_input_open)) }
    if (open) Dialog(onDismissRequest = { open = false }) {
        Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column(Modifier.heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.8f).dp)) {
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                    RemoteInputCard(request, onSubmit)
                }
                TextButton(onClick = { open = false }, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                    Text(stringResource(R.string.remote_input_later), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
