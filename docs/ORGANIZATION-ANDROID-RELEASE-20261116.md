# Gather Android 1.2.0

## Pixel update on November 16, 2026

- The signed release build passed Gradle release checks. Android package `ninja.takeya.gather` reports version `1.2.0` and version code `5`.
- Verified the APK signature against the installed `1.1.1` (code `3`) APK. Both use certificate SHA-256 `754b1a8c7c8e78af87a9a3f060b780a1fa0ffe9ec8821034b4842e37d1ab064d`.
- Installed the update over the existing app with `adb install -r` after the production service passed its workspace version 3 checks. Android reported the same first install timestamp, `2026-11-06 01:00:47`, and an update time of `2026-11-16 16:41:11`. The launch succeeded with zero startup crashes observed.
- Gather showed the signed-out screen before and after the update. This limited the release smoke test to startup and installation continuity. The update required no uninstall, storage reset, or account action. The production service independently verified unchanged content hashes for 9 lists in 2 workspaces and retention of all 3 sessions.
- Debug verification used the isolated test service. All 18 unit tests and 4 physical Pixel journeys passed, including the organization journey, existing list workflows, rotation and recreation, and injected fold layouts in LTR and RTL. Lint reported zero errors and eight existing warnings.

The local signed APK is `android/app/build/outputs/apk/release/app-release.apk` (4,877,481 bytes). APK SHA-256 is `a843822cb4bf14796c19ac23d99aec3fbcff00befd800c7a30fa1dd1fc3c24a2`.

## Manual GitHub releases

Once the [workflow](../.github/workflows/android-release.yml) is on the default branch, repository maintainers can open **Actions → Release Android APK → Run workflow**. Choose the reviewed ref and enter a tag in the form `android-vX.Y.Z` that matches `versionName` in `android/app/build.gradle.kts`. For this release, enter `android-v1.2.0`. Increase `versionCode` as well when preparing a new version.

Configure the existing production signing materials under **Settings → Secrets and variables → Actions → Repository secrets**. Preserve the existing keystore and its private backup. [GitHub Actions secrets](https://docs.github.com/en/actions/security-for-github-actions/security-guides/using-secrets-in-github-actions)

| Repository secret | Value |
| --- | --- |
| `GATHER_ANDROID_KEYSTORE_BASE64` | Base64 encoding of the binary production keystore |
| `GATHER_ANDROID_STORE_PASSWORD` | Existing `storePassword` |
| `GATHER_ANDROID_KEY_ALIAS` | Existing `keyAlias` |
| `GATHER_ANDROID_KEY_PASSWORD` | Existing `keyPassword` |

The workflow checks the APK’s package, version and signing certificate. It retains `Gather-X.Y.Z.apk` and its SHA-256 file with the Actions run for 30 days, then attaches both to the GitHub Release for the tag. If the tag already exists, it must identify the workflow's exact commit. An existing APK asset is retained as-is.

The hosted workflow has not run for this release. The Pixel installation used the locally built and verified APK.
