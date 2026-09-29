package com.notebookplush.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal class UpdateReminderStore(context: Context) {
    private val preferences = context.getSharedPreferences("update_reminder", Context.MODE_PRIVATE)
    fun read(): UpdateReminder = UpdateReminder(preferences.getLong("dismissed_at_millis", 0))
    @Synchronized
    fun save(reminder: UpdateReminder) {
        if (!preferences.edit().putLong("dismissed_at_millis",
                maxOf(preferences.getLong("dismissed_at_millis", 0), reminder.dismissedAtMillis)).commit()) {
            throw java.io.IOException("Could not save update reminder")
        }
    }
}

@Suppress("DEPRECATION")
internal fun PackageInfo.updateVersionCode(): Long =
    if (Build.VERSION.SDK_INT >= 28) longVersionCode else versionCode.toLong()

@Suppress("DEPRECATION")
private fun PackageInfo.signerDigests(): Set<String> {
    val certificates = if (Build.VERSION.SDK_INT >= 28) signingInfo?.apkContentsSigners else signatures
    return certificates.orEmpty().map { signature ->
        MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(it) }
    }.toSet()
}

/** Check bytes, package, version and signer before giving any APK to the system installer. */
@Suppress("DEPRECATION")
internal suspend fun verifyUpdateApk(context: Context, file: File, manifest: UpdateManifest) {
    if (file.length() != manifest.size) throw UpdateFailure("The APK size does not match the published update.")
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    if (digest.digest().joinToString("") { "%02x".format(it) } != manifest.sha256) {
        throw UpdateFailure("The APK checksum does not match. Check for updates and download again.")
    }
    val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
    val manager = context.packageManager
    val apk = manager.getPackageArchiveInfo(file.absolutePath, flags)
        ?: throw UpdateFailure("The download is not a readable APK.")
    val installed = manager.getPackageInfo(context.packageName, flags)
    if (apk.packageName != context.packageName || apk.packageName != manifest.applicationId ||
        apk.updateVersionCode() != manifest.versionCode || apk.versionName != manifest.versionName ||
        apk.updateVersionCode() <= installed.updateVersionCode()
    ) throw UpdateFailure("This APK is not a newer version of the installed app.")
    val signers = installed.signerDigests()
    if (signers.isEmpty() || apk.signerDigests() != signers) {
        throw UpdateFailure("This APK uses a different signing key and cannot update this installation. Keep the installed app; do not uninstall it.")
    }
}
