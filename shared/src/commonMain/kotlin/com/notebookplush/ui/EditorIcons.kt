package com.notebookplush.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

internal object EditorIcons {
    private fun icon(name: String, draw: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            path(fill = null, stroke = SolidColor(Color.White), strokeLineWidth = 1.8f, pathBuilder = draw)
        }.build()
    val Close = icon("Close") { moveTo(6f, 6f); lineTo(18f, 18f); moveTo(18f, 6f); lineTo(6f, 18f) }
    val Rename = icon("Rename") {
        moveTo(4f, 16f); lineTo(4f, 20f); lineTo(8f, 20f); lineTo(21f, 7f)
        lineTo(17f, 3f); close()
        moveTo(14f, 6f); lineTo(18f, 10f)
    }
    val Settings = icon("Settings") {
        repeat(32) { step ->
            val angle = step * PI / 16
            val radius = if (step % 4 == 1 || step % 4 == 2) 10.2 else 8.0
            val x = (12 + radius * cos(angle)).toFloat()
            val y = (12 + radius * sin(angle)).toFloat()
            if (step == 0) moveTo(x, y) else lineTo(x, y)
        }
        close()
        moveTo(15f, 12f)
        arcToRelative(3f, 3f, 0f, false, true, -6f, 0f)
        arcToRelative(3f, 3f, 0f, false, true, 6f, 0f)
        close()
    }
    val Open = icon("Open file") {
        moveTo(3f, 20f); lineTo(3f, 5f); lineTo(10f, 5f); lineTo(12f, 8f); lineTo(21f, 8f)
        lineTo(21f, 11f); moveTo(3f, 20f); lineTo(7f, 11f); lineTo(23f, 11f); lineTo(19f, 20f); close()
    }
    val Save = icon("Save file") {
        moveTo(4f, 3f); lineTo(17f, 3f); lineTo(21f, 7f); lineTo(21f, 21f); lineTo(4f, 21f); close()
        moveTo(8f, 3f); lineTo(8f, 9f); lineTo(16f, 9f); lineTo(16f, 3f)
        moveTo(8f, 21f); lineTo(8f, 14f); lineTo(17f, 14f); lineTo(17f, 21f)
    }
    val Undo = icon("Undo") {
        moveTo(8f, 5f); lineTo(3f, 10f); lineTo(8f, 15f); moveTo(3f, 10f); lineTo(15f, 10f)
        curveTo(23f, 10f, 23f, 21f, 14f, 21f)
    }
    val Redo = icon("Redo") {
        moveTo(16f, 5f); lineTo(21f, 10f); lineTo(16f, 15f); moveTo(21f, 10f); lineTo(9f, 10f)
        curveTo(1f, 10f, 1f, 21f, 10f, 21f)
    }
    val Wrap = icon("Word wrap") {
        moveTo(3f, 6f); lineTo(21f, 6f); moveTo(3f, 12f); lineTo(17f, 12f)
        curveTo(23f, 12f, 23f, 20f, 17f, 20f); lineTo(12f, 20f)
        moveTo(15f, 17f); lineTo(12f, 20f); lineTo(15f, 23f); moveTo(3f, 18f); lineTo(8f, 18f)
    }
    val File = icon("Text file") {
        moveTo(5f, 2f); lineTo(14f, 2f); lineTo(20f, 8f); lineTo(20f, 22f); lineTo(5f, 22f); close()
        moveTo(14f, 2f); lineTo(14f, 8f); lineTo(20f, 8f)
        moveTo(8f, 13f); lineTo(16f, 13f); moveTo(8f, 17f); lineTo(16f, 17f)
    }
    val Json = icon("JSON file") {
        moveTo(8f, 3f); curveTo(4f, 3f, 5f, 7f, 5f, 9f); lineTo(2f, 12f); lineTo(5f, 15f)
        curveTo(5f, 17f, 4f, 21f, 8f, 21f)
        moveTo(16f, 3f); curveTo(20f, 3f, 19f, 7f, 19f, 9f); lineTo(22f, 12f); lineTo(19f, 15f)
        curveTo(19f, 17f, 20f, 21f, 16f, 21f)
    }
}
