# Gather for Android

A native Kotlin + Jetpack Compose client for Gather. Android 14 (API 34) or newer is required. The release connects to **https://gather.takeya.ninja/**; it shares the same account, library, templates, and invitations as the web app.

## Build on Windows

Install JDK 17+, Android SDK platform 35 and build-tools 36.0.0. Set `JAVA_HOME` and `ANDROID_HOME`, or create an untracked `local.properties` with `sdk.dir=F\:\\path\\to\\android-sdk`. The checked-in Gradle wrapper downloads Gradle 9.1.0; dependencies are pinned in the build files.

```powershell
$env:JAVA_HOME = 'F:\Android\openjdk\jdk-17.0.12'
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

The default debug app also uses the public HTTPS service. For an isolated local server on this computer, first run that server with a disposable data directory and then:

```powershell
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest -PgatherServerUrl=http://127.0.0.1:48733/
& "$env:ANDROID_HOME\platform-tools\adb.exe" -s DEVICE_SERIAL reverse tcp:48733 tcp:48733
& "$env:ANDROID_HOME\platform-tools\adb.exe" -s DEVICE_SERIAL install -r app\build\outputs\apk\debug\app-debug.apk
```

Debug uses application ID `ninja.takeya.gather.debug`, so it can coexist with the production app. HTTP overrides are permitted only for `localhost`, `127.0.0.1`, or emulator host `10.0.2.2` in debug. Debug network security also restricts cleartext to those hosts. Release ignores `gatherServerUrl`, always uses the public HTTPS origin, forbids cleartext, disables Android backups, and requires a real release signature.

## Signed release

Release application ID: `ninja.takeya.gather`. Keep the signing keystore and its properties outside source control. By default Gradle reads `$HOME/.codex/secrets/gather-android/release.properties`; `GATHER_SIGNING_PROPERTIES` can select another private file. Its keys are `storeFile`, `storePassword`, `keyAlias`, and `keyPassword`. Use forward slashes in `storeFile`. Do not print or commit credentials.

```powershell
.\gradlew.bat :app:assembleRelease
```

The signed, optimized APK is `app/build/outputs/apk/release/app-release.apk`. Release packaging fails if signing is unavailable. Retain the same keystore for upgrades. The website's `/.well-known/assetlinks.json` must contain this package and release certificate fingerprint to verify Android App Links. Debug signatures are deliberately not trusted by production App Links.

## What is native

Every screen is Compose, including registration/sign-in, library/favorites/templates/archive, scoped search, personal categories, list details, checklist and notes editing, quantities/notes/sections, accessible ordering controls, template copies and checklist reset, member management/invitation preview and joining, password changes, appearance, and backup import/export. Android's share sheet distributes invitation links. Gather also receives shared plain text for capture into an existing list. SAF file pickers read and write JSON without storage permissions. Archive, deletion, reset, membership removal, sign-out, and backup import have explicit confirmations where appropriate.

The Paper, Cloud, Forest, Midnight, and System appearance choices are phone-local. The original Gather vector mark and a bundled welcome illustration avoid external font or image dependencies. Controls use Material minimum touch targets, accessible labels, visible focus, scrollable forms, keyboard insets, and light/dark status bar contrast.

## Connection and privacy behavior

A connection is required for all server changes. Workspace content is held only in memory. A failed connection leaves the current view visible and read-only; **Refresh** restores authoritative data. Restarting the process requires the server to load the library. There is no persistent offline library or offline write queue. Successful mutations replace the entire in-memory workspace with the server's response.

The ViewModel serializes writes, workspace refreshes, and sensitive account operations. Coroutine cancellation cancels OkHttp calls. Session epochs invalidate old responses before changing account or rotating a password; search additionally binds its results to the exact query, collection, and category. JSON decoding runs off the UI thread. HTTP transport never automatically retries a write. An interrupted mutation shows an unknown-outcome message and re-reads the server; it never blindly replays the request. Collaborator changes refresh on focus and every 30 seconds while resumed.

Opaque bearer tokens are encrypted with Android Keystore AES-GCM and held in app-private preferences. No password or token is logged, saved in an activity state bundle, included in backups, or placed in a request URL. Invalid Keystore state safely discards the unusable local session. Sign-out awaits server revocation; failures remain visible so the user can retry. Password changes preserve this phone's new token and revoke other sessions. Expired sessions clear the old account's in-memory content immediately.

Invite links use the canonical HTTPS host with `#invite=TOKEN`. Tokens are extracted into memory, removed from incoming intents, previewed via JSON POST only after authentication, and joined only after the recipient chooses **Join list**. Android App Links verification enables direct opening after deployment; **Open with Gather** or sharing a Gather link to the app also works. The owner controls invitation replacement/revocation and member removal. Editors retain content editing and private copy controls but cannot change ownership-only settings or delete the canonical list.

JSON export includes only owned lists and personal categories. Import adds private copies with fresh IDs and does not replace existing data or access. File reads and API responses are bounded; the server performs the authoritative document validation.

## Validation

`testDebugUnitTest` covers collection/category filtering, ordering, canonical invite parsing, JSON payload types, future response metadata, and account/password-rotation epoch invalidation. `lintDebug` checks manifest/API usage and Compose integration.

The `androidTest` journey requires a disposable local service at `127.0.0.1:48733` and the debug URL override. It rejects production or nonlocal targets. Install both debug APKs and run `androidx.test.runner.AndroidJUnitRunner` with `gatherTestUsername` (prefix `android-e2e-`) and `gatherTestPassword` instrumentation arguments. It exercises real Compose UI and independent cookie/native clients. Screenshots and semantic evidence are saved in the test app's `acceptance` directory. Consult the repository's Android review/release evidence for actual device results; a compiled test is not a completed device run.

Build outputs, local SDK settings, credentials, and signing keys are excluded from source. Main and unit-test Kotlin sources were formatted with ktfmt's Kotlin style.
