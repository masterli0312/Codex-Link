package com.codex.quota.ui.feature.taskhistory

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.codex.quota.R
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

internal object ConversationSwipePolicy {
    fun clamp(offset: Float, width: Float) = offset.coerceIn(-width.coerceAtLeast(0f), 0f)
    fun reveal(offset: Float, velocity: Float, width: Float, flingThreshold: Float): Boolean = width > 0f &&
        if (kotlin.math.abs(velocity) > flingThreshold) velocity < 0 else offset < -width * 0.4f
}

/** Drag only reveals buttons. No swipe distance or velocity performs a mutation. */
@Composable
internal fun ConversationSwipeRow(revealed: Boolean, onReveal: () -> Unit, onClose: () -> Unit,
    pinned: Boolean, archived: Boolean, enabled: Boolean, canManage: Boolean = enabled,
    onPin: () -> Unit, onRename: () -> Unit, onArchive: () -> Unit, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    val width = with(density) { 204.dp.toPx() }
    val velocityThreshold = with(density) { 400.dp.toPx() }
    var offset by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    var animation by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    fun settle(target: Float) {
        animation?.cancel()
        animation = scope.launch { animate(offset, target, animationSpec = tween(170)) { value, _ -> offset = value } }
    }
    LaunchedEffect(revealed, width, enabled) { if (!dragging) settle(if (revealed && enabled) -width else 0f) }
    val pin = stringResource(if (pinned) R.string.conversation_unpin else R.string.conversation_pin)
    val rename = stringResource(R.string.conversation_menu_rename)
    val archive = stringResource(if (archived) R.string.conversation_restore else R.string.conversation_menu_archive)
    fun action(block: () -> Unit) { onClose(); settle(0f); block() }
    Box(Modifier.fillMaxWidth().heightIn(min = 72.dp).clipToBounds().semantics {
        customActions = buildList {
            if (enabled) add(CustomAccessibilityAction(pin) { action(onPin); true })
            if (canManage) {
                add(CustomAccessibilityAction(rename) { action(onRename); true })
                add(CustomAccessibilityAction(archive) { action(onArchive); true })
            }
        }
    }.draggable(rememberDraggableState { delta -> offset = ConversationSwipePolicy.clamp(offset + delta, width) },
        orientation = Orientation.Horizontal, enabled = enabled,
        onDragStarted = { dragging = true; animation?.cancel(); onReveal() },
        onDragStopped = { velocity ->
            dragging = false
            val open = ConversationSwipePolicy.reveal(offset, velocity, width, velocityThreshold)
            if (open) onReveal() else onClose()
            settle(if (open) -width else 0f)
        })) {
        if (revealed || dragging) Row(Modifier.align(Alignment.CenterEnd).width(204.dp)
            .graphicsLayer { alpha = (-offset / width).coerceIn(0f, 1f) },
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SwipeAction(pin, Icons.Outlined.PushPin, enabled && revealed, false) { action(onPin) }
            SwipeAction(rename, Icons.Outlined.Edit, canManage && revealed, false) { action(onRename) }
            SwipeAction(archive, if (archived) Icons.Outlined.Unarchive else Icons.Outlined.Archive, canManage && revealed, true) { action(onArchive) }
        }
        Box(Modifier.fillMaxWidth().heightIn(min = 72.dp).offset { IntOffset(offset.roundToInt(), 0) }
            .background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.CenterStart) {
            content()
            if (revealed) Box(Modifier.matchParentSize().clickable(onClick = { onClose(); settle(0f) }))
        }
    }
}

@Composable
private fun RowScope.SwipeAction(label: String, icon: ImageVector, enabled: Boolean, emphasized: Boolean, onClick: () -> Unit) {
    Surface(onClick = onClick, enabled = enabled, shape = RoundedCornerShape(12.dp),
        color = if (emphasized) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f).height(64.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else 0.38f))
            Spacer(Modifier.height(5.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.38f))
        }
    }
}
