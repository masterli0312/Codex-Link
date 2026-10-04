package com.codex.quota.ui.feature.taskhistory

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.codex.quota.R
import com.codex.quota.notifications.task.RemoteInputRequest
import com.codex.quota.notifications.task.RemoteInputRules
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Answers stay in memory; secret fields never become a saved draft, history item or notification. */
@Composable
internal fun RemoteInputCard(request: RemoteInputRequest, onSubmit: suspend (Map<String, List<String>>) -> Unit) {
    val scope = rememberCoroutineScope()
    var selected by remember(request.id) { mutableStateOf<Map<String, String>>(emptyMap()) }
    var text by remember(request.id) { mutableStateOf<Map<String, String>>(emptyMap()) }
    var sending by remember(request.id) { mutableStateOf(false) }
    var submitted by remember(request.id) { mutableStateOf(false) }
    var failed by remember(request.id) { mutableStateOf(false) }
    LaunchedEffect(request.id, submitted) {
        if (submitted) { delay(30_000); submitted = false; failed = true }
    }
    val answers = request.questions.associate { q ->
        q.id to listOf(if (q.options.isEmpty() || selected[q.id] == "") text[q.id].orEmpty() else selected[q.id].orEmpty())
    }
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.remote_input_required), style = MaterialTheme.typography.titleSmall)
            if (!request.is_blocking) Text(stringResource(R.string.remote_input_optional), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            request.questions.forEach { q ->
                key(q.id) {
                    Text(q.question, style = MaterialTheme.typography.bodyMedium)
                    q.options.forEach { option ->
                        Row(Modifier.fillMaxWidth().selectable(selected[q.id] == option.label, enabled = !sending && !submitted,
                            role = Role.RadioButton, onClick = { selected = selected + (q.id to option.label) }).padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected[q.id] == option.label, onClick = null, enabled = !sending && !submitted)
                            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                                Text(option.label, style = MaterialTheme.typography.bodyMedium)
                                if (option.description.isNotBlank()) Text(option.description, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    if (q.options.isNotEmpty() && q.is_other) Row(Modifier.fillMaxWidth().selectable(selected[q.id] == "",
                        enabled = !sending && !submitted, role = Role.RadioButton, onClick = { selected = selected + (q.id to "") }),
                        verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected[q.id] == "", onClick = null, enabled = !sending && !submitted)
                        Text(stringResource(R.string.remote_input_other), Modifier.padding(start = 10.dp))
                    }
                    if (q.options.isEmpty() || q.is_other && selected[q.id] == "") OutlinedTextField(
                        value = text[q.id].orEmpty(), onValueChange = { if (it.toByteArray().size <= 4096) text = text + (q.id to it) },
                        modifier = Modifier.fillMaxWidth(), label = { Text(q.header) }, minLines = 1, maxLines = if (q.is_secret) 1 else 4,
                        enabled = !sending && !submitted, singleLine = q.is_secret,
                        visualTransformation = if (q.is_secret) PasswordVisualTransformation() else VisualTransformation.None,
                        keyboardOptions = KeyboardOptions(autoCorrectEnabled = !q.is_secret))
                }
            }
            if (failed) Text(stringResource(R.string.remote_input_reply_unconfirmed), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error)
            if (submitted) Text(stringResource(R.string.remote_input_submitted), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            FilledTonalButton(onClick = {
                sending = true; failed = false
                scope.launch {
                    try { onSubmit(answers); submitted = true }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { failed = true }
                    finally { sending = false }
                }
            }, enabled = !sending && !submitted && RemoteInputRules.accepts(request, answers), modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(if (sending) R.string.remote_waiting else R.string.remote_input_submit))
            }
        }
    }
}
