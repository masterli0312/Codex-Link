package com.codex.quota.ui.feature.taskhistory

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.codex.quota.R

/** Used only before the first confirmed frame. Streaming never clears readable messages. */
@Composable
internal fun ConversationLoadingPlaceholder(modifier: Modifier = Modifier) {
    val label = stringResource(R.string.conversation_syncing_latest)
    val color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
    Column(modifier.semantics { contentDescription = label }.padding(horizontal = 20.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Box(Modifier.fillMaxWidth(0.78f).height(58.dp).align(Alignment.End).background(color, RoundedCornerShape(18.dp)))
        Box(Modifier.fillMaxWidth().height(92.dp).background(color, RoundedCornerShape(18.dp)))
    }
}
