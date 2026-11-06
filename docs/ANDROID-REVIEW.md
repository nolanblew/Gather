# Native Android independent review

Review date: 2026-11-06. Scope: native Kotlin/Compose client using the same Gather account, workspace, sharing, search and backup API as the browser. Security review used the local `security-review` skill.

## Evidence completed

`tests/native-review.test.js` passed 4/4 using Node 24.22.1. These are independent HTTP tests against temporary data directories, with fuzzy search to avoid external provider calls:

- Browser cookies cannot authenticate bearer requests, native bearer tokens cannot authenticate cookie requests, malformed Authorization never falls back to cookies, session/export responses omit secrets and use no-store.
- A browser owner and native editor create 12 items concurrently with no lost commands; inaccessible users cannot search content; editors cannot delete; exports exclude joined lists and sharing secrets; removal immediately blocks writes and remains effective after restart.
- Native password changes revoke old browser/native sessions, issue a fresh native credential, persist across restart and permit explicit logout.
- Native reads and writes reject cross-origin requests, reject inappropriate content type and oversized authentication input, and accept normal native requests without browser Origin headers.

The production API is not exercised by this file; tests never touch production volumes or accounts. The first run corrected a test assumption: a removed member receives 404 for a now-inaccessible list, which is intentional resource hiding.

## Acceptance checklist

| Area | Required behavior | Verification status |
| --- | --- | --- |
| Shared service | Native and browser use the same account and authoritative workspace API; clients send granular commands | API verified; native granular command flow reviewed statically |
| Authentication | Register/login/session/password/logout, durable encrypted token, no credential logging, expired/revoked token recovery, no token/cookie interchange | API verified; encrypted TokenVault reviewed statically; physical startup/retry verified by root; corrupt-Keystore recovery remains static |
| Transport | HTTPS only; no redirect forwarding of bearer tokens; cleartext disabled; no cookie exchange | Manifest/network reviewed statically |
| Library parity | Library/favorites/templates/archive/category views, search with evidence and scope, list create/edit/copy/start/reset/archive/delete | Native UI reviewed statically; root reports Pixel + API 34 E2E passed |
| Content parity | Notes/checklist differences, quantities/notes, sections, group ordering, item create/edit/check/move/delete, empty states | Native UI reviewed statically; root reports Pixel + API 34 E2E passed |
| Personal state | Favorite/category/archive are per-user, including shared lists | Existing account tests cover API; native controls reviewed statically |
| Sharing | Owner/member roles, invite preview and explicit join, revoke/replace/remove/leave, no invitation items before join, no accidental autojoin on link open | Native mixed-transport acceptance verified; native explicit-join UX reviewed statically |
| Concurrency | Serial client mutations, server-side atomic granular edits, stale polling/search results do not overwrite newer state, revoked editor blocked | Concurrent server writes verified; native state review completed; fixes below |
| Backup | SAF export/import, full confirmation before append import, import bounded to 5 MiB, owned snapshots only, no password/token/member export | API export verified; native bounded SAF reads and import confirmation reviewed; SAF-specific lifecycle/account-switch checks listed below |
| UX/reliability | Loading/error/retry, keyboard resize, edge-to-edge insets, accessibility labels/tap targets, rotation/background resume, no duplicate writes on retry | Root reports Pixel + API 34 E2E and Pixel offline/startup retry passed; fault cases below |
| Delivery | Reproducible Gradle build/tests/lint, release signing kept outside source, APK checksum, production backup before rollout | Root integration responsibility |

## Static observations

The initial Android manifest disables backup and cleartext traffic. The network layer requires an HTTPS server and disables redirects. TokenVault uses a non-exportable Android Keystore AES/GCM key, commits encrypted preferences, and clears unreadable ciphertext rather than crashing on a stale key. No native disk workspace cache or queued offline writes appeared in the initial source.

## Remaining evidence

Native Compose source review covered library views, evidence-backed search results, note/checklist behavior, list/category/item/section editors and ordering, template cloning/reset rules, personal flags, sharing member controls, explicit invite acceptance, bounded SAF import with confirmation, backup export, shared text capture, appearance and accessibility descriptions. No P1/P2 API findings were found in the independent checks.

Review findings sent to the Android implementer and confirmed fixed in a subsequent source read (runtime race/recovery checks remain separate):

- P2: Queued sharing/password/export operations needed an account epoch guard after acquiring the mutex, preventing an old operation from dispatching with a newer account token. Guards now appear in source. Sharing responses now bind to selected list.
- P2: Password rotation must invalidate existing request epochs and cancel old searches before dispatch; otherwise an old-token 401 can clear a freshly rotated session. The final source invalidates at both start and success. Wrong-current-password 401 now shows a correction without clearing the login.
- P2: OkHttp automatic connection retry must be disabled for non-idempotent granular commands. `retryOnConnectionFailure(false)` now appears in source; client reconciles ambiguous command outcomes with a read rather than replay.
- P2: A saved-token session check that fails before username loads needed a Retry action. The final source adds Retry connection and reruns verification while preserving the token.
- P2: Android Back needed to return from list detail/settings to library before leaving the activity. The final source adds BackHandler for these states.

## Final correction review

A final read of the corrected source confirms the three later P2 findings are addressed:

- Export JSON and its originating account epoch/username now live in `GatherViewModel.PendingExport`, outside instance-state bundles. The picker launch is driven by `exportReady`, and the restored launcher calls `finishExport`. Document writes run in `viewModelScope` with the application content resolver, surviving activity rotation. Consuming an export checks the account epoch and username; a missing or invalidated pending export explicitly reports that no backup was written. No old composition callback or multi-megabyte `rememberSaveable` payload remains.
- Sharing mutations clear transient tokens before dispatch. Failed mutations reload safe sharing metadata and explicitly discard tokens. Copy/share clicks revalidate owner, selected list, connection/busy state and expiry through `invitationLink`; UI copy/share controls are disabled while disconnected/busy.
- Incoming shared text increments `captureId`, including identical text shared twice. Both the opening effect and CaptureForm composition use this event ID, replacing stale remembered fields for a new share.

No unresolved P1/P2 finding remains in this static pass. Build/lint and runtime evidence are separate: root reports zero lint errors, eight passing Android unit tests, a signed minified APK, successful full Pixel and Android API 34 E2E flows, plus manual Pixel offline/retry and startup recovery. The reviewer independently ran the four native API acceptance tests and read source; the reviewer did not execute those native device runs.

## Remaining targeted runtime checks

These fault/lifecycle cases have source-level coverage but were not reported as executed in this review:

1. Rotate while export fetch is in flight, while CreateDocument is open, and while a slow document provider writes. Confirm exactly one picker/written backup, valid nonempty JSON equal to the owned server export, and success only after the write completes.
2. Revoke/sign out the originating account while the export picker is open; returning a URI must not write that account's snapshot under a new session. Kill the process while the picker is open; returning must explicitly report no backup written rather than claim success. Normal cancel should produce no success message.
3. With capture open, deliver a second ACTION_SEND containing different text, then identical text as another event. Confirm the new event replaces the form contents and one Save adds exactly the text currently displayed.
4. Drop the response after the server commits invitation replacement/revocation. Confirm the previous token cannot be copied/shared, recovery shows only safe metadata, and creating a fresh invitation produces a working link. Also drop a create/clone response and confirm the client reads authoritative state without automatically replaying the mutation.
5. Exercise wrong-current-password and an old search response arriving around password rotation; confirm the fresh session remains signed in. On a device with unreadable credential ciphertext, confirm startup clears it and returns to sign-in without crashing.





Final auth polish source check: successful sign-out uses a normal message with no saved token, so AuthScreen does not offer a misleading Retry. A real saved-token startup network failure still offers Retry; expired sessions clear the token. authenticate() clears both error and message, preventing the sign-out notice from surviving the next login. No unresolved finding in this bounded adjustment.
