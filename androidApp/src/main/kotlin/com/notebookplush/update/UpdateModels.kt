package com.notebookplush.update

import org.json.JSONObject

internal val UpdateBaseUrl = com.notebookplush.BuildConfig.UPDATE_BASE_URL
internal val UpdateManifestUrl = com.notebookplush.BuildConfig.UPDATE_MANIFEST_URL
internal const val MaxUpdateBytes = 200L * 1024 * 1024

internal class UpdateFailure(message: String) : Exception(message)

internal data class UpdateManifest(
    val versionCode: Long,
    val versionName: String,
    val applicationId: String,
    val sha256: String,
    val size: Long,
    val commit: String,
) {
    companion object {
        fun parse(text: String): UpdateManifest {
            val json = JSONObject(text)
            if (json.optInt("schema") != 1) throw UpdateFailure("This update's manifest format is not supported.")
            val result = UpdateManifest(
                json.getLong("versionCode"), json.getString("versionName"),
                json.getString("applicationId"), json.getString("sha256").lowercase(),
                json.getLong("size"), json.getString("commit"),
            )
            if (result.versionCode !in 1..2_100_000_000L || result.size !in 1..MaxUpdateBytes ||
                !result.sha256.matches(Regex("[0-9a-f]{64}")) ||
                !result.commit.matches(Regex("[0-9a-fA-F]{40}")) ||
                result.versionName.isBlank() || result.applicationId.isBlank()
            ) throw UpdateFailure("The update manifest is incomplete or invalid.")
            return result
        }
    }
}

// The content-addressed name stays valid when CI publishes the next manifest.
internal data class AvailableUpdate(val manifest: UpdateManifest) {
    val apkUrl: String get() = "${UpdateBaseUrl}${manifest.sha256}.apk"
}

internal fun selectUpdate(
    manifest: UpdateManifest,
    installedPackage: String,
    installedVersion: Long,
): AvailableUpdate? {
    if (manifest.applicationId != installedPackage) {
        throw UpdateFailure("This release is for a different app. Updates are available in the regular release build.")
    }
    return if (manifest.versionCode > installedVersion) AvailableUpdate(manifest) else null
}
