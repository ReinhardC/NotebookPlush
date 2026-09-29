package com.notebookplush.storage

import android.content.Context
import android.util.AtomicFile
import com.notebookplush.model.Document
import com.notebookplush.model.Workspace
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

internal object WorkspaceCodec {
    fun encode(workspace: Workspace): String = JSONObject().put("schema", 1)
        .put("activeId", workspace.activeId)
        .put("documents", JSONArray().apply {
            workspace.documents.forEach { document ->
                put(JSONObject().put("id", document.id).put("name", document.name)
                    .put("text", document.text).put("sourceUri", document.sourceUri ?: JSONObject.NULL)
                    .put("savedText", document.savedText).put("cursorLine", document.cursorLine)
                    .put("cursorColumn", document.cursorColumn))
            }
        }).toString()

    fun decode(text: String): Workspace {
        val json = JSONObject(text)
        require(json.getInt("schema") == 1) { "Unsupported workspace format" }
        val items = json.getJSONArray("documents")
        val documents = (0 until items.length()).map { index ->
            val item = items.getJSONObject(index)
            Document(item.getLong("id"), item.getString("name"), item.getString("text"),
                if (item.isNull("sourceUri")) null else item.getString("sourceUri"),
                item.getString("savedText"), item.optInt("cursorLine").coerceAtLeast(0),
                item.optInt("cursorColumn").coerceAtLeast(0))
        }
        return Workspace(documents, json.getLong("activeId"))
    }
}

internal class WorkspaceStore(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "workspace.json"))
    private val preferences = context.getSharedPreferences("notebook", Context.MODE_PRIVATE)

    fun load(): Workspace {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) {
            // Keep the single-file prototype's draft when upgrading to tabs.
            return Workspace(listOf(Document(1, preferences.getString("name", "untitled.json").orEmpty(),
                preferences.getString("text", "").orEmpty())))
        }
        return WorkspaceCodec.decode(file.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() })
    }

    @Synchronized
    fun save(workspace: Workspace) {
        val bytes = WorkspaceCodec.encode(workspace).toByteArray(Charsets.UTF_8)
        val output = file.startWrite()
        try {
            output.write(bytes)
            file.finishWrite(output)
        } catch (failure: Exception) {
            file.failWrite(output)
            throw failure
        }
    }
}
