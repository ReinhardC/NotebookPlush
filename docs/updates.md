# NotebookPlush hosted updates

NotebookPlush publishes signed release APKs and an update manifest to its own hosted feed. The public base is `https://clausbilder.de/notebookplush/`.

GitHub CI is the production release publisher for [ReinhardC/NotebookPlush](https://github.com/ReinhardC/NotebookPlush). On each push to `main`, `.github/workflows/build.yml` runs build checks and tests, builds with the stable release key from GitHub secrets, then publishes the verified APK and manifest to the host. No secret values are stored in this checkout.

## Host configuration

`tools/update-host.json` supplies the HTTPS base URL, stable opaque manifest filename, and SFTP directory to both Gradle and the publisher. The base URL must end in `/`. Keep the manifest filename stable after distributing the first release. APKs use their SHA-256 as the filename, so a previously checked update stays downloadable during later publications.

The SFTP host is `hosting.telekom.de`, the server in [Telekom's SFTP documentation](https://homepagecenter.telekom.de/hilfe/uebersicht-der-server); it is distinct from the public download hostname. `tools/update-host-known_hosts` contains the pinned Telekom host key. The publisher rejects unknown host keys. The mapping of `public_html/notebookplush` to the configured HTTPS directory is verified by downloading each published APK and manifest over HTTPS.

Publishing uploads a temporary APK and renames it atomically, downloads the public HTTPS copy to check size/hash, then atomically replaces the manifest and verifies its public copy. Failed APK uploads leave the previous feed usable. Older APKs are retained. A blank `index.html` suppresses directory listings; the feed is public and its opaque filename is not an access-control mechanism.

## Stable release signing

Every distributed release must use the same stable key. CI reads it from repository secrets; the repository variable `NOTEBOOKPLUSH_KEY_ALIAS` names its alias. Keep a backup of the key outside the repository. Gradle reads:

| Setting | Purpose |
| --- | --- |
| `NOTEBOOKPLUSH_KEYSTORE_FILE` | Keystore path; defaults to `~/.android/notebookplush-release.jks` |
| `NOTEBOOKPLUSH_KEYSTORE_PASSWORD` | Store password; alternatively use `notebookplushReleaseStorePassword` in your private user Gradle properties |
| `NOTEBOOKPLUSH_KEY_ALIAS` | Alias; defaults to `notebookplush` |
| `NOTEBOOKPLUSH_KEY_PASSWORD` | Key password; defaults to the store password |

Without a configured key, Gradle produces an unsigned release rather than silently signing with the debug key. The publisher verifies APK signatures with Android's `apksigner` and refuses standard debug certificates. The app verifies that the download has the exact installed signing certificates as well.

An installation using a debug key cannot receive stable release updates. Preserve/export wanted drafts before changing its signing identity. The updater and local deployment helper refuse a different signer and never uninstall the current app.

Both local and CI builds derive `versionCode` from UTC seconds since 2020-01-01. Keep build machine clocks synchronized and build releases sequentially. `versionName` is `0.2.<versionCode>`. The publisher reads the actual APK output metadata rather than guessing a version.

## Local signed builds

`setup-release-key.ps1` imports the CI signing key once for the current Windows account. It requires GitHub CLI and repository write access, using an existing `gh` login or Git Credential Manager login. Run it with `powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\setup-release-key.ps1`.

The import creates a temporary remote branch and dispatches an isolated workflow based on `tools/export-release-key.yml`. The runner encrypts the signing material for a temporary, non-exportable certificate on the local PC. Only the encrypted CMS envelope is uploaded as an artifact. After import, the helper removes the artifact, temporary branch, and recipient certificate/private key. The default CI workflow remains unchanged. Hosting credentials are not imported.

The local copy is stored at `%LOCALAPPDATA%\NotebookPlush\signing\release-key.dpapi`, protected by Windows DPAPI for the current account. Its directory grants access to that account and SYSTEM. This store is specific to the Windows account and PC; keep a separate stable-key backup. The script also pins the signing certificate SHA-256 from the published GitHub release.

```powershell
# Build and verify; no installation or publication:
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\build-release.ps1
# Verify an existing signed APK:
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\build-release.ps1 -SkipBuild
# Check PowerShell parsing, encryption interoperability, and JDK selection:
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\tools\test-release-scripts.ps1
```

`build-release.ps1` selects JDK 17/21, rejects overlapping Gradle builds, materializes the keystore in a private temporary directory, and invokes `assembleRelease` with configuration caching disabled so signing passwords are not serialized into the workspace cache. It restores process environment variables and removes temporary signing files even when the build fails. The resulting APK must have the same certificate as the published CI release.

`deploy-release.ps1` adds `-ListModels`, `-Model`, `-SkipBuild`, and `-DryRun` switches. The default model is `SM_X906B`; hyphens and underscores are interchangeable. It combines USB/wireless connections to the same device and rejects missing or ambiguous targets. Before installation, every selected device must have a compatible signing certificate and an older version, or no existing NotebookPlush installation. A dry run uses the existing APK and performs these checks without installing. Installation uses `adb install -r`, verifies the installed version, and never uninstalls apps to bypass a signing mismatch.

## Optional local publication

Production releases use the GitHub workflow below. These commands are available for local troubleshooting or a manually authorized publication.

Use a committed release revision; the manifest records its full 40-character SHA. Supply `NOTEBOOKPLUSH_UPDATE_USER` and `NOTEBOOKPLUSH_UPDATE_PASSWORD` through your environment or secret manager. They are SFTP credentials for the configured hosting account; no credentials are bundled into the app.

Run from the project root in PowerShell after configuring signing and hosting:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\build-release.ps1
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

The optional repository variable `NOTEBOOKPLUSH_KEY_ALIAS` defaults to `notebookplush`; set it if the release key uses a different alias. Main publications are serialized. Checks use read-only repository permissions; the publication job has contents-write permission to create a GitHub release after hosted verification succeeds. Each release uses its own version tag and retains the signed APK and manifest. Pull requests do not receive the release key or publish. Signed APK/manifest artifacts are also retained in the successful publication run. The workflow uses [checkout](https://github.com/actions/checkout/releases/tag/v7.0.0) v7, [Gradle setup](https://github.com/gradle/actions/releases/tag/v6.0.0) v6, and [Android SDK setup](https://github.com/android-actions/setup-android/releases/tag/v4.0.0) v4.

## Runtime behavior and checks

Release builds check once per Activity session, with state retained across rotation. Automatic checks default on and can be disabled in App updates. Offline failures stay silent at startup. Manual checks bypass the seven-day reminder delay. Update now downloads and verifies the APK, requests Android's per-app install permission where needed, and hands off to the system installer. Installation always requires Android confirmation. Canceling the installer keeps the verified download available for retry in the same session.

Requests and redirects use HTTPS and stay inside the configured host/directory. Manifest downloads are limited to 64 KiB, APKs to 200 MiB. Downloads are bounded by manifest size and are checked for exact bytes/hash, package/version, strictly newer build, and signer equality. Interrupted downloads are removed at the next process start.

```powershell
.\gradlew.bat :androidApp:testQaUnitTest :androidApp:lintDebug
.\gradlew.bat :androidApp:connectedQaAndroidTest
uv run --python 3.13 python tools/test-publish-update.py
```

Unit tests use fake transport/SFTP and real JSON parsing; they do not contact the host. Device tests exercise JSON colors, independent tab undo/cursors, migration, rotation, and workspace restoration in a separate QA app. A live release-to-release installation remains to be exercised after signing and the host are provisioned.
