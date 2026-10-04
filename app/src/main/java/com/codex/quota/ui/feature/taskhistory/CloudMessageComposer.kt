package com.codex.quota.ui.feature.taskhistory

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.codex.quota.R
import com.codex.quota.data.cloud.*
import com.codex.quota.notifications.task.RemoteModelOption

/** Fixed input surface shared by new Cloud tasks, follow-ups and environment preparation. */
@Composable
internal fun CloudMessageComposer(text: String, onText: (String)->Unit, onSend: ()->Unit,
    models: List<CloudPreparationModel>, choice: CloudPreparationChoice, busy: Boolean, canSend: Boolean,
    onModel: (String)->Unit, onEffort: (String)->Unit, onRefreshModels: ()->Unit,
    running: Boolean = false, modifier: Modifier = Modifier,onStop: ()->Unit = {}) {
    var options by remember { mutableStateOf(false) }
    var pickModel by remember { mutableStateOf(false) }
    var pickEffort by remember { mutableStateOf(false) }
    var voiceError by remember { mutableStateOf<Int?>(null) }
    val selected = models.firstOrNull { it.model == choice.model }
    Column(modifier.fillMaxWidth(),verticalArrangement = Arrangement.spacedBy(6.dp)) {
        voiceError?.let { Text(stringResource(R.string.remote_voice_error,it),color = MaterialTheme.colorScheme.error,style = MaterialTheme.typography.bodySmall) }
        Surface(shape = RoundedCornerShape(28.dp),color = MaterialTheme.colorScheme.surfaceContainerLowest) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp,vertical = 8.dp)) {
                BasicTextField(text,onText,enabled = !busy,maxLines = 6,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(8.dp).testTag("cloud-message-input"),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),decorationBox = { field ->
                        Box { if(text.isEmpty()) Text(stringResource(R.string.conversation_ask),color = MaterialTheme.colorScheme.onSurfaceVariant); field() }
                    })
                Row(Modifier.fillMaxWidth(),verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.weight(1f))
                    Box {
                        TextButton(onClick = { options = true },enabled = !busy && !running,contentPadding = PaddingValues(horizontal = 6.dp)) {
                            Text(selected?.name?.removePrefix("GPT-")?.replace("-"," ") ?: stringResource(R.string.remote_model),
                                maxLines = 1,overflow = TextOverflow.Ellipsis,modifier = Modifier.widthIn(max = 150.dp),
                                color = MaterialTheme.colorScheme.onSurface,style = MaterialTheme.typography.labelLarge)
                            if(choice.effort.isNotBlank()) Text(" " + effortLabel(choice.effort),color = MaterialTheme.colorScheme.onSurface,style = MaterialTheme.typography.labelLarge)
                        }
                        DropdownMenu(options,{ options = false },shape = RoundedCornerShape(20.dp)) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.remote_model)) },onClick = { options = false; pickModel = true })
                            DropdownMenuItem(text = { Text(stringResource(R.string.remote_effort)) },onClick = { options = false; pickEffort = true })
                        }
                    }
                    VoiceInputButton(enabled = !busy,onResult = { onText(listOf(text,it).filter { s -> s.isNotBlank() }.joinToString(" ")) },onError = { voiceError = it },onListening = {})
                    val stopping = running && text.isBlank()
                    FilledIconButton(onClick = if (stopping) onStop else onSend,enabled = !busy && (stopping || canSend && CloudWire.validPrompt(text)),
                        modifier = Modifier.size(42.dp).testTag("cloud-message-send"),shape = CircleShape,
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.onSurface,contentColor = MaterialTheme.colorScheme.surface)) {
                        if(busy) CircularProgressIndicator(Modifier.size(18.dp),strokeWidth = 2.dp,color = MaterialTheme.colorScheme.surface)
                        else Icon(if (stopping) Icons.Filled.Stop else Icons.Filled.ArrowUpward,stringResource(if (stopping) R.string.remote_stop else R.string.remote_send),Modifier.size(22.dp))
                    }
                }
            }
        }
    }
    if(pickModel) RemoteModelPicker(models.map { RemoteModelOption(it.model,it.name,it.efforts,it.defaultEffort) },choice.model,
        loading = busy,failed = models.isEmpty(),onRefresh = onRefreshModels,
        onSelect = { onModel(it); pickModel = false },onDismiss = { pickModel = false })
    if(pickEffort) RemoteEffortPicker(selected?.efforts.orEmpty(),choice.effort,
        onSelect = { onEffort(it); pickEffort = false },onDismiss = { pickEffort = false })
}
