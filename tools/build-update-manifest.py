"""Publish the APK's actual Gradle version and checksum for the hosted and GitHub releases."""

import argparse
import hashlib
import json
import re
from pathlib import Path


def build_manifest(metadata_path: Path, apk_path: Path, commit: str) -> dict:
    metadata = json.loads(metadata_path.read_text(encoding="utf-8"))
    elements = metadata["elements"]
    if len(elements) != 1:
        raise ValueError("The updater needs exactly one universal APK")
    element = elements[0]
    if metadata["applicationId"] != "com.notebookplush":
        raise ValueError("Only the regular release package may be published")
    if not re.fullmatch(r"[0-9a-fA-F]{40}", commit):
        raise ValueError("A full commit SHA is required")
    if not 0 < apk_path.stat().st_size <= 200 * 1024 * 1024:
        raise ValueError("APK size is outside the updater's accepted range")
    if not 0 < element["versionCode"] <= 2_100_000_000:
        raise ValueError("APK versionCode is invalid")
    original = metadata_path.parent / element["outputFile"]
    with original.open("rb") as stream:
        original_digest = hashlib.file_digest(stream, "sha256").hexdigest()
    with apk_path.open("rb") as stream:
        digest = hashlib.file_digest(stream, "sha256").hexdigest()
    if digest != original_digest:
        raise ValueError("Published APK differs from the Gradle output")
    return {
        "schema": 1,
        "applicationId": metadata["applicationId"],
        "versionCode": element["versionCode"],
        "versionName": element["versionName"],
        "sha256": digest,
        "size": apk_path.stat().st_size,
        "commit": commit.lower(),
    }


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--metadata", type=Path, required=True)
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--commit", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    args.output.write_text(
        json.dumps(build_manifest(args.metadata, args.apk, args.commit), indent=2) + "\n",
        encoding="utf-8",
    )
