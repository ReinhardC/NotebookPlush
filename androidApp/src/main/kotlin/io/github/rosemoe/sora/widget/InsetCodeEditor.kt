package io.github.rosemoe.sora.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Drawable
import io.github.rosemoe.sora.graphics.TextRowParams
import io.github.rosemoe.sora.lang.styling.Spans
import io.github.rosemoe.sora.widget.layout.Layout
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Scrollable document margins for Sora 0.24.6, whose renderer ignores View padding.
 * This package grants access to its layout field; document text and row indexes stay unchanged.
 */
internal class InsetCodeEditor(context: Context) : CodeEditor(context) {
    val documentInset: Int get() = (88f * dpUnit).roundToInt()
    internal var drawingNativeRows = false

    override fun getNonPrintablePaintingFlags(): Int = super.getNonPrintablePaintingFlags().let {
        if (drawingNativeRows) it and WhitespaceMarkers.inv() else it
    }

    init {
        // Equal explicit margins replace Sora's default extra half-screen below the last line.
        setVerticalExtraSpaceFactor(0f)
    }

    override fun createLayout(clearWordwrapCache: Boolean) {
        (layout as? InsetLayout)?.let { layout = it.content }
        super.createLayout(clearWordwrapCache)
        layout = InsetLayout(layout, documentInset)
    }

    override fun setTabWidth(width: Int) {
        if (width == tabWidth) return
        super.setTabWidth(width)
        // Sora 0.24.6 invalidates painting but leaves measured widths and wrap rows cached.
        if (layout != null) createLayout(true)
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

private val WhitespaceMarkers = CodeEditor.FLAG_DRAW_WHITESPACE_LEADING or CodeEditor.FLAG_DRAW_WHITESPACE_INNER or
    CodeEditor.FLAG_DRAW_WHITESPACE_TRAILING or CodeEditor.FLAG_DRAW_WHITESPACE_FOR_EMPTY_LINE

private class InsetLayout(val content: Layout, private val inset: Int) : Layout by content {
    override fun getLayoutHeight() = content.layoutHeight + inset * 2
    override fun getVisualPositionForLayoutOffset(x: Float, y: Float) =
        content.getVisualPositionForLayoutOffset(x, y - inset)
    override fun getCharPositionForLayoutOffset(x: Float, y: Float) =
        content.getCharPositionForLayoutOffset(x, y - inset)
    // Sora's character offsets already use the editor's inset-aware getRowBottom().
}

private class InsetRenderer(private val insetEditor: InsetCodeEditor) : EditorRenderer(insetEditor) {
    private val whitespacePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun drawView(canvas: Canvas) {
        val whitespace = insetEditor.nonPrintablePaintingFlags and WhitespaceMarkers != 0
        // Sora's private whitespace painter adds row-zero's top twice with document margins.
        // Keep its text/line-ending rendering, then paint visible spaces and tabs in row coordinates.
        insetEditor.drawingNativeRows = whitespace
        try { super.drawView(canvas) } finally { insetEditor.drawingNativeRows = false }
        if (whitespace && insetEditor.isEditable()) drawWhitespace(canvas)
    }

    private fun drawWhitespace(canvas: Canvas) {
        val editor = insetEditor
        whitespacePaint.color = editor.colorScheme.getColor(EditorColorScheme.NON_PRINTABLE_CHAR)
        whitespacePaint.strokeWidth = editor.dpUnit * .7f
        val saved = canvas.save()
        try {
            canvas.clipRect(editor.measureTextRegionOffset(), 0f, editor.width.toFloat(), editor.height.toFloat())
            for (rowIndex in editor.firstVisibleRow..editor.lastVisibleRow) {
                val row = editor.layout.getRowAt(rowIndex)
                val textRow = createTextRow(rowIndex)
                val origin = editor.measureTextRegionOffset() - editor.offsetX + row.renderTranslateX
                val centerY = editor.getRowTopOfText(rowIndex) - editor.offsetY + editor.rowHeightOfText / 2f
                textRow.iterateDrawTextRegions(row.startColumn, row.endColumn, canvas,
                    max(0f, -origin), editor.width - origin, false) {
                    _, chars, index, count, contextIndex, contextCount, rtl, horizontalOffset, width, _, _ ->
                    for (column in index until index + count) {
                        val character = chars[column]
                        if (character == ' ' || character == '\t') {
                            val advance = textRow.measureAdvanceInRun(column, index, column,
                                contextIndex, contextIndex + contextCount, rtl)
                            val start = origin + horizontalOffset + if (rtl) width - advance else advance
                            val size = paintGeneral.spaceWidth * if (character == '\t') editor.tabWidth else 1
                            val end = start + if (rtl) -size else size
                            if (character == ' ') canvas.drawCircle((start + end) / 2, centerY, editor.dpUnit * .7f, whitespacePaint)
                            else {
                                val left = min(start, end) + editor.dpUnit
                                val right = max(start, end) - editor.dpUnit
                                canvas.drawLine(left, centerY, right, centerY, whitespacePaint)
                                val tip = if (rtl) left else right
                                val tail = tip + if (rtl) editor.dpUnit * 2 else -editor.dpUnit * 2
                                canvas.drawLine(tail, centerY - editor.dpUnit * 1.5f, tip, centerY, whitespacePaint)
                                canvas.drawLine(tail, centerY + editor.dpUnit * 1.5f, tip, centerY, whitespacePaint)
                            }
                        }
                    }
                }
            }
        } finally { canvas.restoreToCount(saved) }
    }

    override fun drawMiniGraph(canvas: Canvas, offset: Float, row: Int, graph: Drawable?) {
        if (row != -1) { super.drawMiniGraph(canvas, offset, row, graph); return }
        // Wrapped line-end markers are drawn inside a canvas already translated to the row.
        val saved = canvas.save()
        try {
            canvas.translate(0f, -insetEditor.documentInset.toFloat())
            super.drawMiniGraph(canvas, offset, row, graph)
        } finally { canvas.restoreToCount(saved) }
    }

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
