package com.codex.quota.ui.feature.taskhistory

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.codex.quota.R
import com.codex.quota.notifications.task.ConversationImageRef
import com.codex.quota.notifications.task.ConversationImageStore
import kotlinx.coroutines.CancellationException

@Composable
internal fun ConversationImage(image: ConversationImageRef, recordId: String?, compact: Boolean = false) {
    val context = LocalContext.current.applicationContext
    var retry by remember(image.id, recordId) { mutableIntStateOf(0) }
    var expanded by remember(image.id, recordId) { mutableStateOf(false) }
    var bitmap by remember(image.id, recordId) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(image.id, recordId) { mutableStateOf(false) }
    LaunchedEffect(image.id, recordId, retry) {
        failed = false
        if (recordId == null) { failed = true; return@LaunchedEffect }
        try { bitmap = ConversationImageStore.load(context, recordId, image) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { failed = true }
    }
    val current = bitmap
    val label = stringResource(R.string.conversation_image)
    // Reserve the same compact frame while loading; decoding cannot resize the conversation.
    val frame = Modifier.size(width = if (compact) 120.dp else 144.dp, height = if (compact) 144.dp else 168.dp)
    if (current != null) {
        val displayed = remember(current) { current.asImageBitmap() }
        Image(displayed, label, frame.clip(RoundedCornerShape(12.dp))
            .clickable { expanded = true }, contentScale = ContentScale.Crop, alignment = Alignment.TopCenter)
    } else {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(12.dp),
            modifier = frame.clickable(enabled = failed) { retry++ }) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                if (failed) Icon(Icons.Outlined.Image, label, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                else CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                Spacer(Modifier.height(8.dp))
                Text(stringResource(if (failed) R.string.conversation_image_retry else R.string.conversation_image_loading),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (expanded && current != null && recordId != null) {
        var large by remember(image.id, recordId) { mutableStateOf(current) }
        LaunchedEffect(image.id, recordId) {
            try { large = ConversationImageStore.load(context, recordId, image, large = true) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* The already loaded preview stays usable. */ }
        }
        Dialog(onDismissRequest = { expanded = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                Box(Modifier.fillMaxSize().padding(16.dp)) {
                    val displayed = remember(large) { large.asImageBitmap() }
                    Image(displayed, label, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                    IconButton(onClick = { expanded = false }, modifier = Modifier.align(Alignment.TopEnd)) {
                        Icon(Icons.Outlined.Close, stringResource(android.R.string.cancel))
                    }
                }
            }
        }
    }
}
