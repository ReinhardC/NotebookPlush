package com.notebookplush

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.notebookplush.model.Document
import com.notebookplush.model.Workspace
import com.notebookplush.resources.Res
import com.notebookplush.resources.plush_notebook
import com.notebookplush.ui.EditorIcons
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
    onClose: (Long) -> Unit,
    showIndentGuides: Boolean,
    showWhitespace: Boolean,
    onIndentGuidesChanged: (Boolean) -> Unit,
    onWhitespaceChanged: (Boolean) -> Unit,
    closing: Document? = null,
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
        LaunchedEffect(workspace.activeId) {
            if (lastDocumentId != workspace.activeId) showingSettings = false
            lastDocumentId = workspace.activeId
        }
        val muted = colors.onSurface.copy(alpha = .6f)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(colors.background, colors.surface)))) {
            Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
                // Draw chrome over the full editor viewport so text scrolls beneath both bars.
                if (showingSettings) EditorSettings(showIndentGuides, showWhitespace,
                    onIndentGuidesChanged, onWhitespaceChanged)
                else editor(Modifier.fillMaxSize(), dark)
                FileBar(workspace,
                    onNew = { showingSettings = false; onNew() },
                    onSelect = { showingSettings = false; onSelect(it) }, onClose = onClose,
                    settingsOpen = settingsOpen, showingSettings = showingSettings,
                    onSettings = { showingSettings = true },
                    onCloseSettings = { settingsOpen = false; showingSettings = false })
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
            title = { Text("Close ${closing.name.ifBlank { "untitled.json" }}?") },
            text = { Text("This file has changes that have not been exported. Closing it removes its local draft. Use Save as to keep a file copy.") },
            confirmButton = { TextButton(onClick = onConfirmClose) { Text("Close file") } },
            dismissButton = { TextButton(onClick = onCancelClose) { Text("Keep open") } },
        )
        overlays()
    }
}

@Composable
private fun FileBar(
    workspace: Workspace,
    onNew: () -> Unit,
    onSelect: (Long) -> Unit,
    onClose: (Long) -> Unit,
    settingsOpen: Boolean,
    showingSettings: Boolean,
    onSettings: () -> Unit,
    onCloseSettings: () -> Unit,
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
                            active, { onSelect(document.id) }, { onClose(document.id) },
                            Modifier.bringIntoViewRequester(reveal))
                    }
                }
                if (settingsOpen) {
                    val reveal = remember { BringIntoViewRequester() }
                    LaunchedEffect(showingSettings) {
                        if (showingSettings) { newTabReveal.bringIntoView(); reveal.bringIntoView() }
                    }
                    FileTab("Settings", false, EditorIcons.Settings, showingSettings, onSettings,
                        onCloseSettings, Modifier.bringIntoViewRequester(reveal))
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
    Box(modifier.width(48.dp).height(46.dp).shadow(2.dp, shape)
        .background(colors.surface.copy(alpha = .65f), shape).clip(shape)
        .clickable(role = Role.Button, onClick = onClick)
        .semantics { contentDescription = "New file" }, contentAlignment = Alignment.Center) {
        Text("+", color = colors.onSurface.copy(alpha = .7f), fontSize = 22.sp)
    }
}

@Composable
private fun FileTab(name: String, modified: Boolean, icon: ImageVector, active: Boolean,
    onClick: () -> Unit, onClose: () -> Unit, modifier: Modifier) {
    val colors = MaterialTheme.colors
    val shape = RoundedCornerShape(topStart = 11.dp, topEnd = 11.dp)
    val face = if (active) colors.surface else colors.surface.copy(alpha = .65f)
    Row(modifier.height(46.dp).widthIn(min = 104.dp, max = 208.dp).shadow(if (active) 6.dp else 2.dp, shape)
        .background(face, shape).clip(shape).clickable(role = Role.Tab, onClick = onClick)
        .semantics { selected = active; contentDescription = if (icon == EditorIcons.Settings) "Settings tab" else "File tab: $name" }
        .drawBehind {
            if (active) drawRect(colors.primary, Offset(0f, size.height - 3.dp.toPx()),
                androidx.compose.ui.geometry.Size(size.width, 3.dp.toPx()))
        }.padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon,
            null, tint = if (active) colors.primary else colors.onSurface.copy(alpha = .6f), modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(7.dp))
        Text(name.ifBlank { "untitled.json" }, maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = if (active) colors.primary else colors.onSurface.copy(alpha = .7f), fontSize = 13.sp,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.weight(1f, fill = false))
        if (modified) Text(" •", color = colors.primary, fontSize = 16.sp)
        IconButton(onClick = onClose, modifier = Modifier.size(44.dp)) {
            Icon(EditorIcons.Close, "Close $name", Modifier.size(14.dp), tint = colors.onSurface.copy(alpha = .55f))
        }
    }
}

@Composable
private fun Tool(icon: ImageVector, label: String, onClick: () -> Unit, enabled: Boolean = true, active: Boolean = false) {
    IconButton(onClick = onClick, enabled = enabled,
        modifier = Modifier.size(44.dp).semantics { selected = active }) {
        Icon(icon, label, Modifier.size(20.dp), tint = when {
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
    onIndentGuidesChanged: (Boolean) -> Unit, onWhitespaceChanged: (Boolean) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
        .padding(start = 24.dp, end = 24.dp, top = 88.dp, bottom = 88.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingToggle("Indent guides", "Show guides along nested JSON blocks", indentGuides, onIndentGuidesChanged)
        SettingToggle("Show whitespace", "Mark spaces, tabs, and line endings", whitespace, onWhitespaceChanged)
    }
}

@Composable
private fun SettingToggle(title: String, description: String, checked: Boolean, onChanged: (Boolean) -> Unit) {
    Row(Modifier.widthIn(max = 560.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp))
        .background(MaterialTheme.colors.surface)
        .toggleable(value = checked, role = Role.Switch, onValueChange = onChanged).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = MaterialTheme.colors.onSurface, fontSize = 15.sp)
            Text(description, color = MaterialTheme.colors.onSurface.copy(alpha = .6f), fontSize = 12.sp)
        }
        Spacer(Modifier.width(16.dp))
        Switch(checked, onCheckedChange = null,
            colors = SwitchDefaults.colors(checkedThumbColor = MaterialTheme.colors.primary))
    }
}
