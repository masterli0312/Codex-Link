package com.codex.quota.ui.feature.taskhistory

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.codex.quota.R

@Composable
internal fun ConversationActivityIndicator(running: Boolean, unread: Boolean) {
    val blue = Color(0xFF2563EB)
    if (running) {
        val label = stringResource(R.string.conversation_activity_running)
        CircularProgressIndicator(Modifier.size(18.dp).semantics { contentDescription = label }, color = blue, strokeWidth = 2.dp)
    } else if (unread) {
        val label = stringResource(R.string.conversation_activity_unread)
        Box(Modifier.size(6.dp).background(blue, CircleShape).semantics { contentDescription = label })
    }
}
