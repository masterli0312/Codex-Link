package com.codex.quota.ui.feature.taskhistory

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** One stroke weight for composer actions and permissions; native vectors stay crisp at every density. */
internal object ComposerIcons {
    private fun icon(name: String, draw: PathBuilder.() -> Unit) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.7f,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, pathBuilder = draw)
    }.build()
    val Photo = icon("Photo") {
        moveTo(5f,3f); lineTo(19f,3f); curveTo(20.1f,3f,21f,3.9f,21f,5f); lineTo(21f,19f)
        curveTo(21f,20.1f,20.1f,21f,19f,21f); lineTo(5f,21f); curveTo(3.9f,21f,3f,20.1f,3f,19f)
        lineTo(3f,5f); curveTo(3f,3.9f,3.9f,3f,5f,3f); close()
        moveTo(3f,17f); lineTo(9f,11f); lineTo(18f,21f)
        moveTo(16f,7f); curveTo(18.2f,7f,18.2f,10f,16f,10f); curveTo(13.8f,10f,13.8f,7f,16f,7f); close()
    }
    val File = icon("File") {
        moveTo(14f,3f); lineTo(6f,3f); curveTo(4.9f,3f,4f,3.9f,4f,5f); lineTo(4f,19f)
        curveTo(4f,20.1f,4.9f,21f,6f,21f); lineTo(18f,21f); curveTo(19.1f,21f,20f,20.1f,20f,19f)
        lineTo(20f,9f); lineTo(14f,3f); lineTo(14f,9f); lineTo(20f,9f)
    }
    val Plan = icon("Plan") {
        moveTo(4f,6f); lineTo(20f,6f); moveTo(4f,12f); lineTo(10f,12f); moveTo(4f,18f); lineTo(8f,18f)
        moveTo(12f,16f); lineTo(15f,19f); lineTo(21f,12f)
    }
    val Goal = icon("Goal") {
        moveTo(21f,12f); curveTo(21f,17f,17f,21f,12f,21f); curveTo(7f,21f,3f,17f,3f,12f)
        curveTo(3f,7f,7f,3f,12f,3f); curveTo(17f,3f,21f,7f,21f,12f); close()
        moveTo(16f,12f); curveTo(16f,14.2f,14.2f,16f,12f,16f); curveTo(9.8f,16f,8f,14.2f,8f,12f)
        curveTo(8f,9.8f,9.8f,8f,12f,8f); curveTo(14.2f,8f,16f,9.8f,16f,12f); close()
    }
    val Branch = icon("Branch") {
        moveTo(6f,6f); lineTo(6f,17f); moveTo(6f,12f); curveTo(6f,8f,18f,14f,18f,6f)
        moveTo(6f,2f); curveTo(9f,2f,9f,6f,6f,6f); curveTo(3f,6f,3f,2f,6f,2f); close()
        moveTo(6f,17f); curveTo(9f,17f,9f,21f,6f,21f); curveTo(3f,21f,3f,17f,6f,17f); close()
        moveTo(18f,2f); curveTo(21f,2f,21f,6f,18f,6f); curveTo(15f,6f,15f,2f,18f,2f); close()
    }
    val Sliders = icon("Sliders") {
        moveTo(3f,7f); lineTo(7f,7f); moveTo(11f,7f); lineTo(21f,7f)
        moveTo(7f,7f); curveTo(7f,4.3f,11f,4.3f,11f,7f); curveTo(11f,9.7f,7f,9.7f,7f,7f); close()
        moveTo(3f,17f); lineTo(13f,17f); moveTo(17f,17f); lineTo(21f,17f)
        moveTo(13f,17f); curveTo(13f,14.3f,17f,14.3f,17f,17f); curveTo(17f,19.7f,13f,19.7f,13f,17f); close()
    }
    val Review = icon("Review") {
        moveTo(12f,3f); lineTo(20f,6f); lineTo(20f,12f); curveTo(20f,17f,16f,20f,12f,22f)
        curveTo(8f,20f,4f,17f,4f,12f); lineTo(4f,6f); lineTo(12f,3f); close()
        moveTo(8f,12f); lineTo(11f,15f); lineTo(16f,9f)
    }
    val Lock = icon("Lock") {
        moveTo(7f,10f); lineTo(7f,7f); curveTo(7f,1f,17f,1f,17f,7f); lineTo(17f,10f)
        moveTo(6f,10f); lineTo(18f,10f); curveTo(19.1f,10f,20f,10.9f,20f,12f); lineTo(20f,20f)
        curveTo(20f,21.1f,19.1f,22f,18f,22f); lineTo(6f,22f); curveTo(4.9f,22f,4f,21.1f,4f,20f)
        lineTo(4f,12f); curveTo(4f,10.9f,4.9f,10f,6f,10f); close(); moveTo(12f,15f); lineTo(12f,18f)
    }
    val Key = icon("Key") {
        moveTo(11f,14f); curveTo(8f,16f,4f,14f,3f,11f); curveTo(1f,7f,4f,3f,8f,3f)
        curveTo(12f,3f,15f,6f,14f,10f); lineTo(21f,17f); lineTo(21f,21f); lineTo(17f,21f)
        lineTo(17f,18f); lineTo(14f,18f); lineTo(14f,15f); lineTo(11f,14f); close()
        moveTo(6f,8f); curveTo(6f,6f,9f,6f,9f,8f); curveTo(9f,10f,6f,10f,6f,8f); close()
    }
}
