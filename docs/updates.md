# NotebookPlush hosted updates

NotebookPlush reuses Easynews's hosted Android update mechanism and stable release signing key with its own package and feed. The public base is `https://clausbilder.de/notebookplush/`. Easynews uses `clausbilder.de/easyapp/`; the two publication directories and manifests are separate.

GitHub CI is the production release publisher for [ReinhardC/NotebookPlush](https://github.com/ReinhardC/NotebookPlush). On each push to `main`, `.github/workflows/build.yml` runs build checks and tests, builds with the stable release key from GitHub secrets, then publishes the verified APK and manifest to the host. The four CI signing/hosting secrets were copied directly from Easynews through an isolated temporary GitHub workflow, then its transfer branch and temporary credential were removed. No secret values were downloaded into this checkout or committed.

## Host configuration

`tools/update-host.json` supplies the HTTPS base URL, stable opaque manifest filename, and SFTP directory to both Gradle and the publisher. The base URL must end in `/`. Keep the manifest filename stable after distributing the first release. APKs use their SHA-256 as the filename, so a previously checked update stays downloadable during later publications.

The SFTP host is `hosting.telekom.de`, borrowing Easynews's provider. This is the server in [Telekom's SFTP documentation](https://homepagecenter.telekom.de/hilfe/uebersicht-der-server); it is distinct from the public download hostname. `tools/update-host-known_hosts` contains Easynews's pinned Telekom host key. The publisher rejects unknown host keys. The mapping of `public_html/notebookplush` to the configured HTTPS directory is verified by downloading each published APK and manifest over HTTPS.

Publishing uploads a temporary APK and renames it atomically, downloads the public HTTPS copy to check size/hash, then atomically replaces the manifest and verifies its public copy. Failed APK uploads leave the previous feed usable. Older APKs are retained. A blank `index.html` suppresses directory listings; the feed is public and its opaque filename is not an access-control mechanism.

## Stable release signing

Every distributed release must use the same stable key. CI currently reuses Easynews's Sideload release key, with repository variable `NOTEBOOKPLUSH_KEY_ALIAS=easynews`. Keep its backup outside the repository. Gradle reads:

| Setting | Purpose |
| --- | --- |
| `NOTEBOOKPLUSH_KEYSTORE_FILE` | Keystore path; defaults to `~/.android/notebookplush-release.jks` |
| `NOTEBOOKPLUSH_KEYSTORE_PASSWORD` | Store password; alternatively use `notebookplushReleaseStorePassword` in your private user Gradle properties |
| `NOTEBOOKPLUSH_KEY_ALIAS` | Alias; defaults to `notebookplush` |
| `NOTEBOOKPLUSH_KEY_PASSWORD` | Key password; defaults to the store password |

Without a configured key, Gradle produces an unsigned release rather than silently signing with the debug key. The publisher verifies APK signatures with Android's `apksigner` and refuses standard debug certificates. The app verifies that the download has the exact installed signing certificates as well.

The existing development installation uses a debug key. A stable release installation must be provisioned separately, preserving/exporting wanted drafts before changing its signing identity. The updater refuses a different signer and never uninstalls the current app.

Both local and future CI builds derive `versionCode` from UTC seconds since 2020-01-01. Keep build machine clocks synchronized and build releases sequentially. `versionName` is `0.2.<versionCode>`. The publisher reads the actual APK output metadata rather than guessing a version.

## Optional local release tools

Production releases use the GitHub workflow below. These commands are available for local troubleshooting or a manually authorized publication.

Use a committed release revision; the manifest records its full 40-character SHA. Supply `NOTEBOOKPLUSH_UPDATE_USER` and `NOTEBOOKPLUSH_UPDATE_PASSWORD` through your environment or secret manager. They are SFTP credentials for the configured hosting account; no credentials are bundled into the app.

Run from the project root in PowerShell after configuring signing and hosting:

```powershell
.\gradlew.bat :androidApp:assembleRelease
if ($LASTEXITCODE -ne 0) { throw 'Release build failed' }
$releaseApk = 'androidApp/build/outputs/apk/release/androidApp-release.apk'
$releaseMetadata = 'androidApp/build/outputs/apk/release/output-metadata.json'
$releaseCommit = git rev-parse HEAD
if ($LASTEXITCODE -ne 0) { throw 'A committed release revision is required' }
$releaseManifest = 'androidApp/build/outputs/apk/release/update.json'
uv run --python 3.13 python tools/build-update-manifest.py `
    --metadata $releaseMetadata --apk $releaseApk `
    --commit $releaseCommit --output $releaseManifest
if ($LASTEXITCODE -ne 0) { throw 'Manifest generation failed' }
$releaseApkSigner = "$env:LOCALAPPDATA/Android/Sdk/build-tools/36.0.0/apksigner.bat"
uv run --python 3.13 --with-requirements tools/update-requirements.txt `
    python tools/publish-update.py --apk $releaseApk `
    --manifest $releaseManifest --apksigner $releaseApkSigner
if ($LASTEXITCODE -ne 0) { throw 'Publication failed' }
```

Adjust the SDK path to your installation. Python 3.11+ and `curl` are required; `uv` installs the pinned Paramiko dependency for publication. Generate the manifest from the signed, universal release APK. Do not use the `.testing` package. The signed release should be committed and reviewed before running the publisher; the publication command writes to the public host.

## Automatic publication from GitHub

`.github/workflows/build.yml` checks main pushes and pull requests. Its release job automatically publishes each main push after checks pass. Configure the host mapping and stable release key for the first publication; missing signing secrets fail the release job before publication. Pull requests and manual check runs do not publish. Runs and their logs are available on the repository's [Actions page](https://github.com/ReinhardC/NotebookPlush/actions).

Configure these repository secrets:

- `NOTEBOOKPLUSH_KEYSTORE_BASE64`: base64 of the same release keystore used locally.
- `NOTEBOOKPLUSH_KEYSTORE_PASSWORD`: its store password.
- `NOTEBOOKPLUSH_KEY_PASSWORD`: optional separate key password; blank falls back to the store password.
- `NOTEBOOKPLUSH_UPDATE_USER` and `NOTEBOOKPLUSH_UPDATE_PASSWORD`: hosting SFTP credentials.

The optional repository variable `NOTEBOOKPLUSH_KEY_ALIAS` defaults to `notebookplush`; this repository sets it to `easynews` for the reused key. Main publications are serialized. Checks use read-only repository permissions; the publication job has contents-write permission to create a GitHub release after hosted verification succeeds. Each release uses its own version tag and retains the signed APK and manifest. Pull requests do not receive the release key or publish. Signed APK/manifest artifacts are also retained in the successful publication run. The action majors match Easynews's [checkout](https://github.com/actions/checkout/releases/tag/v7.0.0), [Gradle setup](https://github.com/gradle/actions/releases/tag/v6.0.0), and [Android SDK setup](https://github.com/android-actions/setup-android/releases/tag/v4.0.0) versions.

## Runtime behavior and checks

Release builds check once per Activity session, with state retained across rotation. Automatic checks default on and can be disabled in App updates. Offline failures stay silent at startup. Manual checks bypass the seven-day reminder delay. Update now downloads and verifies the APK, requests Android's per-app install permission where needed, and hands off to the system installer. Installation always requires Android confirmation. Canceling the installer keeps the verified download available for retry in the same session.

Requests and redirects use HTTPS and stay inside the configured host/directory. Manifest downloads are limited to 64 KiB, APKs to 200 MiB. Downloads are bounded by manifest size and are checked for exact bytes/hash, package/version, strictly newer build, and signer equality. Interrupted downloads are removed at the next process start.

```powershell
.\gradlew.bat :androidApp:testQaUnitTest :androidApp:lintDebug
.\gradlew.bat :androidApp:connectedQaAndroidTest
uv run --python 3.13 python tools/test-publish-update.py
```

Unit tests use fake transport/SFTP and real JSON parsing; they do not contact the host. Device tests exercise JSON colors, independent tab undo/cursors, migration, rotation, and workspace restoration in a separate QA app. A live release-to-release installation remains to be exercised after signing and the host are provisioned.
