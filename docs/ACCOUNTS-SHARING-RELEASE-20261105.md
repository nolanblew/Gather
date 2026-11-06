# Accounts and shared lists — November 5, 2026

Delivered at **https://gather.takeya.ninja/** as `gather:20261105-accounts`.

## Product decisions

PM, UX and backend agents used GPT-6.1-Sol with high reasoning. The orchestrator reviewed their proposals, implementation and evidence and made the final decisions. See [requirements](ACCOUNTS-SHARING-PRD.md) and [UX](ACCOUNTS-SHARING-UX.md).

- Anyone can create a username/password account with an empty private library.
- A shared list is one canonical list with an owner and editors. Explicit acceptance is required; one-use links expire in seven days. Owners manage access and deletion; editors can leave.
- Favorites, categories, archive and ordering remain personal. Shared template starts and duplicates create independent private copies.
- Export contains owned lists. Import adds private copies and never restores permissions or overwrites shared content.

Review caught and fixed invitation-token exposure in preview URLs, existing members consuming pending invitations, owner self-removal changing personal preferences, missing accepted-person visibility, aggregate limits on joins, stale Share-dialog data and asynchronous account-switch cleanup. Preview uses a POST body and invitation navigation uses a URL fragment. Sessions and membership are rechecked after asynchronous work.

## Verification

- **62/62 automated tests passed on Node 24 on Windows and in the actual Linux release image**, including 11 independently written HTTP acceptance tests. TypeScript and the Vite production build passed.
- Coverage includes three-account isolation, all mutation families, forged parent/category IDs, invite replacement/revocation/expiry/simultaneous redemption, private template copies, additive imports, per-person organization, durable sessions, targeted operator recovery and legacy migration.
- Real held-request tests prove revoked membership removes search evidence before an in-flight hosted response returns, and logout during an unfinished request body prevents a captured session from saving.
- Actual Edge browser checks: public account creation; invitation through signup; explicit Join list; editor add/check reflected in owner's list; membership refreshed while Share stays open; owner removal rejects a stale editor and closes inaccessible content; switching accounts in a second tab clears the previous account's list, unsent draft and URL. Tested desktop and a 360px viewport in light and dark themes, without horizontal overflow.
- Live HTTPS API smoke exercised empty new accounts, secure cookies, private list/search isolation, explicit joining, collaborative edits, personal archive, owner permissions and revocation. Temporary test content and both temporary accounts were removed afterward. Public registration was also rendered in a live HTTPS browser.

Screenshots are under `output/playwright/`: `accounts-mobile-signup.png`, `accounts-mobile-invitation.png`, `accounts-mobile-shared-list.png`, `accounts-desktop-sharing.png`, `accounts-mobile-dark.png`, `accounts-mobile-people-dark.png`, and `accounts-live-registration.png`. Browser phone coverage uses viewport emulation, not a physical device. Collaboration refreshes on focus and polling; offline editing is not supported.

## Deployment and rollback

The source folder has no Git metadata. Pre-change source is archived at `output/source-before-accounts-20261105-173828.zip`.

The release image passed its Linux suite before transfer. SHA-256 of the transferred image archive matched `d5751448288b1bd14bb4bc9bdeb5603f59a0a09d6d7227b98e75d1d059e6fecf`.

- Release/current target: `/home/nolan/dockers/gather/releases/20261105-accounts`
- Active volume: `gather_lan_accounts_20261105`
- Untouched rollback volume: `gather_lan_data`
- Complete stopped-volume backup: `/home/nolan/dockers/gather/releases/20261105-accounts/data-before-accounts.tar` (mode 0600)
- Previous release: `/home/nolan/dockers/gather/releases/20261023-pwa`

Only Gather was stopped and recreated. The deployment inventory checked 30 containers and verified every unrelated container's ID, start time and restart count remained unchanged. Gather is healthy with its existing 0.5 CPU / 512 MiB limits. The original account/password, session mappings and exact original one-list export were verified after migration; prototype deletion was unnecessary.

`scripts/deploy-accounts.py` clones the stopped original volume and switches only Gather to the new volume. Failure during its health gates restores the previous Compose deployment. After a successful release, rolling back to the old volume discards subsequent writes from the active service; preserve a fresh active-volume backup before an operator rollback. The two data volumes must never be merged or substituted silently.

## Account recovery

On the operator machine, supply the new password through the environment, not command arguments. From the active Compose directory:

```powershell
$env:GATHER_RESET_PASSWORD = Read-Host 'New password' -MaskInput
docker compose -f compose.lan.yaml exec -e GATHER_RESET_PASSWORD gather node server/reset-account.mjs --username USERNAME --confirm
Remove-Item Env:GATHER_RESET_PASSWORD
```

For remote SSH deployment, use the equivalent environment on the Docker host. Recovery targets one normalized username, preserves its stable ID and sharing, and revokes only its sessions. The retired reset-all/setup-key command is rejected. No email recovery is configured.

## Welcome follow-up

Published gather:20261105-welcome with separate welcome, sign-in and registration screens. Existing 90-day cookies are unchanged; isolated live browser and cookie-free requests verified signed-out state and protected workspace 401. No 2FA added. TypeScript/build and browser navigation passed; live 360px welcome has no horizontal overflow. Active volume: gather_lan_accounts_20261105_welcome; previous volume retained. All 30 inventoried containers checked; unrelated containers unchanged.

