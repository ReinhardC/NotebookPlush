package com.notebookplush

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.notebookplush.model.Document
import com.notebookplush.model.Workspace
import com.notebookplush.resources.Res
import com.notebookplush.resources.plush_notebook
import com.notebookplush.ui.EditorIcons
import com.notebookplush.ui.CloseAllTabsIcon
import com.notebookplush.ui.CloseOtherTabsIcon
import org.jetbrains.compose.resources.painterResource

// Match Easynews's BarChrome: 70% chrome, with 30% of the scrolling text showing through.
private const val ToolbarOpacity = .7f

@Composable
fun App(
    workspace: Workspace,
    saveStatus: String,
    position: String,
    canUndo: Boolean,
    canRedo: Boolean,
    wordWrap: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onWrapChanged: () -> Unit,
    onOpen: () -> Unit,
    onSaveAs: () -> Unit,
    onNew: () -> Unit,
    onSelect: (Long) -> Unit,
    onCloseTabs: (Set<Long>, Long?, (Long) -> Unit) -> Unit,
    onRename: (Long, String) -> Unit,
    showIndentGuides: Boolean,
    showWhitespace: Boolean,
    onIndentGuidesChanged: (Boolean) -> Unit,
    onWhitespaceChanged: (Boolean) -> Unit,
    fullScreen: Boolean,
    onFullScreenChanged: (Boolean) -> Unit,
    tabSize: Int,
    onTabSizeChanged: (Int) -> Unit,
    automaticUpdates: Boolean,
    onAutomaticUpdatesChanged: (Boolean) -> Unit,
    updateCheckReady: Boolean,
    onCheckUpdates: () -> Unit,
    closing: List<Document>? = null,
    onCancelClose: () -> Unit = {},
    onConfirmClose: () -> Unit = {},
    editor: @Composable (Modifier, Boolean) -> Unit,
    overlays: @Composable () -> Unit = {},
) {
    val dark = isSystemInDarkTheme()
    val colors = if (dark) {
        darkColors(primary = Color(0xFFA8C8FF), background = Color(0xFF161D29),
            surface = Color(0xFF202B3A), onSurface = Color(0xFFE8EEF8),
            onBackground = Color(0xFFE8EEF8), onPrimary = Color(0xFF182C4B))
    } else {
        lightColors(primary = Color(0xFF416DA7), background = Color(0xFFEAF0F8),
            surface = Color(0xFFFFFDF8), onSurface = Color(0xFF293B52),
            onBackground = Color(0xFF293B52), onPrimary = Color.White)
    }
    MaterialTheme(colors = colors) {
        var settingsOpen by rememberSaveable { mutableStateOf(false) }
        var showingSettings by rememberSaveable { mutableStateOf(false) }
        var lastDocumentId by rememberSaveable { mutableStateOf(workspace.activeId) }
        var renamingId by rememberSaveable { mutableStateOf<Long?>(null) }
        LaunchedEffect(workspace.activeId) {
            if (lastDocumentId != workspace.activeId) showingSettings = false
            lastDocumentId = workspace.activeId
        }
        val muted = colors.onSurface.copy(alpha = .6f)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(colors.background, colors.surface)))) {
            Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
                // Draw chrome over the full editor viewport so text scrolls beneath both bars.
                if (showingSettings) EditorSettings(showIndentGuides, showWhitespace,
                    onIndentGuidesChanged, onWhitespaceChanged, fullScreen, onFullScreenChanged,
                    tabSize, onTabSizeChanged, automaticUpdates, onAutomaticUpdatesChanged,
                    updateCheckReady, onCheckUpdates)
                else editor(Modifier.fillMaxSize(), dark)
                FileBar(workspace,
                    onNew = { showingSettings = false; onNew() },
                    onSelect = { showingSettings = false; onSelect(it) },
                    onClose = { id -> onCloseTabs(setOf(id), null) { lastDocumentId = it } },
                    onCloseAll = {
                        onCloseTabs(workspace.documents.map { it.id }.toSet(), null) {
                            lastDocumentId = it; settingsOpen = false; showingSettings = false
                        }
                    },
                    onCloseOthers = { keepId ->
                        onCloseTabs(workspace.documents.filterNot { it.id == keepId }.map { it.id }.toSet(), keepId) {
                            lastDocumentId = it; settingsOpen = false; showingSettings = false
                        }
                    },
                    onRename = { renamingId = it },
                    settingsOpen = settingsOpen, showingSettings = showingSettings,
                    onSettings = { showingSettings = true },
                    onCloseSettings = { settingsOpen = false; showingSettings = false },
                    onCloseOtherForSettings = {
                        onCloseTabs(workspace.documents.map { it.id }.toSet(), null) {
                            lastDocumentId = it; showingSettings = true
                        }
                    })
                BoxWithConstraints(
                    Modifier.align(Alignment.BottomStart).fillMaxWidth().height(46.dp)
                        .background(toolbarColor())
                        .padding(horizontal = 12.dp),
                ) {
                    val showPosition = maxWidth >= 416.dp && !showingSettings
                    Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                        Tool(EditorIcons.Open, "Open file", { showingSettings = false; onOpen() })
                        Tool(EditorIcons.Save, "Save as", onSaveAs, !showingSettings)
                        Spacer(Modifier.width(8.dp))
                        Tool(EditorIcons.Undo, "Undo", onUndo, canUndo && !showingSettings)
                        Tool(EditorIcons.Redo, "Redo", onRedo, canRedo && !showingSettings)
                        Tool(EditorIcons.Wrap, "Word wrap", onWrapChanged, !showingSettings, active = wordWrap)
                        Spacer(Modifier.width(8.dp))
                        Text(saveStatus, color = muted, fontSize = 11.sp, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Spacer(Modifier.width(8.dp))
                        if (showPosition) {
                            Text(position, color = muted, fontSize = 11.sp)
                            Spacer(Modifier.width(8.dp))
                        }
                        Tool(EditorIcons.Settings, "Settings", {
                            settingsOpen = true
                            showingSettings = true
                        }, active = showingSettings)
                    }
                }
            }
        }
        if (closing != null) AlertDialog(
            onDismissRequest = onCancelClose,
            title = { Text(if (closing.size == 1) "Close ${closing.single().name.ifBlank { "untitled.json" }}?" else "Close ${closing.size} tabs?") },
            text = { Text(if (closing.size == 1)
                "This file has changes that have not been exported. Closing it removes its local draft. Use Save as to keep a file copy."
                else "${closing.count { it.modified }} of these files have changes that have not been exported. Closing the tabs removes their local drafts. Use Save as to keep file copies.") },
            confirmButton = { TextButton(onClick = onConfirmClose) { Text(if (closing.size == 1) "Close file" else "Close tabs") } },
            dismissButton = { TextButton(onClick = onCancelClose) { Text("Keep open") } },
        )
        workspace.documents.firstOrNull { it.id == renamingId }?.let { document ->
            RenameDialog(document, onDismiss = { renamingId = null }, onRename = {
                onRename(document.id, it); renamingId = null
            })
        }
        overlays()
    }
}

@Composable
private fun FileBar(
    workspace: Workspace,
    onNew: () -> Unit,
    onSelect: (Long) -> Unit,
    onClose: (Long) -> Unit,
    onCloseAll: () -> Unit,
    onCloseOthers: (Long) -> Unit,
    onRename: (Long) -> Unit,
    settingsOpen: Boolean,
    showingSettings: Boolean,
    onSettings: () -> Unit,
    onCloseSettings: () -> Unit,
    onCloseOtherForSettings: () -> Unit,
) {
    val colors = MaterialTheme.colors
    val newTabReveal = remember { BringIntoViewRequester() }
    // The mark deliberately overflows this slim strip onto the text below it.
    Box(Modifier.fillMaxWidth().height(52.dp).background(toolbarColor())) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(start = 90.dp, end = 4.dp)) {
            Row(Modifier.fillMaxSize().horizontalScroll(rememberScrollState()).widthIn(min = maxWidth),
                verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End)) {
                workspace.documents.forEach { document ->
                    key(document.id) {
                        val active = !showingSettings && document.id == workspace.activeId
                        val reveal = remember { BringIntoViewRequester() }
                        LaunchedEffect(workspace.activeId, workspace.documents.size, showingSettings) {
                            if (active) {
                                if (document.id == workspace.documents.last().id) newTabReveal.bringIntoView()
                                reveal.bringIntoView()
                            }
                        }
                        FileTab(document.name, document.modified,
                            if (document.name.endsWith(".json", true)) EditorIcons.Json else EditorIcons.File,
                            active, { onSelect(document.id) }, { onClose(document.id) }, onCloseAll,
                            { onCloseOthers(document.id) }, workspace.documents.size > 1 || settingsOpen,
                            { onRename(document.id) },
                            Modifier.bringIntoViewRequester(reveal))
                    }
                }
                if (settingsOpen) {
                    val reveal = remember { BringIntoViewRequester() }
                    LaunchedEffect(showingSettings) {
                        if (showingSettings) { newTabReveal.bringIntoView(); reveal.bringIntoView() }
                    }
                    FileTab("Settings", false, EditorIcons.Settings, showingSettings, onSettings,
                        onCloseSettings, onCloseAll, onCloseOtherForSettings, workspace.documents.isNotEmpty(),
                        null, Modifier.bringIntoViewRequester(reveal))
                }
                NewFileTab(onNew, Modifier.bringIntoViewRequester(newTabReveal))
            }
        }
        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(1.dp).background(
            Brush.horizontalGradient(listOf(Color.Transparent, colors.primary, colors.primary.copy(alpha = .15f), Color.Transparent))))
        Image(
            painterResource(Res.drawable.plush_notebook), "NotebookPlush logo",
            Modifier.align(Alignment.TopStart).offset(x = 2.dp, y = 2.dp)
                .wrapContentSize(Alignment.TopStart, unbounded = true).requiredSize(80.dp)
                .drawBehind {
                    val radius = size.minDimension * .48f
                    drawCircle(Brush.radialGradient(listOf(Color.Black.copy(alpha = .22f), Color.Transparent),
                        Offset(center.x, center.y + 8.dp.toPx()), radius), radius, Offset(center.x, center.y + 8.dp.toPx()))
                },
        )
    }
}

@Composable
private fun NewFileTab(onClick: () -> Unit, modifier: Modifier) {
    val colors = MaterialTheme.colors
    val shape = RoundedCornerShape(topStart = 11.dp, topEnd = 11.dp)
    Box(modifier.width(40.dp).height(46.dp).shadow(2.dp, shape)
        .background(colors.surface.copy(alpha = .65f), shape).clip(shape)
        .clickable(role = Role.Button, onClick = onClick)
        .semantics { contentDescription = "New file" }, contentAlignment = Alignment.Center) {
        Text("+", color = colors.onSurface.copy(alpha = .7f), fontSize = 20.sp)
    }
}

@Composable
private fun FileTab(name: String, modified: Boolean, icon: ImageVector, active: Boolean,
    onClick: () -> Unit, onClose: () -> Unit, onCloseAll: () -> Unit,
    onCloseOthers: () -> Unit, hasOtherTabs: Boolean, onRename: (() -> Unit)?, modifier: Modifier) {
    val colors = MaterialTheme.colors
    var menu by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(topStart = 11.dp, topEnd = 11.dp)
    val face = if (active) colors.surface else colors.surface.copy(alpha = .65f)
    Box(modifier) {
        Row(Modifier.height(46.dp).widthIn(min = 88.dp, max = 168.dp).shadow(if (active) 6.dp else 2.dp, shape)
            .background(face, shape).clip(shape).combinedClickable(role = Role.Tab, onClick = onClick,
                onLongClick = { menu = true }, onLongClickLabel = "Tab options")
            .semantics { selected = active; contentDescription = if (icon == EditorIcons.Settings) "Settings tab" else "File tab: $name" }
            .drawBehind {
                if (active) drawRect(colors.primary, Offset(0f, size.height - 3.dp.toPx()),
                    androidx.compose.ui.geometry.Size(size.width, 3.dp.toPx()))
            }.padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon,
                null, tint = if (active) colors.primary else colors.onSurface.copy(alpha = .6f), modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(name.ifBlank { "untitled.json" }, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (active) colors.primary else colors.onSurface.copy(alpha = .7f), fontSize = 13.sp,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier.weight(1f, fill = false))
            if (modified) Text(" •", color = colors.primary, fontSize = 16.sp)
        }
        MaterialTheme(shapes = MaterialTheme.shapes.copy(small = RoundedCornerShape(12.dp))) {
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false },
                modifier = Modifier.widthIn(min = 220.dp, max = 360.dp)) {
                // Borrow Easynews's full-title band, including its bleed into the menu's 8 dp padding.
                Row(Modifier.layout { measurable, constraints ->
                    val bleed = 8.dp.roundToPx()
                    val placeable = measurable.measure(constraints)
                    layout(placeable.width, placeable.height - bleed) { placeable.place(0, -bleed) }
                }.fillMaxWidth().background(lerp(colors.surface, Color.Black, .08f))
                    .padding(start = 14.dp, end = 16.dp, top = 15.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(9.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(3.dp).height(17.dp).background(colors.primary, RoundedCornerShape(2.dp)))
                    Text(name, color = colors.onSurface, fontFamily = FontFamily.Serif, fontSize = 17.sp)
                }
                Divider(color = colors.onSurface.copy(alpha = .12f))
                TabMenuItem("Close tab", EditorIcons.Close) { menu = false; onClose() }
                TabMenuItem("Close all tabs", CloseAllTabsIcon) { menu = false; onCloseAll() }
                TabMenuItem("Close other tabs", CloseOtherTabsIcon, enabled = hasOtherTabs) { menu = false; onCloseOthers() }
                if (onRename != null) TabMenuItem("Rename", EditorIcons.Rename) { menu = false; onRename() }
            }
        }
    }
}

@Composable
private fun TabMenuItem(label: String, icon: ImageVector, enabled: Boolean = true, onClick: () -> Unit) {
    DropdownMenuItem(onClick = onClick, enabled = enabled) {
        Icon(icon, null, Modifier.size(18.dp))
        Spacer(Modifier.width(12.dp))
        Text(label, fontSize = 14.sp)
    }
}

@Composable
private fun RenameDialog(document: Document, onDismiss: () -> Unit, onRename: (String) -> Unit) {
    var name by rememberSaveable(document.id, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(document.name, TextRange(0, document.name.length)))
    }
    val focus = remember { FocusRequester() }
    val filename = name.text.trim()
    val valid = filename.isNotEmpty() && filename.none { it == '/' || it == '\\' || it.isISOControl() }
    LaunchedEffect(document.id) { focus.requestFocus() }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text("Rename tab") },
        text = { OutlinedTextField(name, onValueChange = { name = it }, singleLine = true,
            label = { Text("Filename") }, isError = !valid, modifier = Modifier.fillMaxWidth().focusRequester(focus)) },
        confirmButton = { TextButton(onClick = { onRename(filename) }, enabled = valid) { Text("Rename") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable
private fun Tool(icon: ImageVector, label: String, onClick: () -> Unit, enabled: Boolean = true, active: Boolean = false) {
    IconButton(onClick = onClick, enabled = enabled,
        modifier = Modifier.size(44.dp).semantics { selected = active }) {
        Icon(icon, label, Modifier.size(18.dp), tint = when {
            !enabled -> MaterialTheme.colors.onSurface.copy(alpha = .25f)
            active -> MaterialTheme.colors.primary
            else -> MaterialTheme.colors.onSurface.copy(alpha = .7f)
        })
    }
}

@Composable
private fun toolbarColor(): Color {
    val colors = MaterialTheme.colors
    return lerp(colors.surface, Color.Black, if (colors.isLight) .1f else .22f).copy(alpha = ToolbarOpacity)
}

@Composable
private fun EditorSettings(indentGuides: Boolean, whitespace: Boolean,
    onIndentGuidesChanged: (Boolean) -> Unit, onWhitespaceChanged: (Boolean) -> Unit,
    fullScreen: Boolean, onFullScreenChanged: (Boolean) -> Unit,
    tabSize: Int, onTabSizeChanged: (Int) -> Unit,
    automaticUpdates: Boolean, onAutomaticUpdatesChanged: (Boolean) -> Unit,
    updateCheckReady: Boolean, onCheckUpdates: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
        .padding(start = 24.dp, end = 24.dp, top = 88.dp, bottom = 88.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingToggle("Indent guides", "Show guides along nested JSON blocks", indentGuides, onIndentGuidesChanged)
        SettingToggle("Show whitespace", "Mark spaces, tabs, and line endings", whitespace, onWhitespaceChanged)
        TabSizeSetting(tabSize, onTabSizeChanged)
        SettingToggle("Full screen", "Hide system bars; swipe from an edge to show them briefly", fullScreen, onFullScreenChanged)
        SettingToggle("Auto check for updates", "Check once at startup; Later pauses reminders for a week",
            automaticUpdates, onAutomaticUpdatesChanged)
        OutlinedButton(onClick = onCheckUpdates, enabled = updateCheckReady) { Text("Check now") }
    }
}

@Composable
private fun SettingToggle(title: String, description: String, checked: Boolean, onChanged: (Boolean) -> Unit) {
    SettingRow(title, description, Modifier.toggleable(value = checked, role = Role.Switch, onValueChange = onChanged)) {
        Switch(checked, onCheckedChange = null,
            colors = SwitchDefaults.colors(checkedThumbColor = MaterialTheme.colors.primary))
    }
}

@Composable
private fun TabSizeSetting(tabSize: Int, onChanged: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    SettingRow("Tab size", "Width of tab characters and indentation steps") {
        Box {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.semantics { contentDescription = "Tab size" }) {
                Text("$tabSize ${if (tabSize == 1) "space" else "spaces"}")
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                (1..8).forEach { size ->
                    DropdownMenuItem(onClick = { expanded = false; onChanged(size) }) {
                        Text("$size ${if (size == 1) "space" else "spaces"}")
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingRow(title: String, description: String, interaction: Modifier = Modifier,
    content: @Composable () -> Unit) {
    Row(Modifier.widthIn(max = 560.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp))
        .background(MaterialTheme.colors.surface)
        .then(interaction).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = MaterialTheme.colors.onSurface, fontSize = 15.sp)
            Text(description, color = MaterialTheme.colors.onSurface.copy(alpha = .6f), fontSize = 12.sp)
        }
        Spacer(Modifier.width(16.dp))
        content()
    }
}
