"""Publish an immutable APK, then atomically replace the hosted update manifest."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import uuid


HERE = Path(__file__).resolve().parent


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def replace_remote(sftp, local, remote):
    temporary = remote + "." + uuid.uuid4().hex + ".partial"
    try:
        sftp.put(str(local), temporary, confirm=True)
        sftp.chmod(temporary, 0o644)
        # Never delete the old manifest first: cancellation must leave it readable.
        sftp.posix_rename(temporary, remote)
    finally:
        try:
            sftp.remove(temporary)
        except FileNotFoundError:
            pass


def fetch_public(url, target):
    # curl uses the platform certificate store on Windows as well as on the CI runner.
    subprocess.run([
        "curl", "--fail", "--silent", "--show-error", "--max-time", "180",
        "--proto", "=https", "--header", "Cache-Control: no-cache",
        "--output", str(target), url,
    ], check=True)


def publish(sftp, config, apk, manifest_path, fetch=fetch_public):
    manifest = json.loads(manifest_path.read_text(encoding="utf-8-sig"))
    checksum = digest(apk)
    if (manifest["schema"] != 1 or manifest["applicationId"] != "com.notebookplush"
            or manifest["sha256"] != checksum or manifest["size"] != apk.stat().st_size):
        raise ValueError("APK does not match the regular-release manifest")
    remote = config["remoteDirectory"]
    try:
        sftp.stat(remote)
    except FileNotFoundError:
        sftp.mkdir(remote, mode=0o755)
    with tempfile.TemporaryDirectory(prefix="notebookplush-publish-") as temporary:
        directory = Path(temporary)
        # A blank landing page exposes no filenames even on hosts with automatic listings.
        index = directory / "index.html"
        index.write_text('<!doctype html><html><head><meta name="robots" '
                         'content="noindex,nofollow"><title></title></head><body></body></html>\n',
                         encoding="utf-8")
        replace_remote(sftp, index, remote + "/index.html")
        # Keeping older content-addressed APKs lets checks made before this publish finish later.
        name = checksum + ".apk"
        replace_remote(sftp, apk, remote + "/" + name)
        downloaded = directory / "verification.apk"
        fetch(config["baseUrl"] + name, downloaded)
        if downloaded.stat().st_size != manifest["size"] or digest(downloaded) != checksum:
            raise ValueError("Public APK differs from the release; manifest was not published")
        replace_remote(sftp, manifest_path, remote + "/" + config["manifestName"])
        fetch(config["baseUrl"] + config["manifestName"] + "?verify=" + uuid.uuid4().hex,
              directory / "verification.json")
        if json.loads((directory / "verification.json").read_text()) != manifest:
            raise ValueError("Public manifest does not match the published release")
    print("Hosted APK and manifest verified over HTTPS.")


def verify_release_signature(apksigner, apk):
    signature = subprocess.run([str(apksigner), "verify", "--verbose", "--print-certs", str(apk)],
                               check=True, capture_output=True, text=True)
    if "CN=Android Debug" in signature.stdout:
        raise ValueError("Publish a signed release APK, not a debug-signed build")


def main():
    import paramiko
    parser = argparse.ArgumentParser()
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--apksigner", type=Path, required=True,
                        help="Android SDK build-tools apksigner executable")
    args = parser.parse_args()
    verify_release_signature(args.apksigner, args.apk)
    config = json.loads((HERE / "update-host.json").read_text(encoding="utf-8-sig"))
    with paramiko.SSHClient() as client:
        client.load_host_keys(str(HERE / "update-host-known_hosts"))
        client.connect(config.get("sftpHost", "hosting.telekom.de"), username=os.environ["NOTEBOOKPLUSH_UPDATE_USER"],
                       password=os.environ["NOTEBOOKPLUSH_UPDATE_PASSWORD"],
                       look_for_keys=False, allow_agent=False,
                       timeout=30, banner_timeout=30, auth_timeout=30)
        with client.open_sftp() as sftp:
            sftp.get_channel().settimeout(60)
            publish(sftp, config, args.apk, args.manifest)


if __name__ == "__main__":
    main()
