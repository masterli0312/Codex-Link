package com.codex.quota.ui.feature.taskhistory

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.codex.quota.R
import com.codex.quota.notifications.task.*

/** One finished answer; public execution details are available behind the elapsed row. */
@Composable
internal fun ConversationTurnBlock(entries: List<ConversationTimelineEntry>,completed: Boolean,durationMs: Long? = null,recordId: String? = null,onBrowseProcess: ()->Unit = {}) {
    if(entries.isEmpty()) return
    val turn = ConversationTimeline.turn(entries.first())
    var expanded by rememberSaveable(turn) { mutableStateOf(false) }
    val inputs = entries.filter { it is ConversationTimelineEntry.Message && it.value.role == "user" }
    val replies = entries.filterIsInstance<ConversationTimelineEntry.Message>().filter { it.value.role == "assistant" }
    val final = replies.lastOrNull { it.value.phase == "final_answer" || it.value.phase == "final" }
        ?: replies.lastOrNull { it.value.phase !in setOf("commentary","analysis") } ?: replies.lastOrNull()
    val process = entries.filterNot { it in inputs || it == final }
    @Composable fun entry(value: ConversationTimelineEntry,copy: Boolean = false) {
        when(value) {
            is ConversationTimelineEntry.Message -> ConversationMessage(value.value,showCopy = copy,recordId = recordId)
            is ConversationTimelineEntry.Activity -> RemoteActivityInline(value.value)
            is ConversationTimelineEntry.ActivityGroup -> RemoteActivityGroupInline(value.values)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if(!completed) entries.forEach { key(it.key) { entry(it) } }
        else {
            inputs.forEach { key(it.key) { entry(it) } }
            if(process.isNotEmpty() || durationMs != null) {
                val seconds = (durationMs ?: 0) / 1000
                Row(Modifier.fillMaxWidth().clickable { if (!expanded) onBrowseProcess(); expanded = !expanded }.padding(vertical = 6.dp),verticalAlignment = Alignment.CenterVertically) {
                    Text(if(durationMs == null) stringResource(R.string.remote_activity_title)
                        else stringResource(R.string.conversation_elapsed,seconds / 60,seconds % 60),
                        style = MaterialTheme.typography.bodyMedium,color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Icon(if(expanded) Icons.Outlined.ExpandMore else Icons.Outlined.ChevronRight,null,Modifier.size(18.dp),tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                if(expanded) process.forEach { key(it.key) { entry(it) } }
            }
            final?.let { entry(it,copy = true) }
        }
    }
}
