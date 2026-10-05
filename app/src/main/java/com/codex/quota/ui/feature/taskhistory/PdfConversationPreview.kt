package com.codex.quota.ui.feature.taskhistory

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.codex.quota.R
import java.io.File
import kotlinx.coroutines.*

internal class ConversationPdf(file: File) : AutoCloseable {
    private val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = try { PdfRenderer(descriptor) } catch (e: Exception) { descriptor.close(); throw e }
    private var closed = false
    val pages: Int = renderer.pageCount
    @Synchronized fun render(index: Int): Bitmap {
        check(!closed)
        return renderer.openPage(index).use { page ->
            val scale = minOf(2f, 1800f / page.width, 2400f / page.height)
            Bitmap.createBitmap((page.width * scale).toInt().coerceAtLeast(1), (page.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888).also {
                it.eraseColor(android.graphics.Color.WHITE)
                page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            }
        }
    }
    @Synchronized override fun close() {
        if (!closed) { closed = true; renderer.close(); descriptor.close() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PdfConversationPreview(file: File, onDismiss: () -> Unit) {
    var failed by remember(file) { mutableStateOf(false) }
    var document by remember(file) { mutableStateOf<ConversationPdf?>(null) }
    LaunchedEffect(file) {
        var opened: ConversationPdf? = null
        try {
            opened = withContext(Dispatchers.IO) { ConversationPdf(file) }
            document = opened
            awaitCancellation()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { failed = true }
        finally { withContext(NonCancellable + Dispatchers.IO) { opened?.close() } }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(topBar = { TopAppBar(title = { Text(file.name, maxLines = 1) }, navigationIcon = {
            IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back)) }
        }) }) { padding ->
            LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (failed) item { Text(stringResource(R.string.conversation_file_failed), color = MaterialTheme.colorScheme.error) }
                else if (document == null) item { Text(stringResource(R.string.conversation_file_downloading)) }
                document?.let { pdf -> items((0 until pdf.pages).toList(), key = { it }) { index ->
                    var bitmap by remember(pdf, index) { mutableStateOf<Bitmap?>(null) }
                    LaunchedEffect(pdf, index) {
                        bitmap = try { withContext(Dispatchers.IO) { pdf.render(index) } }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { failed = true; null }
                    }
                    bitmap?.let { image -> Image(image.asImageBitmap(), "${index + 1}/${pdf.pages}", Modifier.fillMaxWidth().aspectRatio(image.width.toFloat() / image.height)) }
                    Text("${index + 1}/${pdf.pages}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } }
            }
        }
    }
}
