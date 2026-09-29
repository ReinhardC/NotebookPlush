"""Publication failures must leave a complete, usable previous update."""

import importlib.util
import json
from pathlib import Path
import tempfile
import types
import unittest
from unittest.mock import patch
import subprocess
from urllib.parse import urlsplit

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("publish_update", HERE / "publish-update.py")
publisher = importlib.util.module_from_spec(spec)
spec.loader.exec_module(publisher)


class FakeSftp:
    def __init__(self):
        self.files = {}
        self.renames = []
        self.fail_apk = False
        self.fail_manifest = False

    def stat(self, path):
        if path not in self.files:
            raise FileNotFoundError(path)
        return types.SimpleNamespace(st_size=len(self.files[path]))

    def mkdir(self, path, mode):
        self.files[path] = b""

    def put(self, local, remote, confirm):
        self.files[remote] = Path(local).read_bytes()
        if self.fail_apk and ".apk." in remote:
            raise OSError("upload interrupted")

    def chmod(self, path, mode):
        pass

    def posix_rename(self, source, target):
        if self.fail_manifest and target.endswith(".json"):
            raise OSError("rename interrupted")
        self.files[target] = self.files.pop(source)
        self.renames.append(target)

    def remove(self, path):
        if path not in self.files:
            raise FileNotFoundError(path)
        del self.files[path]


class PublishTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        root = Path(self.temp.name)
        self.apk = root / "release.apk"
        self.apk.write_bytes(b"new release fixture")
        self.manifest = root / "update.json"
        self.manifest.write_text(json.dumps({"schema": 1, "applicationId": "com.notebookplush",
                                            "sha256": publisher.digest(self.apk),
                                            "size": self.apk.stat().st_size}))
        self.config = json.loads((HERE / "update-host.json").read_text(encoding="utf-8-sig"))
        self.sftp = FakeSftp()
        self.manifest_remote = self.config["remoteDirectory"] + "/" + self.config["manifestName"]
        self.sftp.files[self.manifest_remote] = b"previous manifest"
        self.sftp.files[self.config["remoteDirectory"] + "/old.apk"] = b"previous apk"

    def fetch(self, url, target):
        remote = "public_html" + urlsplit(url).path
        target.write_bytes(self.sftp.files[remote])

    def publish(self, fetch=None):
        publisher.publish(self.sftp, self.config, self.apk, self.manifest, fetch or self.fetch)

    def assert_previous_survives(self):
        self.assertEqual(b"previous manifest", self.sftp.files[self.manifest_remote])
        self.assertFalse(any(path.endswith(".partial") for path in self.sftp.files))

    def test_manifest_is_last_and_previous_apks_remain(self):
        self.publish()
        self.assertEqual(self.manifest_remote, self.sftp.renames[-1])
        self.assertEqual(self.manifest.read_bytes(), self.sftp.files[self.manifest_remote])
        self.assertEqual(b"previous apk", self.sftp.files[self.config["remoteDirectory"] + "/old.apk"])

    def test_interrupted_apk_does_not_advertise_new_update(self):
        self.sftp.fail_apk = True
        with self.assertRaises(OSError):
            self.publish()
        self.assert_previous_survives()

    def test_public_download_mismatch_does_not_publish_manifest(self):
        with self.assertRaises(ValueError):
            self.publish(lambda url, target: target.write_bytes(b"server error page"))
        self.assert_previous_survives()

    def test_failed_atomic_swap_keeps_previous_manifest(self):
        self.sftp.fail_manifest = True
        with self.assertRaises(OSError):
            self.publish()
        self.assert_previous_survives()

    def test_local_mismatch_never_touches_server(self):
        self.apk.write_bytes(b"wrong file")
        with self.assertRaises(ValueError):
            self.publish()
        self.assertEqual([], self.sftp.renames)
        self.assert_previous_survives()

    def test_app_and_publisher_share_one_configuration(self):
        build = (HERE.parent / "androidApp/build.gradle.kts").read_text(encoding="utf-8-sig")
        source = (HERE.parent / "androidApp/src/main/kotlin/com/notebookplush/update/UpdateModels.kt").read_text(encoding="utf-8-sig")
        self.assertIn('rootProject.file("tools/update-host.json")', build)
        self.assertIn('BuildConfig.UPDATE_BASE_URL', source)
        self.assertIn('BuildConfig.UPDATE_MANIFEST_URL', source)
        self.assertRegex(self.config["manifestName"], r"^[0-9a-f]{32}\.json$")
        self.assertEqual("public_html/notebookplush", self.config["remoteDirectory"])

    def test_debug_signed_apk_is_refused(self):
        with patch.object(publisher.subprocess, "run", return_value=types.SimpleNamespace(
                stdout="Signer #1 certificate DN: CN=Android Debug, O=Android, C=US")):
            with self.assertRaises(ValueError):
                publisher.verify_release_signature("apksigner", self.apk)

    def test_invalid_signature_stops_publication(self):
        with patch.object(publisher.subprocess, "run", side_effect=subprocess.CalledProcessError(1, "apksigner")):
            with self.assertRaises(subprocess.CalledProcessError):
                publisher.verify_release_signature("apksigner", self.apk)

if __name__ == "__main__":
    unittest.main()
