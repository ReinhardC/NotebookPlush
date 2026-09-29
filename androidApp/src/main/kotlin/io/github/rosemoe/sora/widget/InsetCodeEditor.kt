package io.github.rosemoe.sora.widget

import android.content.Context
import android.graphics.Canvas
import io.github.rosemoe.sora.graphics.TextRowParams
import io.github.rosemoe.sora.lang.styling.Spans
import io.github.rosemoe.sora.widget.layout.Layout
import kotlin.math.roundToInt

/**
 * Scrollable document margins for Sora 0.24.6, whose renderer ignores View padding.
 * This package grants access to its layout field; document text and row indexes stay unchanged.
 */
internal class InsetCodeEditor(context: Context) : CodeEditor(context) {
    val documentInset: Int get() = (88f * dpUnit).roundToInt()

    init {
        // Equal explicit margins replace Sora's default extra half-screen below the last line.
        setVerticalExtraSpaceFactor(0f)
    }

    override fun createLayout(clearWordwrapCache: Boolean) {
        (layout as? InsetLayout)?.let { layout = it.content }
        super.createLayout(clearWordwrapCache)
        layout = InsetLayout(layout, documentInset)
    }

    override fun setLayoutBusy(busy: Boolean) {
        val insetLayout = layout as? InsetLayout
        if (!busy && isWordwrap && insetLayout != null && eventHandler.positionNotApplied) {
            // Sora casts to WordwrapLayout when restoring the pinch-zoom focal point.
            val focus = eventHandler.focusY
            layout = insetLayout.content
            eventHandler.focusY = focus - documentInset
            try {
                super.setLayoutBusy(false)
            } finally {
                eventHandler.focusY = focus
                if (layout === insetLayout.content) layout = insetLayout
            }
        } else {
            super.setLayoutBusy(busy)
        }
    }

    override fun getRowTop(row: Int) = super.getRowTop(row) + documentInset
    override fun getRowBottom(row: Int) = super.getRowBottom(row) + documentInset
    override fun getFirstVisibleRow() = ((offsetY - documentInset) / rowHeight).coerceAtLeast(0)
    override fun getLastVisibleRow() = ((offsetY + height - documentInset) / rowHeight)
        .coerceIn(0, layout.rowCount - 1)

    override fun onCreateRenderer(): EditorRenderer = InsetRenderer(this)
}

private class InsetLayout(val content: Layout, private val inset: Int) : Layout by content {
    override fun getLayoutHeight() = content.layoutHeight + inset * 2
    override fun getVisualPositionForLayoutOffset(x: Float, y: Float) =
        content.getVisualPositionForLayoutOffset(x, y - inset)
    override fun getCharPositionForLayoutOffset(x: Float, y: Float) =
        content.getCharPositionForLayoutOffset(x, y - inset)
    // Sora's character offsets already use the editor's inset-aware getRowBottom().
}

private class InsetRenderer(private val insetEditor: InsetCodeEditor) : EditorRenderer(insetEditor) {
    override fun createTextRowParams(): TextRowParams {
        val local = super.createTextRowParams()
        val inset = insetEditor.documentInset
        // Glyph/cache geometry is relative to its row, rather than to the document origin.
        return local.copy(textTop = local.textTop - inset, textBottom = local.textBottom - inset,
            rowTop = local.rowTop - inset, rowBottom = local.rowBottom - inset)
    }

    override fun drawSingleTextLine(canvas: Canvas?, line: Int, offsetX: Float, offsetY: Float,
        spans: Spans.Reader?, visibleOnly: Boolean): Float {
        if (canvas == null) return super.drawSingleTextLine(null, line, offsetX, offsetY, spans, visibleOnly)
        val saved = canvas.save()
        try {
            canvas.translate(0f, -insetEditor.documentInset.toFloat())
            return super.drawSingleTextLine(canvas, line, offsetX, offsetY, spans, visibleOnly)
        } finally { canvas.restoreToCount(saved) }
    }
}
