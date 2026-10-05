package com.codex.quota.ui.feature.taskhistory

import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.codex.quota.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal sealed interface ConversationSaveContent {
    data class Bytes(val value: ByteArray) : ConversationSaveContent
    data class StagedFile(val value: java.io.File, val discardAfterUse: Boolean = true) : ConversationSaveContent
}
private fun ConversationSaveContent.discard() { if (this is ConversationSaveContent.StagedFile && discardAfterUse) value.delete() }

/** System save dialog requires no storage permission and leaves filenames under user control. */
@Composable
internal fun ConversationSaveButton(name: String, image: Boolean = false, trigger: Int = 0, compact: Boolean = false, load: suspend () -> ConversationSaveContent) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val latestLoad by rememberUpdatedState(load)
    var busy by remember(name) { mutableStateOf(false) }
    var saved by remember(name) { mutableStateOf(false) }
    var failed by remember(name) { mutableStateOf(false) }
    var pendingBytes by remember(name) { mutableStateOf<ConversationSaveContent?>(null) }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(if (image) "image/*" else com.codex.quota.notifications.task.ConversationDocumentTypes.mime(name))) { uri ->
        val content = pendingBytes
        pendingBytes = null
        if (uri != null && content != null) {
            busy = true; failed = false; saved = false
            scope.launch {
                try {
                    withContext(Dispatchers.IO) { requireNotNull(context.contentResolver.openOutputStream(uri, "wt")).use { output ->
                        when (content) {
                            is ConversationSaveContent.Bytes -> output.write(content.value)
                            is ConversationSaveContent.StagedFile -> content.value.inputStream().use { input -> input.copyTo(output, 65536) }
                        }
                    } }
                    saved = true
                } catch (cancelled: CancellationException) {
                    withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) } }
                    throw cancelled
                } catch (_: Exception) {
                    withContext(Dispatchers.IO) { runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) } }
                    failed = true
                } finally { withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { content.discard() }; busy = false }
            }
        } else if (content != null) scope.launch(Dispatchers.IO) { content.discard() }
    }
    val latestPending by rememberUpdatedState(pendingBytes)
    DisposableEffect(Unit) { onDispose { latestPending?.discard() } }
    fun download() {
        if (busy || pendingBytes != null) {
            return
        }
            busy = true; failed = false; saved = false
            scope.launch {
                try {
                    val content = latestLoad()
                    pendingBytes = content
                    val bytes = withContext(Dispatchers.IO) { when (content) {
                        is ConversationSaveContent.Bytes -> content.value.take(12).toByteArray()
                        is ConversationSaveContent.StagedFile -> content.value.inputStream().use { input -> ByteArray(12).let { header -> val n = input.read(header); header.copyOf(n.coerceAtLeast(0)) } }
                    } }
                    val extension = if (!image) "" else when {
                        bytes.size > 2 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() -> "jpg"
                        bytes.size >= 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" -> "webp"
                        bytes.size > 6 && String(bytes, 0, 3, Charsets.US_ASCII) == "GIF" -> "gif"
                        else -> "png"
                    }
                    saver.launch(if (image) name.substringBeforeLast('.') + "." + extension else name)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { failed = true; pendingBytes?.discard(); pendingBytes = null }
                finally { busy = false }
            }
    }
    LaunchedEffect(trigger) { if (trigger > 0) download() }
    Column {
        OutlinedButton(onClick = ::download, enabled = !busy && pendingBytes == null,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)) {
            Icon(if (saved) Icons.Outlined.Check else Icons.Outlined.Download, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(if (busy) stringResource(R.string.conversation_file_downloading) else if (saved) stringResource(R.string.conversation_file_saved)
                else if (image) stringResource(R.string.conversation_image_save) else if (compact) stringResource(R.string.conversation_file_download) else name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 240.dp))
        }
        if (failed) Text(stringResource(R.string.conversation_file_failed), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
}
