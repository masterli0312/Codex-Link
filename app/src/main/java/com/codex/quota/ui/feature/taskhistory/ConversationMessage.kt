package com.codex.quota.ui.feature.taskhistory

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import android.widget.TextView
import android.text.method.LinkMovementMethod
import android.text.TextPaint
import android.text.style.MetricAffectingSpan
import android.view.ViewGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import com.codex.quota.R
import com.codex.quota.notifications.task.TaskConversationMessage
import com.codex.quota.notifications.task.ConversationDisplayText
import com.codex.quota.notifications.task.ConversationFileTransfer
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.MarkwonConfiguration
import io.noties.markwon.MarkwonSpansFactory
import io.noties.markwon.LinkResolver
import io.noties.markwon.LinkResolverDef
import io.noties.markwon.core.MarkwonTheme
import io.noties.markwon.ext.tables.TablePlugin
import org.commonmark.node.StrongEmphasis

@Composable
internal fun ConversationMessage(message: TaskConversationMessage, showCopy: Boolean = true, recordId: String? = null) {
    if (message.role == "user") {
        val displayText = remember(message.text) { ConversationDisplayText.userText(message.text) }
        val attachmentNames = remember(message.text) { ConversationDisplayText.attachmentNames(message.text) }
        val questionAnswers = remember(message.text) { ConversationDisplayText.questionAnswers(message.text) }
        if (displayText.isBlank() && message.images.isEmpty() && attachmentNames.isEmpty()) return
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (message.images.isEmpty()) attachmentNames.forEach { name ->
                    Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Outlined.Image, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(name, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                        }
                    }
                }
                if (displayText.isNotBlank()) {
                    Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceContainer,
                        modifier = Modifier.widthIn(max = 320.dp).padding(start = 28.dp)) {
                        Column(Modifier.padding(horizontal = 18.dp, vertical = 12.dp)) {
                            SelectionContainer {
                                if (questionAnswers == null) Text(displayText, style = MaterialTheme.typography.bodyLarge)
                                else Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    questionAnswers.forEach { answer -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(answer.question, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text(answer.answer, style = MaterialTheme.typography.bodyLarge)
                                    } }
                                }
                            }
                        }
                    }
                }
                message.images.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { image -> key(image.id) { ConversationImage(image, recordId, compact = true) } }
                    }
                }
            }
        }
    } else {
        val context = LocalContext.current
        var fileRequest by remember(message.id, recordId) { mutableStateOf<Pair<String, Int>?>(null) }
        var fileRequestCounter by remember(message.id, recordId) { mutableIntStateOf(0) }
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            MarkdownReply(message.text) { link ->
                val name = runCatching { android.net.Uri.decode(link).replace('\\', '/').substringAfterLast('/').replace(Regex(":\\d+(?::\\d+)?$"), "") }.getOrNull()
                val file = message.files.singleOrNull { it.name == name }
                if (recordId != null && file != null) { fileRequestCounter++; fileRequest = file.id to fileRequestCounter; true }
                else false
            }
            if (recordId != null) message.files.forEach { file -> key(file.id) {
                ConversationDocumentCard(recordId, file, trigger = fileRequest?.takeIf { it.first == file.id }?.second ?: 0)
            } }
            message.images.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { image -> key(image.id) { ConversationImage(image, recordId) } }
                }
            }
            if (showCopy && message.text.isNotBlank()) {
                var copied by remember(message.text) { mutableStateOf(false) }
                IconButton(modifier = Modifier.size(48.dp), onClick = {
                    val clip = ClipData.newPlainText(context.getString(R.string.task_history_latest_reply), message.text)
                    if (Build.VERSION.SDK_INT >= 33) clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
                    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
                    copied = true
                }) { Icon(Icons.Outlined.ContentCopy, stringResource(if (copied) R.string.conversation_copied else R.string.conversation_copy), Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

/** Markwon renders local native text, headings, links, code and tables. No scripts/images run. */
@Composable
private fun MarkdownReply(text: String, onFileLink: (String) -> Boolean = { false }) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography.bodyLarge
    val latestFileLink by rememberUpdatedState(onFileLink)
    val markwon = remember(context, colors) {
        Markwon.builder(context).usePlugin(TablePlugin.create(context)).usePlugin(object : AbstractMarkwonPlugin() {
            override fun configureSpansFactory(builder: MarkwonSpansFactory.Builder) {
                // Some Android CJK fallback fonts ignore a requested bold typeface.
                // Draw the author's **emphasis** with explicit weight on both devices.
                builder.setFactory(StrongEmphasis::class.java) { _, _ -> VisibleStrongEmphasisSpan() }
            }
            override fun configureConfiguration(builder: MarkwonConfiguration.Builder) {
                val standard = LinkResolverDef()
                builder.linkResolver(LinkResolver { view, link -> if (!latestFileLink(link)) standard.resolve(view, link) })
            }
            override fun configureTheme(builder: MarkwonTheme.Builder) {
                builder.linkColor(colors.primary.toArgb()).codeTextColor(colors.onSurface.toArgb())
                    .codeBlockTextColor(colors.onSurface.toArgb()).codeBackgroundColor(colors.surfaceContainer.toArgb())
                    .codeBlockBackgroundColor(colors.surfaceContainer.toArgb()).headingBreakHeight(0)
                    .headingTextSizeMultipliers(floatArrayOf(1.45f, 1.3f, 1.15f, 1.05f, 1f, 1f))
            }
        }).build()
    }
    AndroidView(modifier = Modifier.fillMaxWidth(), factory = { TextView(it).apply {
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        setTextIsSelectable(true); setPadding(0, 0, 0, 0)
    } }, update = { view ->
        val presentation = Triple(text, colors, typography)
        if (view.tag != presentation) {
            val previous = view.tag as? Triple<*, *, *>
            if (previous?.second != colors || previous?.third != typography) {
                view.setTextColor(colors.onSurface.toArgb())
                view.textSize = 17f
                view.setLineSpacing(6 * view.resources.displayMetrics.density, 1.2f)
            }
            markwon.setMarkdown(view, text)
            view.movementMethod = LinkMovementMethod.getInstance()
            view.linksClickable = true
            view.tag = presentation
            view.requestLayout()
        }
    })
}

private class VisibleStrongEmphasisSpan : MetricAffectingSpan() {
    override fun updateDrawState(paint: TextPaint) { paint.isFakeBoldText = true }
    override fun updateMeasureState(paint: TextPaint) { paint.isFakeBoldText = true }
}
