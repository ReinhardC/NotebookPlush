package com.notebookplush.storage

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import com.notebookplush.model.Document
import com.notebookplush.model.Workspace
import java.util.concurrent.Executors

/** Keep the latest draft and ordered disk writes alive across Activity recreation. */
internal class WorkspaceSession(application: Application) : AndroidViewModel(application) {
    val store = WorkspaceStore(application)
    val fileWorker = Executors.newSingleThreadExecutor()
    var workspace by mutableStateOf(Workspace())
    var savedWorkspace: Workspace? = null
    var saveStatus by mutableStateOf("Files saved locally")
    var restoreError by mutableStateOf<String?>(null)
    var exportDocument: Document? = null

    init {
        runCatching { store.load() }.onSuccess {
            workspace = it
            savedWorkspace = it
        }.onFailure {
            restoreError = "The saved workspace could not be read. Its file has been preserved. Restart after recovering workspace.json from app storage."
            saveStatus = "Workspace could not be restored"
        }
    }

    override fun onCleared() {
        // Already queued autosaves finish even when the Activity closes.
        fileWorker.shutdown()
    }
}
