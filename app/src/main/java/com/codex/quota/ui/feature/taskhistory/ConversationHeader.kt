package com.codex.quota.ui.feature.taskhistory

import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.codex.quota.R
import com.codex.quota.notifications.task.ComputerConnection

@Composable
internal fun ConversationHeader(title: String, computer: ComputerConnection?, now: Long, remote: Boolean,
    onBack: () -> Unit, onStatus: () -> Unit, actions: @Composable () -> Unit) {
    val connected = computer?.recentlyReachable(now) == true
    val checking = computer?.checking == true || computer?.lastProbeAt == 0L
    val status = stringResource(if (connected) R.string.computer_connected else if (checking) R.string.computer_checking else R.string.computer_disconnected)
    val surface = MaterialTheme.colorScheme.surfaceContainerLowest
    Row(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(shape = CircleShape, color = surface) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back)) }
        }
        Surface(Modifier.weight(1f), shape = RoundedCornerShape(28.dp), color = surface) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    if (remote) Box(Modifier.size(18.dp)) {
                        Icon(Icons.Outlined.Computer, status, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Box(Modifier.size(6.dp).align(Alignment.BottomEnd).background(
                            if (connected) Color(0xFF008575) else if (checking) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.error, CircleShape))
                    }
                    Text(if (remote) computer?.name?.ifBlank { null } ?: stringResource(R.string.conversation_paired_computer)
                        else stringResource(R.string.conversation_read_only), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Surface(shape = RoundedCornerShape(28.dp), color = surface) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val statusLabel = stringResource(R.string.conversation_info)
                val statusColor = MaterialTheme.colorScheme.onSurfaceVariant
                IconButton(onClick = onStatus) {
                    Canvas(Modifier.size(20.dp).semantics { contentDescription = statusLabel }) {
                        drawArc(statusColor, -90f, 270f, false, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round))
                    }
                }
                Box { actions() }
            }
        }
    }
}
