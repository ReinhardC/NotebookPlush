package com.notebookplush.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * The tab menu's closing marks: tab glyphs stacked down the diagonal, front one bottom right, as
 * ContentCopy stacks its sheets. The stock layer and frame icons read as unrelated to Close tab's
 * plain X (2026-09-26), so these are built from that X. Close all is an X over an X; Close other
 * tabs is an X over a check over an X, the check the tab that stays. One tint colours every glyph,
 * so the stack leaves gaps between them rather than overlapping: overlapping same-coloured X's
 * merged into one long diagonal.
 */
internal val CloseAllTabsIcon: ImageVector by lazy {
    tabStackIcon("Close all tabs") {
        cross(2.5f, 8f)
        cross(13.5f, 8f)
    }
}

internal val CloseOtherTabsIcon: ImageVector by lazy {
    tabStackIcon("Close other tabs") {
        cross(1.5f, 5.5f)
        check(9.25f, 5.5f)
        cross(17f, 5.5f)
    }
}

/** Material's rounded icons stroke at about 2 units; a shade more keeps the small glyphs solid. */
private const val TabStackStroke = 2.2f

private class TabStack(private val builder: ImageVector.Builder) {
    private fun stroke(block: PathBuilder.() -> Unit) {
        builder.path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = TabStackStroke,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
            pathBuilder = block,
        )
    }

    /** An X filling the square of [size] whose top-left corner sits at ([origin], [origin]). */
    fun cross(origin: Float, size: Float) = stroke {
        moveTo(origin, origin); lineTo(origin + size, origin + size)
        moveTo(origin + size, origin); lineTo(origin, origin + size)
    }

    /** A check in the same square: short leg down from the left, long leg up to the top right. */
    fun check(origin: Float, size: Float) = stroke {
        moveTo(origin, origin + size * 0.55f)
        lineTo(origin + size * 0.38f, origin + size * 0.95f)
        lineTo(origin + size, origin + size * 0.12f)
    }
}

private fun tabStackIcon(name: String, glyphs: TabStack.() -> Unit): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply { TabStack(this).glyphs() }.build()
