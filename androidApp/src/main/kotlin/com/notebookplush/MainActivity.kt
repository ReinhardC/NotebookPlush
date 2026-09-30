package com.notebookplush

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material.AlertDialog
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.viewinterop.AndroidView
import com.notebookplush.model.Document
import com.notebookplush.model.Workspace
import com.notebookplush.storage.WorkspaceSession
import com.notebookplush.update.UpdatesDialog
import com.notebookplush.update.UpdatesViewModel
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.event.SelectionChangeEvent
import io.github.rosemoe.sora.lang.EmptyLanguage
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.InsetCodeEditor
import io.github.rosemoe.sora.widget.subscribeAlways

class MainActivity : ComponentActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private val session: WorkspaceSession by viewModels()
    private val fileWorker get() = session.fileWorker
    private val store get() = session.store
    private val updates: UpdatesViewModel by viewModels()
    private var workspace: Workspace
        get() = session.workspace
        set(value) { session.workspace = value }
    private var savedWorkspace: Workspace?
        get() = session.savedWorkspace
        set(value) { session.savedWorkspace = value }
    private var saveStatus: String
        get() = session.saveStatus
        set(value) { session.saveStatus = value }
    private var position by mutableStateOf("Ln 1, Col 1")
    private var canUndo by mutableStateOf(false)
    private var canRedo by mutableStateOf(false)
    private var wordWrap by mutableStateOf(false)
    private var showIndentGuides by mutableStateOf(true)
    private var showWhitespace by mutableStateOf(false)
    private data class TabCloseRequest(val documents: List<Document>, val keepId: Long?,
        val onClosed: (Long) -> Unit = {})
    private var closing by mutableStateOf<TabCloseRequest?>(null)
    private val restoreError get() = session.restoreError
    private val editors = mutableMapOf<Long, CodeEditor>()
    private var viewIntentHandled = false
    private var exportDocument: Document?
        get() = session.exportDocument
        set(value) { session.exportDocument = value }
    private val save = Runnable { saveWorkspace() }
    private val openFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) readDocument(uri)
    }
    private val saveAs = registerForActivityResult(object : ActivityResultContracts.CreateDocument("text/plain") {
        override fun createIntent(context: Context, input: String): Intent =
            super.createIntent(context, input).setType(if (input.endsWith(".json", true)) "application/json" else "text/plain")
    }) { uri ->
        val exported = exportDocument
        if (uri != null && exported != null) writeDocument(uri, exported)
        exportDocument = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewIntentHandled = savedInstanceState?.getBoolean("viewIntentHandled") ?: false
        enableEdgeToEdge()
        wordWrap = getSharedPreferences("notebook", MODE_PRIVATE).getBoolean("wrap", false)
        showIndentGuides = getSharedPreferences("notebook", MODE_PRIVATE).getBoolean("indentGuides", true)
        showWhitespace = getSharedPreferences("notebook", MODE_PRIVATE).getBoolean("whitespace", false)
        JsonHighlighting.initialize(applicationContext)
        setContent {
            App(
                workspace = workspace, saveStatus = saveStatus, position = position,
                canUndo = canUndo, canRedo = canRedo, wordWrap = wordWrap,
                showIndentGuides = showIndentGuides, showWhitespace = showWhitespace,
                onIndentGuidesChanged = { setDisplaySettings(it, showWhitespace) },
                onWhitespaceChanged = { setDisplaySettings(showIndentGuides, it) },
                onUndo = { activeEditor()?.undo(); refreshEditorState() },
                onRedo = { activeEditor()?.redo(); refreshEditorState() },
                onWrapChanged = {
                    wordWrap = !wordWrap
                    getSharedPreferences("notebook", MODE_PRIVATE).edit().putBoolean("wrap", wordWrap).apply()
                },
                onOpen = { openFile.launch(arrayOf("application/json", "text/*", "application/octet-stream")) },
                onSaveAs = {
                    captureEditors()
                    exportDocument = workspace.active
                    saveAs.launch(workspace.active.name.ifBlank { "untitled.json" })
                },
                onNew = { newDocument() }, onSelect = { selectDocument(it) },
                onCloseTabs = { ids, keepId, onClosed -> requestCloseTabs(ids, keepId, onClosed) },
                onRename = { id, name -> renameDocument(id, name) },
                closing = closing?.documents,
                onCancelClose = { cancelCloseTabs() },
                onConfirmClose = { confirmCloseTabs() },
                editor = { modifier, dark ->
                    key(workspace.activeId) {
                        AndroidView(
                            modifier = modifier,
                            factory = { context ->
                                val document = workspace.active
                                editors.getOrPut(document.id) {
                                    InsetCodeEditor(context).apply {
                                        contentDescription = "Code editor"
                                        typefaceText = Typeface.MONOSPACE
                                        setTextSize(16f)
                                        tabWidth = 2
                                        isLineNumberEnabled = true
                                        applyDisplaySettings(this)
                                        setWordwrap(wordWrap)
                                        JsonHighlighting.applyTheme(this, dark)
                                        setEditorLanguage(if (document.name.endsWith(".json", true)) TextMateLanguage.create("source.json", false) else EmptyLanguage())
                                        setText(document.text)
                                        val line = document.cursorLine.coerceIn(0, text.lineCount - 1)
                                        setSelection(line, document.cursorColumn.coerceIn(0, text.getColumnCount(line)))
                                        subscribeAlways<ContentChangeEvent> {
                                            change(workspace.update(document.id) { it.copy(text = text.toString()) })
                                            post { refreshEditorState() }
                                        }
                                        subscribeAlways<SelectionChangeEvent> { refreshEditorState() }
                                    }
                                }
                            },
                            update = { view ->
                                applyDisplaySettings(view)
                                view.setWordwrap(wordWrap)
                                if (view.tag != dark) JsonHighlighting.applyTheme(view, dark)
                                view.post { refreshEditorState() }
                            },
                        )
                    }
                },
                overlays = {
                    UpdatesDialog(updates)
                    restoreError?.let { message -> AlertDialog(
                        onDismissRequest = {}, title = { Text("Workspace recovery") }, text = { Text(message) },
                        confirmButton = { TextButton(onClick = { finish() }) { Text("Close app") } },
                    ) }
                },
            )
            val updateState by updates.state.collectAsState()
            LaunchedEffect(updateState.initialized, updateState.busy) { updates.checkAtStartup() }
        }
        if (!viewIntentHandled) openViewIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        viewIntentHandled = false
        openViewIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("viewIntentHandled", viewIntentHandled)
        super.onSaveInstanceState(outState)
    }

    private fun openViewIntent(request: Intent) {
        if (request.action != Intent.ACTION_VIEW || restoreError != null) return
        val uri = request.data ?: return
        if (uri.scheme != "content" && uri.scheme != "file") return
        // Keep the request pending until the read completes, so rotation during I/O retries it.
        readDocument(uri, request.type) {
            if (intent.data == uri) viewIntentHandled = true
        }
    }

    private fun activeEditor(): CodeEditor? = editors[workspace.activeId]

    internal fun setDisplaySettings(indentGuides: Boolean, whitespace: Boolean) {
        showIndentGuides = indentGuides
        showWhitespace = whitespace
        getSharedPreferences("notebook", MODE_PRIVATE).edit()
            .putBoolean("indentGuides", indentGuides).putBoolean("whitespace", whitespace).apply()
        editors.values.forEach { applyDisplaySettings(it) }
    }

    private fun applyDisplaySettings(editor: CodeEditor) {
        if (editor.isBlockLineEnabled != showIndentGuides) editor.isBlockLineEnabled = showIndentGuides
        val flags = if (showWhitespace) {
            CodeEditor.FLAG_DRAW_WHITESPACE_LEADING or CodeEditor.FLAG_DRAW_WHITESPACE_INNER or
                CodeEditor.FLAG_DRAW_WHITESPACE_TRAILING or CodeEditor.FLAG_DRAW_WHITESPACE_FOR_EMPTY_LINE or
                CodeEditor.FLAG_DRAW_LINE_SEPARATOR
        } else 0
        if (editor.nonPrintablePaintingFlags != flags) editor.nonPrintablePaintingFlags = flags
    }

    private fun refreshEditorState() {
        val editor = activeEditor()
        canUndo = editor?.canUndo() == true
        canRedo = editor?.canRedo() == true
        position = if (editor == null) "Ln 1, Col 1" else "Ln ${editor.cursor.leftLine + 1}, Col ${editor.cursor.leftColumn + 1}"
    }

    private fun captureEditors() {
        workspace = workspace.copy(documents = workspace.documents.map { document ->
            editors[document.id]?.let { editor -> document.copy(text = editor.text.toString(),
                cursorLine = editor.cursor.leftLine, cursorColumn = editor.cursor.leftColumn) } ?: document
        })
    }

    private fun change(updated: Workspace) {
        if (updated != workspace && restoreError == null) {
            workspace = updated
            saveStatus = "Saving files…"
            handler.removeCallbacks(save)
            handler.postDelayed(save, 500)
        }
    }

    internal fun newDocument() {
        captureEditors()
        change(workspace.add(Document(workspace.nextId())))
        refreshEditorState()
    }

    internal fun selectDocument(id: Long) {
        captureEditors()
        change(workspace.select(id))
        refreshEditorState()
    }

    internal fun closeDocument(id: Long) {
        closeTabs(TabCloseRequest(workspace.documents.filter { it.id == id }, null))
    }

    internal fun requestCloseTabs(ids: Set<Long>, keepId: Long? = null, onClosed: (Long) -> Unit = {}) {
        captureEditors()
        val request = TabCloseRequest(workspace.documents.filter { it.id in ids }, keepId, onClosed)
        if (request.documents.any { it.modified }) closing = request else closeTabs(request)
    }

    internal fun cancelCloseTabs() { closing = null }

    internal fun confirmCloseTabs() {
        closing?.let { closeTabs(it) }
        closing = null
    }

    private fun closeTabs(request: TabCloseRequest) {
        captureEditors()
        val ids = request.documents.map { it.id }.toSet()
        ids.forEach { editors.remove(it)?.release() }
        change(workspace.close(ids, request.keepId))
        refreshEditorState()
        request.onClosed(workspace.activeId)
    }

    internal fun renameDocument(id: Long, name: String) {
        val filename = name.trim()
        if (filename.isEmpty() || filename.any { it == '/' || it == '\\' || it.isISOControl() }) return
        captureEditors()
        change(workspace.update(id) { it.copy(name = filename) })
        applyLanguage(id)
    }

    private fun applyLanguage(id: Long) {
        val document = workspace.documents.firstOrNull { it.id == id } ?: return
        val editor = editors[id] ?: return
        val json = document.name.endsWith(".json", true)
        if (json != (editor.editorLanguage is TextMateLanguage)) {
            editor.setEditorLanguage(if (json) TextMateLanguage.create("source.json", false) else EmptyLanguage())
        }
    }

    private fun saveWorkspace() {
        if (restoreError != null) return
        captureEditors()
        val snapshot = workspace
        if (snapshot == savedWorkspace) { saveStatus = "Files saved locally"; return }
        fileWorker.execute {
            val result = runCatching { store.save(snapshot) }
            runOnUiThread {
                result.onSuccess {
                    savedWorkspace = snapshot
                    if (workspace == snapshot) saveStatus = "Files saved locally"
                }.onFailure { saveStatus = "Could not save files locally" }
            }
        }
    }

    private fun readDocument(uri: Uri, mimeType: String? = null, onComplete: () -> Unit = {}) {
        captureEditors()
        val existing = workspace.documents.firstOrNull { it.sourceUri == uri.toString() }
        if (existing != null) {
            selectDocument(existing.id)
            onComplete()
            return
        }
        fileWorker.execute {
            val result = runCatching {
                val name = runCatching {
                    contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                        if (it.moveToFirst()) it.getString(0)?.takeIf(String::isNotBlank) else null
                    }
                }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf {
                    it.endsWith(".json", true) || it.endsWith(".txt", true)
                } ?: if ((mimeType ?: runCatching { contentResolver.getType(uri) }.getOrNull()) == "text/plain") "untitled.txt" else "untitled.json"
                val text = requireNotNull(contentResolver.openInputStream(uri)).bufferedReader(Charsets.UTF_8).use { it.readText() }
                Document(0, name, text, uri.toString(), savedText = text)
            }
            runOnUiThread {
                if (!isDestroyed) {
                    result.fold(
                        onSuccess = { loaded ->
                            captureEditors()
                            val existing = workspace.documents.firstOrNull { it.sourceUri == uri.toString() }
                            if (existing != null) selectDocument(existing.id)
                            else change(workspace.add(loaded.copy(id = workspace.nextId())))
                            refreshEditorState()
                        },
                        onFailure = { Toast.makeText(this, "Could not open file: ${it.localizedMessage}", Toast.LENGTH_LONG).show() },
                    )
                    onComplete()
                }
            }
        }
    }

    private fun writeDocument(uri: Uri, exported: Document) {
        fileWorker.execute {
            val result = runCatching {
                requireNotNull(contentResolver.openOutputStream(uri, "wt")).writer(Charsets.UTF_8).use { it.write(exported.text) }
                // Save as owns naming now that the duplicate filename field is gone.
                runCatching {
                    contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                        if (it.moveToFirst()) it.getString(0) else null
                    }
                }.getOrNull() ?: exported.name
            }
            runOnUiThread {
                if (!isDestroyed) {
                    result.onSuccess { name ->
                        change(workspace.update(exported.id) { it.copy(name = name, sourceUri = uri.toString(), savedText = exported.text) })
                        applyLanguage(exported.id)
                    }
                    Toast.makeText(this, result.fold({ "File saved" }, { "Could not save file: ${it.localizedMessage}" }), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onPause() {
        handler.removeCallbacks(save)
        saveWorkspace()
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacks(save)
        editors.values.forEach { it.release() }
        editors.clear()
        super.onDestroy()
    }
}
