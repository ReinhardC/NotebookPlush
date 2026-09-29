package com.notebookplush

import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.notebookplush.model.Workspace
import com.notebookplush.storage.WorkspaceStore
import java.io.File
import io.github.rosemoe.sora.lang.styling.TextStyle
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.util.IntPair
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class JsonEditorTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val preferences = context.getSharedPreferences("notebook", Context.MODE_PRIVATE)
    private var scenario: ActivityScenario<MainActivity>? = null
    private val sample = """{"name": "Plush 🧸", "count": 7, "enabled": true, "empty": null}"""

    @Before
    fun prepareDraft() {
        assertTrue("Device tests must use the isolated QA package", context.packageName.endsWith(".testing"))
        clearWorkspace()
        assertTrue(preferences.edit().clear().putString("name", "test.json").putString("text", sample).commit())
    }

    @After
    fun restoreDraft() {
        scenario?.close()
    }

    @Test
    fun jsonColorsPlainTextUndoRedoAndDraftRestoration() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        val colors = mutableListOf<Int>()
        val deadline = System.currentTimeMillis() + 8000
        while (System.currentTimeMillis() < deadline) {
            colors.clear()
            scenario!!.onActivity { activity ->
                val editor = findEditor(activity.window.decorView)!!
                val spans = editor.styles?.spans?.read()?.getSpansOnLine(0).orEmpty()
                for (token in listOf("name", "Plush", "7", "true")) {
                    val column = sample.indexOf(token)
                    val span = spans.lastOrNull { it.column <= column }
                    if (span != null) colors += editor.colorScheme.getColor(TextStyle.getForegroundColorId(span.style))
                }
            }
            if (colors.distinct().size == 4) break
            Thread.sleep(100)
        }
        assertEquals("Keys, strings, numbers and booleans should have distinct colors", 4, colors.distinct().size)
        scenario!!.onActivity { activity ->
            val editor = findEditor(activity.window.decorView)!!
            assertEquals(sample, editor.text.toString())
            assertTrue(editor.isLineNumberEnabled)
            editor.setSelection(0, sample.length)
            editor.insertText("\n", 1)
            assertTrue(editor.canUndo())
            assertEquals(sample + "\n", editor.text.toString())
            editor.undo()
            assertEquals(sample, editor.text.toString())
            assertTrue(editor.canRedo())
            editor.redo()
            assertEquals(sample + "\n", editor.text.toString())
        }
        scenario!!.recreate()
        scenario!!.onActivity { activity ->
            assertEquals(sample + "\n", findEditor(activity.window.decorView)!!.text.toString())
        }
        scenario!!.close()
        awaitWorkspace { it.active.text == sample + "\n" }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario!!.onActivity { activity ->
            assertEquals(sample + "\n", findEditor(activity.window.decorView)!!.text.toString())
        }
    }

    @Test
    fun tabsKeepIndependentTextUndoCursorAndSurviveImmediateRecreation() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        awaitEditor(sample)
        scenario!!.onActivity { activity ->
            val editor = findEditor(activity.window.decorView)!!
            editor.setSelection(0, sample.length)
            editor.insertText(" ", 1)
            activity.newDocument()
        }
        awaitEditor("")
        val second = """{"second": 2}"""
        scenario!!.onActivity { activity ->
            val editor = findEditor(activity.window.decorView)!!
            editor.insertText(second, second.length)
            editor.setSelection(0, 3)
            activity.selectDocument(1)
        }
        awaitEditor(sample + " ")
        scenario!!.onActivity { activity ->
            val editor = findEditor(activity.window.decorView)!!
            assertEquals(sample.length + 1, editor.cursor.leftColumn)
            assertTrue(editor.canUndo())
            editor.undo()
            assertEquals(sample, editor.text.toString())
            editor.redo()
            activity.selectDocument(2)
        }
        awaitEditor(second)
        scenario!!.onActivity { activity ->
            val editor = findEditor(activity.window.decorView)!!
            assertEquals(3, editor.cursor.leftColumn)
            editor.undo()
            assertEquals("", editor.text.toString())
            editor.redo()
            editor.setSelection(0, 3)
        }
        // Recreate before the debounce can save: the retained workspace must win over disk.
        scenario!!.recreate()
        awaitEditor(second)
        scenario!!.onActivity { activity ->
            assertEquals(3, findEditor(activity.window.decorView)!!.cursor.leftColumn)
        }
        awaitWorkspace { it.documents.size == 2 && it.activeId == 2L &&
            it.documents[0].text == sample + " " && it.documents[1].text == second }
        scenario!!.close()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        awaitEditor(second)
        scenario!!.onActivity { it.selectDocument(1) }
        awaitEditor(sample + " ")
        scenario!!.onActivity { it.closeDocument(1) }
        awaitEditor(second)
        scenario!!.onActivity { it.closeDocument(2) }
        awaitEditor("")
        awaitWorkspace { it.documents.size == 1 && it.active.text.isEmpty() }
    }

    @Test
    fun scrollingMarginsPreserveTextAndTouchCoordinatesWithAndWithoutWrapping() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        awaitEditor(sample)
        val document = (0 until 100).joinToString("\n") { "{\"row\":$it,\"text\":\"${"x".repeat(160)}\"}" }
        scenario!!.onActivity { findEditor(it.window.decorView)!!.setText(document) }
        for (wrap in listOf(false, true)) {
            scenario!!.onActivity { findEditor(it.window.decorView)!!.setWordwrap(wrap) }
            val deadline = System.currentTimeMillis() + 8000
            var ready = false
            while (System.currentTimeMillis() < deadline && !ready) {
                scenario!!.onActivity {
                    val editor = findEditor(it.window.decorView)!!
                    ready = editor.height > 0 && editor.isEditable()
                }
                if (!ready) Thread.sleep(50)
            }
            assertTrue("Wrapped layout must finish", ready)
            scenario!!.onActivity {
                val editor = findEditor(it.window.decorView)!!
                val inset = editor.getRowTop(0)
                assertEquals(document, editor.text.toString())
                assertEquals(100, editor.text.lineCount)
                assertTrue("First line clears the 80 dp logo", inset > 80 * editor.dpUnit)
                assertEquals(inset.toFloat(), editor.layout.getCharLayoutOffset(0, 0)[0] - editor.rowHeight, .1f)
                assertEquals(editor.layout.rowCount * editor.rowHeight + inset * 2, editor.layout.layoutHeight)

                fun checkTouch(line: Int) {
                    val offset = editor.layout.getCharLayoutOffset(line, 5)
                    val point = editor.getPointPositionOnScreen(
                        editor.measureTextRegionOffset() + offset[1] - editor.offsetX,
                        offset[0] - editor.rowHeight / 2f - editor.offsetY)
                    assertEquals("Inset-aware touched line", line, IntPair.getFirst(point))
                    assertEquals("Inset-aware touched column", 5, IntPair.getSecond(point))
                }
                val max = editor.scrollMaxY
                editor.scroller.startScroll(0, 0, 0, 0, 0)
                editor.scroller.abortAnimation()
                checkTouch(0)
                editor.scroller.startScroll(0, 0, 0, max, 0)
                editor.scroller.abortAnimation()
                assertEquals("Equal trailing margin at the scroll limit", (editor.height - inset).toFloat(),
                    editor.layout.getCharLayoutOffset(99, document.substringAfterLast('\n').length)[0] - editor.offsetY, .1f)
                checkTouch(99)
            }
        }
    }

    private fun clearWorkspace() {
        for (name in listOf("workspace.json", "workspace.json.bak", "workspace.json.new")) {
            File(context.filesDir, name).delete()
        }
    }

    private fun awaitWorkspace(predicate: (Workspace) -> Boolean) {
        val deadline = System.currentTimeMillis() + 8000
        while (System.currentTimeMillis() < deadline) {
            if (runCatching { predicate(WorkspaceStore(context).load()) }.getOrDefault(false)) return
            Thread.sleep(50)
        }
        fail("Workspace was not saved: ${WorkspaceStore(context).load()}")
    }

    private fun awaitEditor(text: String) {
        val deadline = System.currentTimeMillis() + 8000
        while (System.currentTimeMillis() < deadline) {
            var found = false
            scenario!!.onActivity { activity -> found = findEditor(activity.window.decorView)?.text.toString() == text }
            if (found) return
            Thread.sleep(50)
        }
        fail("Active editor did not contain the expected text")
    }

    private fun findEditor(view: View): CodeEditor? {
        if (view is CodeEditor) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) {
            findEditor(view.getChildAt(index))?.let { return it }
        }
        return null
    }
}
