package com.notebookplush

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsetsController
import androidx.core.view.WindowInsetsControllerCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelProvider
import com.notebookplush.model.Workspace
import com.notebookplush.model.Document
import com.notebookplush.storage.WorkspaceStore
import com.notebookplush.storage.WorkspaceSession
import com.notebookplush.storage.WorkspaceCodec
import java.io.File
import io.github.rosemoe.sora.lang.styling.TextStyle
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.lang.EmptyLanguage
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage
import io.github.rosemoe.sora.util.IntPair
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class JsonEditorTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val preferences = context.getSharedPreferences("notebook", Context.MODE_PRIVATE)
    private var scenario: ActivityScenario<MainActivity>? = null
    private val associationFixtures = mutableListOf<File>()
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
        if (scenario == null) {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                listOf(Stage.RESUMED, Stage.STARTED, Stage.PAUSED, Stage.STOPPED, Stage.CREATED)
                    .flatMap { ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(it) }
                    .filterIsInstance<MainActivity>().forEach { it.finish() }
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        }
        associationFixtures.forEach { it.delete() }
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
    fun renamingUpdatesLanguageWithoutLosingTextUndoCursorOrSourceFile() {
        val source = "content://example/original.json"
        WorkspaceStore(context).save(Workspace(listOf(Document(1, "original.json", sample, source, sample))))
        scenario = ActivityScenario.launch(MainActivity::class.java)
        awaitEditor(sample)
        scenario!!.onActivity { activity ->
            val editor = findEditor(activity.window.decorView)!!
            editor.setSelection(0, sample.length)
            editor.insertText(" ", 1)
            activity.renameDocument(1, "  notes.txt  ")
            assertSame(editor, findEditor(activity.window.decorView))
            assertTrue(editor.editorLanguage is EmptyLanguage)
            assertEquals(sample + " ", editor.text.toString())
            assertEquals(sample.length + 1, editor.cursor.leftColumn)
            editor.undo()
            assertEquals(sample, editor.text.toString())
            editor.redo()
            activity.renameDocument(1, "renamed.settings.json")
            assertTrue(editor.editorLanguage is TextMateLanguage)
            for (invalid in listOf(" ", "path/file.json", "path\\file.json", "name\n.json")) activity.renameDocument(1, invalid)
            val document = ViewModelProvider(activity)[WorkspaceSession::class.java].workspace.active
            assertEquals("renamed.settings.json", document.name)
            assertEquals(source, document.sourceUri)
            assertEquals(sample, document.savedText)
            assertEquals(sample + " ", document.text)
        }
        scenario!!.recreate()
        awaitEditor(sample + " ")
        awaitWorkspace { it.active.name == "renamed.settings.json" && it.active.sourceUri == source }
    }

    @Test
    fun bulkClosingConfirmsBeforeRemovingAnyDraftAndKeepsTheHeldTab() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        awaitEditor(sample)
        scenario!!.onActivity { it.newDocument() }
        awaitEditor("")
        scenario!!.onActivity {
            val editor = findEditor(it.window.decorView)!!
            editor.insertText("second", 6)
            editor.setSelection(0, 3)
            it.newDocument()
        }
        awaitEditor("")
        var closedActiveId: Long? = null
        scenario!!.onActivity { activity ->
            val session = ViewModelProvider(activity)[WorkspaceSession::class.java]
            activity.requestCloseTabs(setOf(1, 3), keepId = 2) { closedActiveId = it }
            assertEquals(listOf(1L, 2L, 3L), session.workspace.documents.map { it.id })
            assertNull(closedActiveId)
            activity.cancelCloseTabs()
            assertEquals(listOf(1L, 2L, 3L), session.workspace.documents.map { it.id })
            activity.requestCloseTabs(setOf(1, 3), keepId = 2) { closedActiveId = it }
            activity.confirmCloseTabs()
            assertEquals(listOf(2L), session.workspace.documents.map { it.id })
            assertEquals(2L, closedActiveId)
        }
        awaitEditor("second")
        scenario!!.onActivity { activity ->
            val editor = findEditor(activity.window.decorView)!!
            assertEquals(3, editor.cursor.leftColumn)
            assertTrue(editor.canUndo())
            editor.undo()
            assertEquals("", editor.text.toString())
            editor.redo()
            activity.requestCloseTabs(setOf(2))
            assertEquals("second", ViewModelProvider(activity)[WorkspaceSession::class.java].workspace.active.text)
            activity.confirmCloseTabs()
        }
        awaitEditor("")
        scenario!!.recreate()
        awaitEditor("")
        awaitWorkspace { it.documents.size == 1 && it.activeId == 3L && it.active.text.isEmpty() }
    }

    @Test
    fun fileAssociationsMatchJsonAndTxtWithoutClaimingOtherFilesOrWebLinks() {
        fun matches(uri: String, mime: String?): Boolean {
            val request = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(uri), mime)
                .setPackage(context.packageName)
            return context.packageManager.queryIntentActivities(request, PackageManager.MATCH_DEFAULT_ONLY)
                .any { it.activityInfo.name == MainActivity::class.java.name }
        }
        for (mime in listOf("application/json", "application/x-json", "text/json", "text/plain")) {
            assertTrue("Registered type $mime with an opaque provider URI", matches("content://example.documents/42", mime))
        }
        for (extension in listOf("json", "txt")) {
            for (mime in listOf(null, "application/octet-stream")) {
                assertTrue("Filename fallback for $extension / $mime",
                    matches("file:///storage/emulated/0/Download/my.settings.$extension", mime))
                assertTrue(matches("content://example.documents/files/notes.$extension", mime))
                if (Build.VERSION.SDK_INT >= 31) {
                    assertTrue(matches("content://example.documents/files/a.b.c.d.${extension.uppercase()}", mime))
                }
            }
        }
        assertFalse(matches("content://example.documents/photo.jpg", "image/jpeg"))
        assertFalse(matches("content://example.documents/archive.zip", "application/octet-stream"))
        assertFalse(matches("https://example.com/notes.json", "application/json"))
    }

    @Test
    fun externalJsonOpensAtLaunchAndPreservesDraftOnReopenAndRotation() {
        val original = """{"opened": "from a file manager", "unicode": "\u2603"}"""
        val request = associationIntent("external.settings.json", original, "application/json")
        launchExternal(request)
        awaitEditor(original)
        awaitWorkspace { it.documents.size == 2 && it.active.name == "external.settings.json" &&
            it.documents[0].text == sample && it.active.savedText == original }
        onExternalActivity {
            val editor = findEditor(it.window.decorView)!!
            assertTrue(editor.editorLanguage is TextMateLanguage)
            editor.setSelection(0, original.length)
            editor.insertText("\n", 1)
            it.startActivity(request)
        }
        awaitEditor(original + "\n")
        awaitWorkspace { it.documents.size == 2 && it.active.text == original + "\n" }
        assertEquals("Opening and editing must not overwrite the original file", original, associationFixtures.single().readText())
        onExternalActivity { it.selectDocument(1) }
        awaitEditor(sample)
        recreateExternalActivity()
        awaitEditor(sample)
        awaitWorkspace { it.documents.size == 2 && it.activeId == 1L }
    }

    @Test
    fun externalTxtReusesActivityAndRetriesAReadInterruptedByRotation() {
        val original = "Plain text\nSecond line, with a tab:\there\n"
        val request = associationIntent("external.notes.txt", original, "text/plain")
        launchExternal(Intent(context, MainActivity::class.java))
        awaitEditor(sample)
        lateinit var current: MainActivity
        onExternalActivity { current = it; it.startActivity(request) }
        awaitEditor(original)
        onExternalActivity {
            assertSame("Open with must reuse the existing workspace Activity", current, it)
            assertTrue(findEditor(it.window.decorView)!!.editorLanguage is EmptyLanguage)
        }
        awaitWorkspace { it.documents.size == 2 && it.active.name == "external.notes.txt" && it.documents[0].text == sample }

        val waiting = CountDownLatch(1)
        val release = CountDownLatch(1)
        val second = associationIntent("rotation.txt", "Survived rotation", "text/plain")
        try {
            onExternalActivity {
                ViewModelProvider(it)[WorkspaceSession::class.java].fileWorker.execute {
                    waiting.countDown()
                    release.await(8, TimeUnit.SECONDS)
                }
            }
            assertTrue(waiting.await(8, TimeUnit.SECONDS))
            onExternalActivity { it.startActivity(second) }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            recreateExternalActivity()
        } finally { release.countDown() }
        awaitEditor("Survived rotation")
        awaitWorkspace { it.documents.size == 3 && it.active.name == "rotation.txt" }
        recreateExternalActivity()
        awaitEditor("Survived rotation")
        awaitWorkspace { it.documents.size == 3 }
    }

    private fun associationIntent(name: String, text: String, mime: String): Intent {
        val directory = File(context.filesDir, "association-fixtures").apply { mkdirs() }
        val file = File(directory, name).apply { writeText(text, Charsets.UTF_8) }
        associationFixtures += file
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        return Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).setPackage(context.packageName)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    // ActivityScenario tracks the original launch intent and loses lifecycle updates when
    // onNewIntent calls setIntent. Use the lifecycle monitor for external-file launches.
    private fun externalActivity(): MainActivity? = listOf(Stage.RESUMED, Stage.STARTED, Stage.PAUSED)
        .flatMap { ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(it) }
        .filterIsInstance<MainActivity>().firstOrNull()

    private fun onExternalActivity(action: (MainActivity) -> Unit) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            action(requireNotNull(externalActivity()) { "Editor Activity is not running" })
        }
    }

    private fun launchExternal(request: Intent) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            context.startActivity(Intent(request).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        }
    }

    private fun recreateExternalActivity() {
        lateinit var previous: MainActivity
        onExternalActivity { previous = it; it.recreate() }
        val deadline = System.currentTimeMillis() + 8000
        while (System.currentTimeMillis() < deadline) {
            var recreated = false
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                recreated = externalActivity()?.let { it !== previous } == true
            }
            if (recreated) return
            Thread.sleep(50)
        }
        fail("Editor Activity was not recreated")
    }

    @Test
    fun displaySettingsPersistAcrossTabsAndRotationWithoutChangingText() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        awaitEditor(sample)
        val document = "    A\n\tB\n     \n"
        scenario!!.onActivity {
            val editor = findEditor(it.window.decorView)!!
            editor.setText(document)
            editor.setSelection(0, 2)
            it.setDisplaySettings(false, true)
            assertFalse(editor.isBlockLineEnabled)
            assertTrue(editor.nonPrintablePaintingFlags and CodeEditor.FLAG_DRAW_LINE_SEPARATOR != 0)
            assertEquals(document, editor.text.toString())
            assertEquals(2, editor.cursor.leftColumn)
            it.newDocument()
        }
        awaitEditor("")
        scenario!!.onActivity {
            val editor = findEditor(it.window.decorView)!!
            assertFalse(editor.isBlockLineEnabled)
            assertTrue(editor.nonPrintablePaintingFlags and CodeEditor.FLAG_DRAW_WHITESPACE_LEADING != 0)
            it.selectDocument(1)
        }
        awaitEditor(document)
        scenario!!.recreate()
        awaitEditor(document)
        for (wrap in listOf(false, true)) {
            scenario!!.onActivity { it.setWordWrap(wrap) }
            val deadline = System.currentTimeMillis() + 8000
            var ready = false
            while (System.currentTimeMillis() < deadline && !ready) {
                scenario!!.onActivity { ready = findEditor(it.window.decorView)!!.isEditable() }
                if (!ready) Thread.sleep(50)
            }
            assertTrue(ready)
            scenario!!.onActivity {
                val editor = findEditor(it.window.decorView)!!
                assertFalse(editor.isBlockLineEnabled)
                // Use an opaque test color so alpha blending/antialiasing cannot mask placement.
                val markerColor = 0xFFFF00FF.toInt()
                editor.colorScheme.setColor(EditorColorScheme.NON_PRINTABLE_CHAR, markerColor)
                val bitmap = Bitmap.createBitmap(editor.width, editor.height, Bitmap.Config.ARGB_8888)
                try {
                    editor.draw(Canvas(bitmap))
                    val from = editor.layout.getCharLayoutOffset(0, 0)[1]
                    val to = editor.layout.getCharLayoutOffset(0, 1)[1]
                    val x = (editor.measureTextRegionOffset() + (from + to) / 2 - editor.offsetX).toInt()
                    val y = (editor.getRowTopOfText(0) + editor.rowHeightOfText / 2f - editor.offsetY).toInt()
                    assertEquals("Whitespace dots align with the first row (wrap=$wrap)",
                        markerColor, bitmap.getPixel(x, y))
                } finally { bitmap.recycle() }
                assertEquals(document, editor.text.toString())
            }
        }
        scenario!!.onActivity { it.setDisplaySettings(true, false) }
        scenario!!.recreate()
        awaitEditor(document)
        scenario!!.onActivity {
            val editor = findEditor(it.window.decorView)!!
            assertTrue(editor.isBlockLineEnabled)
            assertEquals(0, editor.nonPrintablePaintingFlags)
            assertEquals(document, editor.text.toString())
        }
    }

    @Test
    fun tabSizeUpdatesMeasuredWidthsAndWrappedRowsWithoutChangingDrafts() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        awaitEditor(sample)
        val original = "\tA\n${"\t".repeat(100)}B\n"
        val edited = original.replace("A", "AX")
        scenario!!.onActivity {
            val editor = findEditor(it.window.decorView)!!
            assertEquals(2, editor.tabWidth)
            editor.setText(original)
            editor.setSelection(0, 2)
            editor.insertText("X", 1)
            editor.setSelection(0, 1)
        }
        for (wrap in listOf(false, true)) {
            scenario!!.onActivity { it.setWordWrap(wrap) }
            var narrowRows = 0
            for (size in listOf(1, 4, 8)) {
                scenario!!.onActivity { it.setTabSize(size) }
                awaitEditor(edited)
                scenario!!.onActivity {
                    val editor = findEditor(it.window.decorView)!!
                    assertEquals(size, editor.tabWidth)
                    val begin = editor.layout.getCharLayoutOffset(0, 0)[1]
                    val end = editor.layout.getCharLayoutOffset(0, 1)[1]
                    assertEquals("Tab geometry updates (wrap=$wrap, size=$size)",
                        editor.textPaint.spaceWidth * size, end - begin, .5f)
                    assertEquals(1, editor.cursor.leftColumn)
                    assertTrue(editor.canUndo())
                    if (size == 1) narrowRows = editor.layout.rowCount
                    if (wrap && size == 8) assertTrue("Wider tabs must recompute wrapped rows", editor.layout.rowCount > narrowRows)
                }
            }
        }
        scenario!!.onActivity { it.newDocument() }
        awaitEditor("")
        scenario!!.onActivity {
            assertEquals(8, findEditor(it.window.decorView)!!.tabWidth)
            it.selectDocument(1)
        }
        awaitEditor(edited)
        scenario!!.onActivity {
            val editor = findEditor(it.window.decorView)!!
            editor.undo()
            assertEquals(original, editor.text.toString())
            editor.redo()
            editor.setSelection(0, 1)
        }
        scenario!!.recreate()
        awaitEditor(edited)
        scenario!!.onActivity {
            val editor = findEditor(it.window.decorView)!!
            assertEquals(8, editor.tabWidth)
            assertEquals(1, editor.cursor.leftColumn)
        }
    }

    @Test
    fun fullScreenSurvivesRecreationAndRestoresSystemBarBehaviorWhenDisabled() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        awaitEditor(sample)
        var previousBehavior = 0
        var previousCaption = 0
        var sameEditor: CodeEditor? = null
        val edited = sample + " "
        scenario!!.onActivity {
            assertFalse(preferences.getBoolean("fullScreen", false))
            val view = it.window.decorView
            previousBehavior = WindowInsetsControllerCompat(it.window, view).systemBarsBehavior
            if (Build.VERSION.SDK_INT >= 35) previousCaption =
                (it.window.insetsController?.systemBarsAppearance ?: 0) and
                    WindowInsetsController.APPEARANCE_TRANSPARENT_CAPTION_BAR_BACKGROUND
            val editor = findEditor(view)!!
            sameEditor = editor
            editor.setSelection(0, sample.length)
            editor.insertText(" ", 1)
            it.setFullScreen(true)
        }
        fun awaitBars(enabled: Boolean) {
            val deadline = System.currentTimeMillis() + 8000
            while (System.currentTimeMillis() < deadline) {
                var ready = false
                scenario!!.onActivity {
                    val behavior = WindowInsetsControllerCompat(it.window, it.window.decorView).systemBarsBehavior
                    val expected = if (enabled) WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE else previousBehavior
                    val caption = if (Build.VERSION.SDK_INT >= 35)
                        (it.window.insetsController?.systemBarsAppearance ?: 0) and
                            WindowInsetsController.APPEARANCE_TRANSPARENT_CAPTION_BAR_BACKGROUND else 0
                    val expectedCaption = if (enabled && Build.VERSION.SDK_INT >= 35)
                        WindowInsetsController.APPEARANCE_TRANSPARENT_CAPTION_BAR_BACKGROUND else previousCaption
                    ready = (Build.VERSION.SDK_INT < 30 || behavior == expected) && caption == expectedCaption
                }
                if (ready) return
                Thread.sleep(50)
            }
            fail("System bar behavior did not switch (full screen=$enabled)")
        }
        awaitBars(true)
        scenario!!.onActivity {
            assertTrue(preferences.getBoolean("fullScreen", false))
            val editor = findEditor(it.window.decorView)!!
            assertSame(sameEditor, editor)
            assertEquals(edited, editor.text.toString())
            assertTrue(editor.canUndo())
        }
        scenario!!.recreate()
        awaitEditor(edited)
        awaitBars(true)
        scenario!!.onActivity { it.setFullScreen(false) }
        awaitBars(false)
        scenario!!.onActivity {
            assertFalse(preferences.getBoolean("fullScreen", true))
            assertEquals(edited, findEditor(it.window.decorView)!!.text.toString())
        }
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
        // AtomicFile.openRead() deletes .new; polling it during an autosave can discard that write.
        // Observe the atomically published base file without invoking recovery or mutating storage.
        val file = File(context.filesDir, "workspace.json")
        val deadline = System.currentTimeMillis() + 8000
        while (System.currentTimeMillis() < deadline) {
            if (runCatching { predicate(WorkspaceCodec.decode(file.readText(Charsets.UTF_8))) }.getOrDefault(false)) return
            Thread.sleep(50)
        }
        fail("Workspace was not saved: ${runCatching { file.readText(Charsets.UTF_8) }.getOrNull()}")
    }

    private fun awaitEditor(text: String) {
        val deadline = System.currentTimeMillis() + 8000
        while (System.currentTimeMillis() < deadline) {
            var found = false
            val activityScenario = scenario
            if (activityScenario != null) activityScenario.onActivity { activity ->
                found = findEditor(activity.window.decorView)?.let {
                    it.text.toString() == text && it.isEditable() && it.width > 0 && it.height > 0
                } == true
            } else InstrumentationRegistry.getInstrumentation().runOnMainSync {
                found = externalActivity()?.let { findEditor(it.window.decorView) }?.let {
                    it.text.toString() == text && it.isEditable() && it.width > 0 && it.height > 0
                } == true
            }
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
