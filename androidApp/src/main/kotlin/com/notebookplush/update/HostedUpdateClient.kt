package com.notebookplush.update

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.net.URI
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal interface HostedUpdateTransport {
    suspend fun manifest(): String
    suspend fun download(url: String, file: File, size: Long, progress: (Long) -> Unit)
}

internal class HostedUpdateClient(private val transport: HostedUpdateTransport = HostedUpdateHttp()) {
    suspend fun check(packageName: String, version: Long): AvailableUpdate? =
        if (UpdateManifestUrl.isBlank()) throw UpdateFailure("The update host has not been configured for this build.") else selectUpdate(UpdateManifest.parse(transport.manifest()), packageName, version)

    suspend fun download(update: AvailableUpdate, file: File, progress: (Long) -> Unit) =
        transport.download(update.apkUrl, file, update.manifest.size, progress)
}

// Both initial requests and redirects stay on the update host, with HTTPS throughout.
internal fun allowedUpdateUrl(url: String): Boolean = runCatching {
    val uri = URI(url)
    val base = URI(UpdateBaseUrl)
    // Normalize the decoded path too: encoded ../ segments must not escape the update directory.
    val path = URI(null, null, uri.path, null).normalize().path
    uri.scheme == "https" && uri.host == base.host && path.startsWith(base.path) &&
        !path.contains('\\') && uri.rawUserInfo == null &&
        (uri.port == -1 || uri.port == 443) && uri.rawFragment == null
}.getOrDefault(false)

internal class HostedUpdateHttp : HostedUpdateTransport {
    override suspend fun manifest(): String = withContext(Dispatchers.IO) {
        val bytes = ByteArrayOutputStream()
        fetch(UpdateManifestUrl, bytes, 64 * 1024) {}
        bytes.toString("UTF-8")
    }

    override suspend fun download(url: String, file: File, size: Long, progress: (Long) -> Unit) =
        withContext(Dispatchers.IO) {
            try {
                file.outputStream().use { fetch(url, it, size, progress) }
                if (file.length() != size) throw UpdateFailure("The APK download was incomplete. Try downloading again.")
            } catch (exception: Exception) {
                file.delete()
                throw exception
            }
        }

    private suspend fun fetch(initialUrl: String, output: OutputStream, limit: Long, progress: (Long) -> Unit) {
        require(allowedUpdateUrl(initialUrl))
        var url = initialUrl
        repeat(5) {
            currentCoroutineContext().ensureActive()
            val connection = connection(url)
            try {
                val status = connection.responseCode
                if (status in setOf(301, 302, 303, 307, 308)) {
                    val location = connection.getHeaderField("Location")
                        ?: throw UpdateFailure("The update server did not provide a download address.")
                    val next = URI(url).resolve(location).toString()
                    if (!allowedUpdateUrl(next)) throw UpdateFailure("The update server returned an unexpected download address.")
                    url = next
                } else {
                    requireSuccess(status)
                    copyResponse(connection, output, limit, progress)
                    return
                }
            } finally { connection.disconnect() }
        }
        throw UpdateFailure("The update server redirected too many times. Try again.")
    }

    private fun connection(url: String): HttpsURLConnection =
        (URL(url).openConnection() as HttpsURLConnection).apply {
            instanceFollowRedirects = false
            connectTimeout = 20_000
            readTimeout = 30_000
            setRequestProperty("Accept", "*/*")
            useCaches = false
            setRequestProperty("Cache-Control", "no-cache")
            setRequestProperty("User-Agent", "NotebookPlush-Android-Updater")
        }

    private suspend fun copyResponse(connection: HttpsURLConnection, output: OutputStream, limit: Long, progress: (Long) -> Unit = {}) {
        if (connection.contentLengthLong > limit) throw UpdateFailure("The update download is larger than expected.")
        var total = 0L
        connection.inputStream.use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                if (total > limit) throw UpdateFailure("The update download is larger than expected.")
                output.write(buffer, 0, count)
                progress(total)
            }
        }
    }

    private fun requireSuccess(status: Int) {
        when (status) {
            200 -> return
            404 -> throw UpdateFailure("The update is not available on the server yet. Try again shortly.")
            429 -> throw UpdateFailure("The update server is busy. Try again later.")
            else -> throw UpdateFailure("The update server could not complete the request (HTTP $status). Try again.")
        }
    }
}
