package com.codex.quota.ui.feature.taskhistory

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.codex.quota.R
import com.codex.quota.notifications.task.*
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Composable
internal fun ConversationDocumentCard(recordId: String, file: ConversationFileRef, trigger: Int = 0) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var staged by remember(recordId, file.id) { mutableStateOf<File?>(null) }
    var loading by remember(recordId, file.id) { mutableStateOf(false) }
    var error by remember(recordId, file.id) { mutableStateOf<Int?>(null) }
    var pdf by remember(recordId, file.id) { mutableStateOf<File?>(null) }
    val lock = remember(recordId, file.id) { Mutex() }
    suspend fun load(): File = lock.withLock {
        staged?.takeIf { it.isFile } ?: ConversationDocuments.stage(context,
            ConversationFileTransfer.loadToFile(context, recordId, file), file.name).also { staged = it }
    }
    fun view() {
        if (loading) return
        loading = true; error = null
        scope.launch {
            try {
                val local = load()
                if (ConversationDocumentTypes.mime(file.name) == "application/pdf") pdf = local
                else context.startActivity(ConversationDocuments.viewIntent(context, local))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: android.content.ActivityNotFoundException) { error = R.string.conversation_file_no_viewer }
            catch (_: Exception) { error = R.string.conversation_file_failed }
            finally { loading = false }
        }
    }
    LaunchedEffect(trigger) { if (trigger > 0) view() }
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(if (file.name.endsWith(".pdf", true)) Icons.Outlined.PictureAsPdf else Icons.Outlined.Description, null,
                    Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(file.name, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = ::view, enabled = !loading) {
                    Icon(Icons.Outlined.OpenInNew, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp))
                    Text(stringResource(if (loading) R.string.conversation_file_downloading else R.string.conversation_file_view))
                }
                ConversationSaveButton(file.name, compact = true) { ConversationSaveContent.StagedFile(load(), discardAfterUse = false) }
            }
            error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }
    pdf?.let { PdfConversationPreview(it) { pdf = null } }
}
