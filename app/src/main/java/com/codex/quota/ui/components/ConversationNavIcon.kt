package com.codex.quota.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

internal val ConversationNavIcon: ImageVector by lazy {
    ImageVector.Builder("Conversation", 24.dp, 24.dp, 24f, 24f, autoMirror = true).apply {
        path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(6f, 4f)
            lineTo(18f, 4f)
            curveTo(20f, 4f, 21f, 5.2f, 21f, 7f)
            lineTo(21f, 15f)
            curveTo(21f, 16.8f, 20f, 18f, 18f, 18f)
            lineTo(9f, 18f)
            lineTo(4f, 21f)
            lineTo(4f, 17.4f)
            curveTo(3.3f, 16.9f, 3f, 16.1f, 3f, 15f)
            lineTo(3f, 7f)
            curveTo(3f, 5.2f, 4f, 4f, 6f, 4f)
            close()
        }
    }.build()
}
