package com.codex.quota.ui.feature.taskhistory

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.codex.quota.R
import com.codex.quota.notifications.task.RemoteAttachmentUpload
import com.codex.quota.notifications.task.SelectedRemoteFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

@Composable
internal fun SelectedAttachmentPreview(file: SelectedRemoteFile, enabled: Boolean, onRemove: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val photo = file.name.substringAfterLast('.', "").lowercase() in setOf("jpg", "jpeg", "png", "webp")
    var bitmap by remember(file.uri) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(file.uri) { mutableStateOf(false) }
    var expanded by remember(file.uri) { mutableStateOf(false) }
    var retry by remember(file.uri) { mutableIntStateOf(0) }
    LaunchedEffect(file.uri, photo, retry) {
        if (!photo) return@LaunchedEffect
        failed = false
        try {
            bitmap = withContext(Dispatchers.IO) {
                val bytes = requireNotNull(context.contentResolver.openInputStream(file.uri)).use { input ->
                    val output = ByteArrayOutputStream(); val buffer = ByteArray(32768)
                    while (true) { val n = input.read(buffer); if (n < 0) break
                        require(output.size() + n <= RemoteAttachmentUpload.MAX_BYTES); output.write(buffer, 0, n) }
                    output.toByteArray()
                }
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                require(bounds.outWidth in 1..32768 && bounds.outHeight in 1..32768 && bounds.outWidth.toLong() * bounds.outHeight <= 100_000_000)
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1024) sample *= 2
                requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }))
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { failed = true }
    }
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.size(96.dp)) {
        Box {
            val current = bitmap
            if (photo && current != null) {
                val displayed = remember(current) { current.asImageBitmap() }
                Image(displayed, file.name, Modifier.fillMaxSize().clip(RoundedCornerShape(16.dp))
                    .clickable { expanded = true }, contentScale = ContentScale.Crop)
            } else Column(Modifier.fillMaxSize().clickable(enabled = photo && failed) { retry++ }
                .padding(horizontal = 10.dp, vertical = 18.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center) {
                if (photo && !failed) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                else Icon(if (photo) Icons.Outlined.Image else Icons.Outlined.InsertDriveFile, null)
                Spacer(Modifier.height(6.dp))
                Text(if (failed) stringResource(R.string.conversation_image_retry) else file.name,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
            }
            // 48dp touch target around a compact 24dp visual. The photo itself never removes it.
            IconButton(onClick = onRemove, enabled = enabled, modifier = Modifier.align(Alignment.TopEnd).size(48.dp)) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f), shadowElevation = 2.dp) {
                    Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.Close, stringResource(R.string.remote_attachment_remove), Modifier.size(16.dp))
                    }
                }
            }
        }
    }
    if (expanded && bitmap != null) Dialog(onDismissRequest = { expanded = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Box(Modifier.fillMaxSize().padding(16.dp)) {
                val current = requireNotNull(bitmap)
                val displayed = remember(current) { current.asImageBitmap() }
                Image(displayed, file.name, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                IconButton(onClick = { expanded = false }, modifier = Modifier.align(Alignment.TopEnd)) {
                    Icon(Icons.Outlined.Close, stringResource(android.R.string.cancel))
                }
            }
        }
    }
}
