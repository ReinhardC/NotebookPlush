package com.notebookplush.update

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class HostedUpdateClientTest {
    private val manifest = UpdateManifest(42, "0.1-poc.42", "com.notebookplush", "a".repeat(64), 100, "b".repeat(40))
    private val manifestJson = """{"schema":1,"versionCode":42,"versionName":"0.1-poc.42","applicationId":"com.notebookplush","sha256":"${"a".repeat(64)}","size":100,"commit":"${"b".repeat(40)}"}"""

    private class FakeTransport(var response: String) : HostedUpdateTransport {
        var downloadUrl = ""
        var size = 0L
        override suspend fun manifest() = response
        override suspend fun download(url: String, file: File, size: Long, progress: (Long) -> Unit) {
            downloadUrl = url
            this.size = size
            progress(size)
        }
    }

    @Test fun publicManifestNeedsNoAccountAndPinsTheApkAcrossPublication() = runBlocking {
        val transport = FakeTransport(manifestJson)
        val client = HostedUpdateClient(transport)
        val update = client.check(manifest.applicationId, 41)!!
        assertEquals(manifest, update.manifest)
        transport.response = manifestJson.replace("42", "43").replace("a".repeat(64), "c".repeat(64))
        var progress = 0L
        client.download(update, File("unused.apk")) { progress = it }
        assertEquals("https://clausbilder.de/notebookplush/${manifest.sha256}.apk", transport.downloadUrl)
        assertEquals(100L, transport.size)
        assertEquals(100L, progress)
    }

    @Test fun sameOrOlderBuildIsNotAnUpdate() {
        assertNull(selectUpdate(manifest, manifest.applicationId, 42))
        assertNull(selectUpdate(manifest, manifest.applicationId, 100))
    }

    @Test fun refusesDiagnosticAndOtherPackages() {
        for (suffix in listOf(".debug", ".diag", ".other")) {
            assertThrows(UpdateFailure::class.java) { selectUpdate(manifest, manifest.applicationId + suffix, 1) }
        }
    }

    @Test fun manifestRequiresSupportedSchemaBoundedSizeAndChecksum() {
        assertEquals(manifest, UpdateManifest.parse(manifestJson))
        for ((old, replacement) in listOf(
            "\"schema\":1" to "\"schema\":2",
            "\"size\":100" to "\"size\":0",
            "\"size\":100" to "\"size\":209715201",
            "\"versionCode\":42" to "\"versionCode\":0",
            "a".repeat(64) to "bad-hash",
            "b".repeat(40) to "bad-commit",
        )) assertThrows(UpdateFailure::class.java) { UpdateManifest.parse(manifestJson.replace(old, replacement)) }
    }

    @Test fun onlyHttpsOnTheUpdateHostIsAllowed() {
        assertTrue(allowedUpdateUrl(UpdateManifestUrl))
        assertTrue(allowedUpdateUrl(AvailableUpdate(manifest).apkUrl))
        for (url in listOf(
            "http://clausbilder.de/otherapp/a.apk", "https://clausbilder.de.evil.test/a.apk",
            "https://evil.test/clausbilder.de", "https://reader@clausbilder.de/a.apk",
            "https://clausbilder.de:444/a.apk", "https://clausbilder.de/a.apk#fragment",
            "https://clausbilder.de/notebookplush/../otherapp/a.apk",
            "https://clausbilder.de/notebookplush/%2e%2e/otherapp/a.apk",
            "https://clausbilder.de/notebookplush-other/a.apk",
            "https://api.github.com/repos/ReinhardC/NotebookPlush", "not a URL",
        )) assertFalse(url, allowedUpdateUrl(url))
    }
}
