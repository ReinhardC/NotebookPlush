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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.TextStyle
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

@Composable
fun App(
    workspace: Workspace,
    saveStatus: String,
    position: String,
    canUndo: Boolean,
    canRedo: Boolean,
    wordWrap: Boolean,
    onNameChanged: (String) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onWrapChanged: () -> Unit,
    onOpen: () -> Unit,
    onSaveAs: () -> Unit,
    onNew: () -> Unit,
    onSelect: (Long) -> Unit,
    onClose: (Long) -> Unit,
    onUpdates: () -> Unit,
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
    var focusMode by rememberSaveable { mutableStateOf(false) }
    MaterialTheme(colors = colors) {
        val muted = colors.onSurface.copy(alpha = .6f)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(colors.background, colors.surface)))) {
            Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
                // The chrome is drawn after the editor, so the oversized mark overlaps the page.
                Column(Modifier.fillMaxSize().padding(top = 52.dp, bottom = 46.dp)) {
                    Row(Modifier.fillMaxWidth().height(54.dp).padding(start = 116.dp, end = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        BasicTextField(
                            value = workspace.active.name, onValueChange = onNameChanged, singleLine = true,
                            textStyle = TextStyle(color = colors.onSurface, fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
                            cursorBrush = SolidColor(colors.primary),
                            modifier = Modifier.weight(1f).semantics { contentDescription = "File name" },
                            decorationBox = { inner -> Box {
                                if (workspace.active.name.isEmpty()) Text("untitled.json", color = muted)
                                inner()
                            } },
                        )
                        if (!focusMode) {
                            Tool(EditorIcons.Open, "Open file", onOpen)
                            Tool(EditorIcons.Save, "Save as", onSaveAs)
                        }
                    }
                    editor(Modifier.fillMaxWidth().weight(1f), dark)
                }
                FileBar(workspace, onNew, onSelect, onClose, onUpdates,
                    focusMode, { focusMode = !focusMode })
                Row(
                    Modifier.align(Alignment.BottomStart).fillMaxWidth().height(46.dp)
                        .background(colors.surface.copy(alpha = .35f))
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (!focusMode) {
                        Tool(EditorIcons.Undo, "Undo", onUndo, canUndo)
                        Tool(EditorIcons.Redo, "Redo", onRedo, canRedo)
                        Tool(EditorIcons.Wrap, "Word wrap", onWrapChanged, active = wordWrap)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(saveStatus, color = muted, fontSize = 11.sp, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    Text(position, color = muted, fontSize = 11.sp)
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
    onUpdates: () -> Unit,
    focusMode: Boolean,
    onFocus: () -> Unit,
) {
    val colors = MaterialTheme.colors
    Box(Modifier.fillMaxWidth().height(52.dp).background(colors.surface.copy(alpha = .35f))) {
        Row(Modifier.fillMaxSize().padding(start = 114.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).fillMaxHeight().horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                workspace.documents.forEach { document ->
                    key(document.id) {
                        val active = document.id == workspace.activeId
                        val reveal = remember { BringIntoViewRequester() }
                        LaunchedEffect(workspace.activeId, workspace.documents.size) {
                            if (active) reveal.bringIntoView()
                        }
                        FileTab(document, active, { onSelect(document.id) }, { onClose(document.id) },
                            Modifier.bringIntoViewRequester(reveal))
                    }
                }
            }
            Tool(EditorIcons.Add, "New file", onNew)
            if (!focusMode) Tool(EditorIcons.Updates, "App updates", onUpdates)
            Tool(EditorIcons.Focus, if (focusMode) "Show tools" else "Focus mode", onFocus, active = focusMode)
        }
        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(1.dp).background(
            Brush.horizontalGradient(listOf(Color.Transparent, colors.primary, colors.primary.copy(alpha = .15f), Color.Transparent))))
        Image(
            painterResource(Res.drawable.plush_notebook), "NotebookPlush logo",
            Modifier.align(Alignment.TopStart).offset(x = 8.dp, y = 4.dp)
                .wrapContentSize(Alignment.TopStart, unbounded = true).requiredSize(96.dp)
                .drawBehind {
                    val radius = size.minDimension * .48f
                    drawCircle(Brush.radialGradient(listOf(Color.Black.copy(alpha = .22f), Color.Transparent),
                        Offset(center.x, center.y + 8.dp.toPx()), radius), radius, Offset(center.x, center.y + 8.dp.toPx()))
                },
        )
    }
}

@Composable
private fun FileTab(document: Document, active: Boolean, onClick: () -> Unit, onClose: () -> Unit, modifier: Modifier) {
    val colors = MaterialTheme.colors
    val shape = RoundedCornerShape(topStart = 11.dp, topEnd = 11.dp)
    val face = if (active) colors.surface else colors.surface.copy(alpha = .65f)
    Row(modifier.height(46.dp).widthIn(min = 104.dp, max = 208.dp).shadow(if (active) 6.dp else 2.dp, shape)
        .background(face, shape).clip(shape).clickable(onClick = onClick)
        .semantics { selected = active; contentDescription = "File tab: ${document.name}" }
        .drawBehind {
            if (active) drawRect(colors.primary, Offset(0f, size.height - 3.dp.toPx()),
                androidx.compose.ui.geometry.Size(size.width, 3.dp.toPx()))
        }.padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (document.name.endsWith(".json", true)) EditorIcons.Json else EditorIcons.File,
            null, tint = if (active) colors.primary else colors.onSurface.copy(alpha = .6f), modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(7.dp))
        Text(document.name.ifBlank { "untitled.json" }, maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = if (active) colors.primary else colors.onSurface.copy(alpha = .7f), fontSize = 13.sp,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.weight(1f, fill = false))
        if (document.modified) Text(" •", color = colors.primary, fontSize = 16.sp)
        IconButton(onClick = onClose, modifier = Modifier.size(44.dp)) {
            Icon(EditorIcons.Close, "Close ${document.name}", Modifier.size(14.dp), tint = colors.onSurface.copy(alpha = .55f))
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
