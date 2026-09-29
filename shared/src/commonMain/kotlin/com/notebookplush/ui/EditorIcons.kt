package com.notebookplush.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

internal object EditorIcons {
    private fun icon(name: String, draw: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            path(fill = null, stroke = SolidColor(Color.White), strokeLineWidth = 1.8f, pathBuilder = draw)
        }.build()
    val Add = icon("New file") { moveTo(12f, 4f); lineTo(12f, 20f); moveTo(4f, 12f); lineTo(20f, 12f) }
    val Close = icon("Close") { moveTo(6f, 6f); lineTo(18f, 18f); moveTo(18f, 6f); lineTo(6f, 18f) }
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
    val Updates = icon("App updates") {
        moveTo(12f, 3f); lineTo(12f, 15f); moveTo(7f, 10f); lineTo(12f, 15f); lineTo(17f, 10f)
        moveTo(4f, 16f); lineTo(4f, 21f); lineTo(20f, 21f); lineTo(20f, 16f)
    }
    val Focus = icon("Focus mode") {
        moveTo(3f, 9f); lineTo(3f, 3f); lineTo(9f, 3f); moveTo(15f, 3f); lineTo(21f, 3f); lineTo(21f, 9f)
        moveTo(21f, 15f); lineTo(21f, 21f); lineTo(15f, 21f); moveTo(9f, 21f); lineTo(3f, 21f); lineTo(3f, 15f)
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
