package com.notebookplush.update

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.AlertDialog
import androidx.compose.material.Button
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.notebookplush.BuildConfig

@Composable
internal fun UpdatesDialog(model: UpdatesViewModel) {
    val state by model.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var launchError by remember(state.dialogVisible) { mutableStateOf<String?>(null) }
    fun open(intent: Intent) {
        launchError = null
        try { context.startActivity(intent) }
        catch (_: Exception) { launchError = "Android could not open the installer. Try Install update again." }
    }
    // Registered even while hidden so rotation and the Android permission screen keep the request.
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (model.state.value.dialogVisible && model.state.value.downloaded) {
            if ((android.os.Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls())) model.install(::open)
            else launchError = "Install permission was not granted. Try Install update to allow it."
        }
    }
    LaunchedEffect(state.installRequested, state.busy) {
        // Download cleanup still owns the work slot after verification; wait until it releases
        // it before asking the ViewModel to install, or the single-operation guard drops it.
        if (state.installRequested && !state.busy) {
            // Consume before leaving the app: rotation or returning must not relaunch the installer.
            model.consumeInstallRequest()
            if (state.dialogVisible && state.downloaded) {
                launchError = null
                if ((android.os.Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls())) model.install(::open)
                else try {
                    permission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${context.packageName}")))
                } catch (_: Exception) {
                    launchError = "Allow NotebookPlush to install unknown apps in Android Settings, then try Install update."
                }
            }
        }
    }
    if (!state.dialogVisible) return

    AlertDialog(
        onDismissRequest = model::dismiss,
        title = { Text(if (state.available != null) "NotebookPlush update available" else "App updates") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Installed: ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.body2)
                Text(state.message)
                launchError?.let { Text(it, color = MaterialTheme.colors.error) }
                if (state.busy) {
                    val size = state.available?.manifest?.size ?: 0
                    if (size > 0 && state.downloadedBytes > 0) {
                        LinearProgressIndicator(
                            progress = (state.downloadedBytes.toFloat() / size).coerceIn(0f, 1f),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text("${state.downloadedBytes / 1024} / ${size / 1024} KB")
                    } else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                if (state.available != null) {
                    Text("Android will ask you to confirm installation. Later hides automatic reminders for a week.",
                        style = MaterialTheme.typography.body2)
                }
            }
        },
        confirmButton = {
            if (state.busy) {
                TextButton(onClick = model::cancel) { Text("Cancel") }
            } else if (state.available != null) {
                Button(onClick = model::updateNow) {
                    Text(if (state.downloaded) "Install update"
                        else "Update now (${state.available!!.manifest.size / 1024 / 1024} MB)")
                }
            } else {
                TextButton(onClick = model::check) { Text("Check again") }
            }
        },
        dismissButton = {
            TextButton(onClick = model::dismiss) {
                Text(if (state.available != null) "Later" else "Close")
            }
        },
    )
}
