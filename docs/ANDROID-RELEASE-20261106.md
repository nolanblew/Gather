# Gather Android and shared API release — November 6, 2026

## Delivery

Native Kotlin/Jetpack Compose app, minimum Android 14 (API 34). Release application ID `ninja.takeya.gather`; debug is separate at `ninja.takeya.gather.debug`. The release endpoint is fixed to `https://gather.takeya.ninja/`. No WebView or production cleartext networking is used.

The web client and Android client share the same account-scoped API, granular commands, sharing authorization, search engine and backup format. Native sessions use hashed bearer tokens on the server and Android Keystore encryption on the phone. Browser sessions remain HttpOnly cookies. The migration adds a session transport column defaulting existing sessions to cookies.

## Local verification

- All 73 backend/web tests passed on Node 24, including independent account, sharing, native session, web response, search and backup tests. TypeScript and the production Vite build passed.
- The built production Docker image passed 33 API/account/store integration tests against its packaged runtime.
- Browser testing created an isolated account, list and item through the local web UI. A separate live smoke checked native registration, browser-cookie login, cross-client edits, completion/notes, search, export and logout.
- The initial complete native UI acceptance journey passed on Pixel 7 Pro `28301FDH300ERZ` running Android 16 (16.188 seconds) and the Android 14 emulator (17.277 seconds). It exercised normal sign-in, list/item edits, search, favorites, sharing/member removal, template copies, archive/restore, password-error handling, appearance and sign-out.
- Manual Pixel tests verified the current view becomes read-only when the local connection is removed, Retry restores it, and a cold start without a connection can recover the persisted sign-in without entering credentials again.
- Independent source review identified and confirmed fixes for stale account requests, password rotation races, obsolete invitation links, export retention across recreation, incoming share replacement, and system Back behavior. See `ANDROID-REVIEW.md` for scope and limits.

The first Pixel instrumentation attempt exposed an obsolete Espresso reflective input call. Espresso 3.7.0 uses the supported system service path; the official [AndroidX Test release notes](https://developer.android.com/jetpack/androidx/releases/test) describe that fix. A test-only FAB semantics selector was corrected before the passing acceptance runs.

## Release safeguards

The server image was built locally, transferred with matching SHA-256 `9eaed7bc7e7a3c3b9eacefca4825e7ce9ae7ae5884f37f29cecfe236f4692b97`, and staged under `/home/nolan/dockers/gather/releases/20261106-android`.

Deployment uses a stopped-volume archive and a new cloned volume. The original volume remains untouched. `inspect-release-data.mjs` compares account/workspace table counts and SHA-256 digests before/after migration, excluding only the new session transport column. Release checks include integrity, exact image and volume identity, native capabilities, public HTTPS, and unchanged unrelated container IDs/start times/restart counts. Only Gather is recreated.

Release signing material is retained outside this checkout at `C:\Users\nolan\.codex\secrets\gather-android`. Keep that directory for app updates. The certificate SHA-256 is `75:4B:1A:8C:7C:8E:78:AF:87:A9:A3:F0:60:B7:80:A1:FA:0F:FE:9E:C8:82:10:34:B4:84:2E:37:D1:AB:06:4D`; `public/.well-known/assetlinks.json` binds it to the release application.

## Assets and limitations

The welcome illustration was generated through the built-in image tool and bundled locally. `OPENAI_API_KEY` was absent from the process; the tool did not expose the requested Imagen model selector. No claim is made that the illustration used Imagen 2.6 Sunburst. The original Gather SVG brand was converted into a native vector drawable. Prompt/provenance is recorded in `ANDROID-ASSETS.md`.

Changes require a connection. The in-memory view can remain readable during a temporary outage; this release does not add persistent offline synchronization, email password recovery, automatic repeat scheduling, or push notifications. Repeat labels remain manual workflow metadata, matching the web app.

## Production verification

Deployment completed at `/home/nolan/dockers/gather/releases/20261106-android-verified`; the `current` symlink selects that release. The running image is `gather:20261106-android`, image ID `sha256:b233417ef62e8695ec2910a5f7bb2232c6e66a91e943b7bf5c9e73cfebf23365`. Its active data volume is `gather_lan_accounts_20261106_android_verified`.

The stopped pre-upgrade archive is `data-before-accounts.tar` in that release directory, SHA-256 `649e562be193ba94add1c8cc40f303847839e9af02301f46afbaea613e6529c1`. Account/workspace integrity checks passed and table counts/digests matched through migration. The prior release `/home/nolan/dockers/gather/releases/20261106-copy` and volume `gather_lan_accounts_20261106_copy` remain available for rollback. All 29 unrelated containers remained unchanged. Machine-readable evidence is in `output/android-deployment.json`.

The initial deployment attempt safely restarted the prior service when the read-only SQLite fingerprint helper encountered WAL handling. The helper now checks temporary copies of SQLite/WAL files, and migration is verified offline on the cloned volume before the new server is published. The subsequent verified deployment passed all checks.

Public HTTPS health, capabilities, browser UI, and Digital Asset Links returned successfully. A synthetic public account exercised native registration, browser login, cross-client CRUD, search, export and logout. The signed release on the physical Pixel preserved its login through an app update and process restart. Checkbox edits from that APK were independently verified through a browser-cookie API session. Android reports `gather.takeya.ninja: verified` for the release certificate.

## Device and lifecycle evidence

The final three-test acceptance suite passed on the Pixel 7 Pro running Android 16 (26.505 seconds) and Android 14 emulator (30.523 seconds), including the final sign-out notice correction. In addition to the full product journey, it checks incoming share replacement, retained export across activity recreation, stale invitation removal after failed sharing, externally deleted editor targets, removal of the final selected category, and immediate clearing of results when search scope changes. These tests use disposable local accounts and refuse production endpoints.

On the physical signed release, a real Storage Access Framework export survived rotation while the file picker was open and produced valid version-2 JSON. Importing that backup into the synthetic account added private copies while retaining its existing lists. Offline/reconnect and cold-start session recovery were also tested. These checks do not claim process-death recovery for an unfinished export or persistent offline sync.

The final Kotlin unit suite passed 11/11. Android lint reported zero errors and 22 warnings, primarily dependency/target updates and intentional synchronous credential persistence. Minified release APK and Android App Bundle builds succeeded. No AndroidRuntime errors were present in either device's final log buffer. Screenshots are retained in `output/android-screenshots`.

The Pixel is left with the signed release installed. Synthetic QA sessions are signed out, and the separate debug/test packages and local ADB reverse mapping are removed.

## Signed artifacts

- Installable APK: `output/android-release/Gather-1.0.0.apk`, SHA-256 `5c2f6cd53ac8054da29e342c75f6a729304561982f9b3796f1805e1131920739`.
- Android App Bundle: `output/android-release/Gather-1.0.0.aab`, SHA-256 `69be9ab3394a4b73ffef688ce617b3634bbe5fdff8547bde51898a1dfbefa3dc`.
- APK v2 signature verified with the retained release certificate. Package metadata confirms Android 14 minimum, target API 35, version 1.0.0, and application ID `ninja.takeya.gather`.
- Both release artifacts use the public HTTPS endpoint; the local test endpoint is a debug-only build override.

A final sign-out presentation fix separates success notices from authentication errors and shows Retry only for a retained session whose verification failed. Starting a new sign-in clears the previous notice. Independent review confirmed this adjustment; the acceptance journey additionally asserts that successful sign-out has no error or misleading retry action.
