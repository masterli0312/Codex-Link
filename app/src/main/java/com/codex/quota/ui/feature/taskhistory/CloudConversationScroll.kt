package com.codex.quota.ui.feature.taskhistory

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import kotlinx.coroutines.flow.first
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

internal class ConversationScrollHandle(val state: LazyListState,val browse: ()->Unit)

/** Follow streamed growth, while a reader dragging into history keeps their place. */
@Composable
internal fun rememberCloudConversationScroll(thread: String, ready: Boolean, acceptedInput: String): ConversationScrollHandle {
    val state = key(thread) { rememberLazyListState() }
    var positioned by remember(thread) { mutableStateOf(false) }
    var follow by remember(thread) { mutableStateOf(true) }
    var dragging by remember(thread) { mutableStateOf(false) }
    val tolerance = with(LocalDensity.current) { 48.dp.roundToPx() }
    fun nearEnd(): Boolean {
        val layout = state.layoutInfo
        val last = layout.visibleItemsInfo.lastOrNull() ?: return false
        return ConversationScrollPolicy.nearEnd(layout.totalItemsCount,last.index,
            last.offset + last.size + layout.afterContentPadding,layout.viewportEndOffset,tolerance)
    }
    LaunchedEffect(state,thread) {
        state.interactionSource.interactions.collect { interaction ->
            when(interaction) {
                is DragInteraction.Start -> dragging = true
                is DragInteraction.Stop, is DragInteraction.Cancel -> { follow = nearEnd(); dragging = false }
            }
        }
    }
    LaunchedEffect(state,thread) {
        snapshotFlow { Triple(dragging,state.isScrollInProgress,nearEnd()) }.collect { (drag,scroll,end) ->
            if(drag && scroll) follow = end
        }
    }
    LaunchedEffect(state,thread,ready,acceptedInput) {
        if(ready) {
            val count = snapshotFlow { state.layoutInfo.totalItemsCount }.first { it > 0 }
            state.scrollToItem(count-1,Int.MAX_VALUE)
            positioned = true; follow = true
        }
    }
    LaunchedEffect(state,thread) {
        snapshotFlow {
            val layout = state.layoutInfo
            val last = layout.visibleItemsInfo.lastOrNull()
            ConversationScrollPolicy.follow(layout.totalItemsCount,layout.totalItemsCount,last?.index ?: -1,
                (last?.offset ?: 0)+(last?.size ?: 0)+layout.afterContentPadding,layout.viewportEndOffset,
                positioned && follow,dragging || state.isScrollInProgress)
        }.collect { step -> when(step) {
            is ConversationScrollPolicy.Step.Move -> state.scrollBy(step.pixels.toFloat())
            is ConversationScrollPolicy.Step.Reveal -> state.scrollToItem(step.index)
            ConversationScrollPolicy.Step.None -> Unit
        } }
    }
    return ConversationScrollHandle(state) { follow = false }
}
