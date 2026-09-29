# NotebookPlush

A cozy Android text editor for JSON, built with Kotlin and Compose Multiplatform.

[GitHub repository](https://github.com/ReinhardC/NotebookPlush) · [Download releases](https://github.com/ReinhardC/NotebookPlush/releases) · [Build and release runs](https://github.com/ReinhardC/NotebookPlush/actions)

## Editing JSON

- JSON syntax highlighting gives keys, strings, numbers, and booleans/null distinct colors.
- Edit plain text with a monospace font, line numbers, undo/redo, and optional word wrapping.
- Open a file with the Android document picker and use Save as to export UTF-8 text. Editing never reformats or validates the JSON automatically.
- Rename your draft above the editor, or switch to Focus mode to hide the toolbar. The footer shows the cursor's line and column.
- Keep multiple files open in scrolling tabs with JSON/file icons and a dot for unexported changes. Each tab keeps its own text, cursor, and undo/redo history while open.
- All tabs autosave locally after 500 ms of inactivity and when the app leaves the foreground. Names, exact text, cursors, and the selected tab are restored on the next launch. Local autosave does not overwrite an opened file; use Save as to export your edits. Closing a file with unexported changes asks before removing its local draft.
- Light and dark themes follow the device setting.

The editor uses [Sora Editor](https://github.com/Rosemoe/sora-editor/tree/0.24.6) 0.24.6 (LGPL 2.1 or later; upstream marks this release as prerelease), hosted in Compose with `AndroidView`. JSON highlighting uses Sora's TextMate engine and the [VS Code JSON grammar](https://github.com/microsoft/vscode/blob/main/extensions/json/syntaxes/JSON.tmLanguage.json), with custom light/dark themes. Grammar and themes are bundled for offline use. Third-party license copies are in `third-party/` and bundled in the APK's assets.

Sora is Android-specific; additional platforms will need their own editor adapter, while the Compose app shell and workspace model can stay shared. Existing single-file drafts are migrated automatically. Undo history lasts for the current Activity; text and cursors also survive rotation and relaunch.

The supplied blue plush notebook artwork sits oversized at the upper left, overlapping the page beneath the tabs. The translucent top and bottom bars, raised tabs, and accent line borrow Easynews's layout. The artwork is also used for the Android launcher icon, with adaptive icons on Android 8+ and density-specific icons for Android 7. The original PNG stays in the project root.

## Hosted updates

The update button checks the NotebookPlush feed at `https://clausbilder.de/notebookplush/`. Signed release builds also check once at startup, show a prompt only for newer builds, and support a seven-day Later reminder and an automatic-check toggle. Downloads are verified against their size, SHA-256, package, version, and the installed app's signing certificate before Android asks for installation confirmation.

The app and publisher share `tools/update-host.json`. GitHub CI builds, signs, and publishes releases after checks pass on pushes to `main`, using signing and hosting secrets configured from Easynews. Uploads use Telekom's SFTP server `hosting.telekom.de`; downloads use the separate NotebookPlush directory on `clausbilder.de`. Verified releases are also downloadable from GitHub Releases. See [CI release and hosting setup](docs/updates.md). Debug/QA builds do not check automatically, and a release APK cannot update an installation signed with a different key.

## Open and run

1. Open this folder as a project in IntelliJ IDEA (with Android and Kotlin Multiplatform support) or Android Studio.
2. Let Gradle sync. Use JDK 17 or newer for Gradle (this machine has JDK 21).
3. Select the `androidApp` module and an Android emulator or connected device, then Run.

The app supports Android 7.0 (API 24) and newer, and compiles and targets API 37. Install Android SDK platform 37 (`platforms;android-37.0`) and build tools 36.0.0 if needed. `local.properties` points to this machine's SDK and is ignored by Git; adjust it on another machine.

From PowerShell in this folder:

```powershell
.\gradlew.bat :androidApp:assembleDebug
.\gradlew.bat :androidApp:lintDebug
.\gradlew.bat :androidApp:testQaUnitTest
# Editor integration tests (requires a device or emulator):
.\gradlew.bat :androidApp:connectedQaAndroidTest
# Publisher tests use fake network/SFTP; Python 3.11+:
uv run --python 3.13 python tools/test-publish-update.py
# With a device or emulator connected:
.\gradlew.bat :androidApp:installDebug
```

The APK is generated at `androidApp/build/outputs/apk/debug/androidApp-debug.apk`.

Device tests install a separate `com.notebookplush.testing` app, preserving the normal app and its drafts.

## Build a signed release locally

The PowerShell helpers use the same release signing key as GitHub CI. Run the setup command once on each Windows account, with GitHub CLI installed and repository write access:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\setup-release-key.ps1
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\build-release.ps1
```

The signed APK is `androidApp/build/outputs/apk/release/androidApp-release.apk`. The build helper selects JDK 17/21, builds the release, and verifies that its signing certificate matches the published CI APK. It does not install or publish anything. Signing material stays outside the checkout in a Windows-account-encrypted store; temporary keystores are removed after each build.

The device helper follows Easynews's model selection:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\deploy-release.ps1 -ListModels
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\deploy-release.ps1 -Model SM-X906B -SkipBuild -DryRun
# Build and install on the selected device:
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\deploy-release.ps1 -Model SM-X906B
```

`-SkipBuild` uses the existing verified APK; `-DryRun` checks it and the selected devices without building or installing. Deployment only updates compatible installations and never uninstalls an app. An installation signed with a debug key is refused, preserving its drafts. See [local signing details](docs/updates.md#local-signed-builds).

## Code

- `shared/src/commonMain/kotlin/com/notebookplush/App.kt`: shared Compose UI. Start editing here.
- `androidApp/src/main/kotlin/com/notebookplush/MainActivity.kt`: Sora editor adapter, document picker, and local autosave.
- `androidApp/src/main/kotlin/com/notebookplush/JsonHighlighting.kt`: offline TextMate grammar/theme setup.
- `shared/src/commonMain/kotlin/com/notebookplush/model/Workspace.kt`: document tabs and selection.
- `androidApp/src/main/kotlin/com/notebookplush/storage/`: atomic workspace persistence and state retained across rotation.
- `androidApp/src/main/kotlin/com/notebookplush/update/`: hosted checks, download verification, reminders, and installer handoff.
- `tools/`: shared hosting configuration and publication scripts adapted from Easynews.
- `.github/workflows/build.yml`: build checks and configured main-release publication.
- `androidApp/src/main/assets/textmate/`: JSON grammar, language configuration, and custom themes.
- `shared/src/commonMain/composeResources/drawable/plush_notebook.png`: shared app artwork.
- `androidApp/src/main/res/mipmap-*`: launcher icon resources.
- `gradle/libs.versions.toml`: dependency and plugin versions.

Only Android is enabled for now. The UI lives in a Kotlin Multiplatform module so additional targets can be added later.

Uses the separate Android application and shared library structure described in the [Android KMP guide](https://developer.android.com/kotlin/multiplatform/plugin).
