package com.notebookplush.update

import android.app.Application
import android.content.ClipData
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.notebookplush.BuildConfig
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class UpdatesState(
    val busy: Boolean = false,
    val initialized: Boolean = false,
    val dialogVisible: Boolean = false,
    val installRequested: Boolean = false,
    val message: String = "Ready to check for updates.",
    val available: AvailableUpdate? = null,
    val downloaded: Boolean = false,
    val downloadedBytes: Long = 0,
)

internal class UpdatesViewModel(application: Application) : AndroidViewModel(application) {
    private val client = HostedUpdateClient()
    private val preferences = application.getSharedPreferences("updates", android.content.Context.MODE_PRIVATE)
    private val mutableAutomaticChecks = MutableStateFlow(preferences.getBoolean("automatic", true))
    val automaticChecks = mutableAutomaticChecks.asStateFlow()
    fun setAutomaticChecks(enabled: Boolean) { mutableAutomaticChecks.value = enabled; preferences.edit().putBoolean("automatic", enabled).apply() }
    private val mutableState = MutableStateFlow(UpdatesState())
    val state = mutableState.asStateFlow()
    private val startupCheck = StartupUpdateCheck()
    private val reminderStore = UpdateReminderStore(application)
    private var reminder = UpdateReminder()
    private var work: Job? = null
    private val directory = File(application.cacheDir, "updates")
    private val apk = File(directory, "notebookplush-update.apk")

    init {
        operation {
            try {
                withContext(Dispatchers.IO) {
                    // Downloads interrupted by process death are never offered to the installer.
                    directory.deleteRecursively()
                    reminder = reminderStore.read()
                }
            } finally {
                mutableState.update { it.copy(initialized = true) }
            }
        }
    }

    private fun operation(block: suspend () -> Unit) {
        if (work?.isActive == true) return
        mutableState.update { it.copy(busy = true) }
        work = viewModelScope.launch {
            try { block() }
            catch (cancelled: CancellationException) {
                mutableState.update { it.copy(message = "Cancelled.") }
                throw cancelled
            } catch (exception: Exception) {
                // Transport exceptions can expose the unlinked feed URL. Only our deliberately authored
                // failures are safe for the screen; never display exception.message otherwise.
                mutableState.update { it.copy(message = (exception as? UpdateFailure)?.message
                    ?: "Could not complete the update request. Check your connection and try again.") }
            } finally {
                mutableState.update { it.copy(busy = false) }
            }
        }
    }

    fun checkAtStartup() {
        val enabled = automaticChecks.value
        val current = mutableState.value
        if (startupCheck.shouldCheck(
                preferencesLoaded = true,
                updaterReady = current.initialized,
                enabled = enabled,
                supported = !BuildConfig.DEBUG && UpdateManifestUrl.isNotBlank() && BuildConfig.APPLICATION_ID == "com.notebookplush",
                busy = current.busy,
                reminder = reminder,
            )) check(startup = true)
    }

    fun dismiss() {
        val postpone = mutableState.value.available != null
        work?.cancel()
        mutableState.update { it.copy(dialogVisible = false, installRequested = false) }
        if (postpone) {
            reminder = UpdateReminder(System.currentTimeMillis())
            val saved = reminder
            // Independent of a cancelled download; a dismissal must survive the next launch.
            viewModelScope.launch(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                try { reminderStore.save(saved) }
                catch (_: java.io.IOException) {
                    mutableState.update { it.copy(message = "Could not save the reminder delay. It lasts only until the app closes.") }
                }
            }
        }
    }

    fun consumeInstallRequest() {
        mutableState.update { it.copy(installRequested = false) }
    }

    fun check() {
        // Explicit checks are always available, even during the week's automatic snooze.
        startupCheck.handledManually()
        mutableState.update { it.copy(dialogVisible = true) }
        check(startup = false)
    }

    private fun check(startup: Boolean) = operation {
        mutableState.update { it.copy(
            message = "Checking for updates…", available = null, downloaded = false,
            installRequested = false, downloadedBytes = 0,
        ) }
        withContext(Dispatchers.IO) { apk.delete() }
        val update = client.check(BuildConfig.APPLICATION_ID, BuildConfig.VERSION_CODE.toLong())
        mutableState.update { it.copy(
            available = update,
            dialogVisible = it.dialogVisible || (startup && update != null && automaticChecks.value &&
                reminder.mayPrompt(System.currentTimeMillis())),
            message = if (update == null) "No newer build is available for this installation."
                else "Update available: ${update.manifest.versionName}",
        ) }
    }

    fun updateNow() {
        if (mutableState.value.downloaded) {
            mutableState.update { it.copy(installRequested = true) }
        } else download()
    }

    private fun download() = operation {
        val update = mutableState.value.available ?: return@operation
        mutableState.update { it.copy(message = "Downloading update…", downloaded = false, downloadedBytes = 0) }
        withContext(Dispatchers.IO) { check(directory.isDirectory || directory.mkdirs()) }
        val partial = File(directory, "download.partial")
        try {
            client.download(update, partial) { count ->
                mutableState.update { it.copy(downloadedBytes = count) }
            }
            mutableState.update { it.copy(message = "Checking the APK…") }
            withContext(Dispatchers.IO) {
                verifyUpdateApk(getApplication(), partial, update.manifest)
                currentCoroutineContext().ensureActive()
                apk.delete()
                if (!partial.renameTo(apk)) throw UpdateFailure("Could not save the downloaded update.")
            }
            mutableState.update { it.copy(downloaded = true, installRequested = it.dialogVisible, message = "Update verified. Ready to install.") }
        } finally { withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { partial.delete() } }
    }

    fun install(launch: (Intent) -> Unit) = operation {
        val update = mutableState.value.available ?: return@operation
        val context = getApplication<Application>()
        if (android.os.Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
            throw UpdateFailure("Allow NotebookPlush to install updates, then press Install update again.")
        }
        withContext(Dispatchers.IO) { verifyUpdateApk(context, apk, update.manifest) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            clipData = ClipData.newRawUri("NotebookPlush update", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        launch(intent)
        mutableState.update { it.copy(message = "Confirm the update in Android's installer. If cancelled, you can try again.") }
    }

    fun cancel() { work?.cancel() }
}
