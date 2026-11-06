# Gather

![Gather welcome artwork](public/assets/gather-welcome.png)

Gather is a responsive home for everyday lists, gift ideas, projects, and reusable trip checklists. The phone layout emphasizes quick capture and checking things off; desktop adds persistent navigation and a spacious library.

## Native Android app

The native Kotlin/Jetpack Compose app in [`android/`](android/README.md) supports **Android 14 and newer**. Its signed release uses **https://gather.takeya.ninja/** and shares accounts, lists, templates, search, invitations, and backups with the web client. It includes Paper, Cloud, Forest, Midnight and System appearance, native sharing and text capture, animated progress, and Android file pickers for JSON backups.

Both clients use the documented [shared API](docs/API.md). Web sessions use HttpOnly cookies; Android uses separately scoped bearer sessions encrypted with Android Keystore. An internet connection is required for changes; a current in-memory view remains read-only during connection loss.

See [Android build, signing and local testing](android/README.md), [independent review](docs/ANDROID-REVIEW.md), and [asset provenance](docs/ANDROID-ASSETS.md). Keep the release signing key outside the repository and retain it for future app updates.

Android **1.1.2** adds refined layouts for foldables and larger screens. See the [adaptive design](docs/ANDROID-ADAPTIVE-DESIGN.md) and [release verification](docs/ANDROID-1.1.2-VERIFICATION.md).

## Run locally with Docker

Requires Docker Desktop running with Linux containers. From this folder in PowerShell:

```powershell
docker compose up --build -d
```

Open **http://localhost:3000**. The default port binding is local to this computer. SQLite data lives in the named `gather-data` volume and survives container rebuilds and restarts.

```powershell
docker compose ps
docker compose logs --tail 50
docker compose restart
docker compose down
```

`down` keeps your lists. `down -v` removes the database volume and all its saved lists.

For a phone on the same trusted Wi-Fi network, optionally set the bind address before starting:

```powershell
$env:GATHER_BIND_ADDRESS = '0.0.0.0'
docker compose up -d
```

Then visit `http://<this-computer-LAN-address>:3000` on the phone. Windows Firewall must permit port 3000 on the private network. Each person creates an account and has a private library, with optional sharing per list. For internet access, use an HTTPS reverse proxy and `GATHER_COOKIE_SECURE=true`; do not expose the app port directly to the public internet.

## Account access

Choose **Create account** on the sign-in screen. Usernames are unique without regard to case. Each new account starts with an empty private library; there is no email service or 2FA. The browser stays signed in for up to 90 days, including across server restarts. Signing out revokes that browser session. Settings → Account lets you change your password and signs out your other devices without affecting anyone else's account.

Registration does not require a setup key. Choose a password of at least 6 characters using any two of uppercase, lowercase, numbers, or symbols. Longer unique passwords are supported up to 128 characters. Do not paste credentials into chat, source files, or command arguments. Upgrading an existing household installation preserves the original account and assigns its lists only to that account; new registrations cannot claim them.

Passwords are salted and hashed with scrypt. Random session tokens are held in HttpOnly, SameSite cookies; only their hashes are persisted in `account.sqlite`. Authentication records are separate from lists and are excluded from portable workspace exports. Back up the entire persistent volume to preserve account access. Appearance preferences remain browser-local.

`GATHER_COOKIE_SECURE` defaults to `false` for local development and `true` in the LAN production Compose file. Production sign-in therefore requires the HTTPS URL; plain LAN HTTP remains available for health checks but cannot retain a sign-in cookie. TLS terminates at the reverse proxy. Gather checks mutation origins against the incoming Host; the proxy must preserve it.

If you forget your password, contact the server operator for a targeted account password reset. Recovery preserves the account's identity, lists and sharing relationships and revokes only that account's sessions. There is no email-based password recovery. The old reset-all/setup-key workflow is no longer supported.

## Share a list

Open a list and choose **Share → Invite one person**. Copy the link and send it to the person you want to invite. Each link works once and expires after seven days; making a new link stops the previous pending one. The recipient signs in or creates an account, then chooses **Join list**. Opening a link alone does not join or reveal list items.

Both people now use the same list. Item edits, notes, sections and checkmarks are shared. Favorites, categories, order and archiving remain personal. Owners manage invitations and people and can delete the list for everyone. Editors can edit content, make a private copy or leave, but cannot delete the shared list or change its kind, cadence or template status. Removing someone immediately blocks further server access; their edits stay.

Starting a shared template or making a private copy creates a separate list with fresh item IDs, unchecked items and no members. Sharing the master never automatically shares its copies. Changes refresh on focus and periodic polling; a network connection is required.

## Develop

Requires Node.js 24 or newer and pnpm 11.19.0. No external database is needed.

```powershell
pnpm install
pnpm dev:server
```

In a second PowerShell terminal:

```powershell
pnpm dev
```

Open http://localhost:5173. Vite proxies `/api` to the loopback server on port 3000. For a production build without Docker:

```powershell
pnpm check
pnpm test
pnpm build
pnpm start
```

The Node server serves the built app and API together at http://localhost:3000. `HOST`, `PORT`, and `DATA_DIR` configure the server; the default data directory is `data` in this project. `/api/health` reports health without authentication; workspace, export, import, actions, and search require a session.

## Your lists

- New accounts start with an empty library. Upgraded accounts can retain their original lists and samples.
- Choose Checklist for completion or Notes & ideas for bullets. Switching kinds preserves content and saved checkmarks if you switch back.
- Appearance opens a quick palette picker: Paper/Cloud in light mode and Midnight/Forest in dark mode. Midnight has a deep blue canvas. Light, Dark, or System mode and each mode's palette are saved separately on each browser. Phone users can open it from the palette icon in the header; Settings also includes the picker.
- Use favorites, categories, and search to find a list. Search includes items, notes, quantities, sections, and categories; typo and meaning matches show stored evidence.
- Item details hold quantity and notes. Sections and accessible ordering controls keep bigger lists organized.
- Templates are separate master lists. Starting one makes a dated copy with all items unchecked; the master stays intact.
- Templates carry their list kind and Manual/Daily/Weekly repeat label. An active checklist with checked items has a confirmed Start again action. Repeat labels never schedule automatic resets.
- Archive keeps lists recoverable. Permanent deletion and workspace restore require confirmation.
- Settings provides JSON export and **Import backup copy**. Import adds private copies after validation and leaves existing lists and sharing unchanged.

## Backup

Use Settings → Export backup for a portable, versioned JSON snapshot of lists you own, including templates, ordering, notes, categories and completed items. Joined lists are excluded; make a private copy first if you want to retain one. Accounts, membership and invitation secrets are never included. Store the file outside this project. **Import backup copy** adds private copies with fresh IDs, preserving saved checkmarks and leaving existing lists intact. Malformed or over-limit imports change nothing. Full-volume infrastructure backups preserve accounts and sharing relationships.

For infrastructure backups, stop the container before copying its SQLite database volume, or use the app's export while it is running. Do not copy a live SQLite file without accounting for its write-ahead log.

## Technical design

React + TypeScript + Vite; Node's HTTP server and built-in SQLite; one production container with a persistent volume. Granular commands mutate server-authoritative data in transactions, with account and membership checks on every request. Polling and focus refresh keep collaborators current. A network connection is required; there is no offline synchronization or live-cursor collaboration.

- [Product requirements](docs/PRD.md)
- [UX and prototype ratings](docs/UX.md)
- [API contract](docs/API.md)
- [Verification record](docs/QA.md)
- [Independent review](docs/REVIEW.md)
- [V2 requirements](docs/PRD-v2.md)
- [V2 UX](docs/UX-v2.md)
- [Search research and measured model comparison](docs/SEARCH-RESEARCH.md)
- [Install Gather and PWA connection behavior](docs/PWA.md)
- [Accounts and shared lists requirements](docs/ACCOUNTS-SHARING-PRD.md)
- [Accounts and sharing UX](docs/ACCOUNTS-SHARING-UX.md)

## Search on a shared mini PC

The default is private local hybrid search: exact and fuzzy matching plus the quantized Apache-2.0 MiniLM model (about 23 MB). Model files download on the first semantic query and persist under `/app/data/models`; `search.sqlite` holds disposable embeddings. Normal startup and health do not wait for AI. Fuzzy matching stays available when the model is loading or unavailable. Local mode sends no queries or household content to a hosted AI provider; model downloads contact Hugging Face.

Compose limits this container to **0.5 CPU and 512 MiB RAM**, with one inference thread. The model loads on demand and is released after five minutes without search. Indexing is bounded per request and cached. These caps limit competition with media services; they are adjustable with `GATHER_CPUS` and `GATHER_MEMORY_LIMIT`. They do not reserve priority for other containers, so keep those services' own resource settings appropriate.

Use `SEARCH_PROVIDER=fuzzy` for the smallest footprint with all AI disabled:

```powershell
$env:SEARCH_PROVIDER = 'fuzzy'
docker compose up -d
```

Optional hosted ranking uses GPT-6 Luna. Set server variables before recreating the container (or use an untracked `.env` file):

```powershell
$env:SEARCH_PROVIDER = 'openai'
$env:OPENAI_API_KEY = '<your key>'
$env:SEARCH_OPENAI_MODEL = 'gpt-6-luna'
$env:SEARCH_REASONING_EFFORT = 'low' # low or medium
docker compose up -d
```

Hosted mode sends the query and a bounded set of scoped list excerpts to OpenAI; it never changes lists. No key or a failed request falls back to text search. Local mode never switches to hosted mode automatically. Keep credentials on the server, outside source control, backups and browser code.

| Variable | Default | Purpose |
| --- | --- | --- |
| `SEARCH_PROVIDER` | `local` | `local`, `fuzzy`, or `openai` |
| `SEARCH_LOCAL_MODEL` | `Xenova/all-MiniLM-L6-v2` | Compatible 384-dimension feature-extraction model |
| `SEARCH_OPENAI_MODEL` | `gpt-6-luna` | Hosted model ID |
| `SEARCH_REASONING_EFFORT` | `low` | Hosted `low` or `medium` |
| `SEARCH_IDLE_MS` | `300000` | Release idle local model; bounded 1 second–1 hour |
| `SEARCH_MAX_EMBEDDINGS` | `4096` | Cached vector cap, bounded 128–8192 |
| `SEARCH_INDEX_BATCH_LIMIT` | `64` | Newly encoded chunks per request, bounded 8–128 |
| `SEARCH_OPENAI_BASE_URL` | `https://api.openai.com/v1/` | Optional Responses-compatible server endpoint |
| `GATHER_CPUS` | `0.5` | Compose CPU cap |
| `GATHER_MEMORY_LIMIT` | `512m` | Compose memory cap |

Native Node also accepts `SEARCH_MODEL_CACHE_DIR` for prefetched model files. Derived caches are excluded from workspace exports and can be rebuilt; removing them requires another first-query download. Search defaults to the current collection/category; explicitly choose Entire workspace to include templates and archived lists. Exact matches rank ahead of looser meaning matches. `/api/search/status` reports safe provider state.

Active searches continue indexing in bounded batches until their scoped content is covered. Retries pause while a list editor is open or the page is hidden. Above the vector cap, Gather reports limited smart search and stops rebuilding the same evicted vectors; text search still covers every scoped item. Changing scope or editing content can replace cached vectors. Hosted ranking uses at most 40 candidate lists and bounded excerpts, one concurrent request, a one-second minimum interval, and ten upstream calls per minute. `pnpm test:search-eval` runs the real-model fixture separately from the fast offline tests; it may download the model.

The application uses system fonts and local vector icons; its rendered UI has no third-party asset or font requests. Generated screenshot prototypes guide the design and are documented under `docs/prototypes`; actual app captures live under `output/playwright`.

## LAN server deployment

Gather is deployed at **http://192.168.68.68:48732/** on `nolan@192.168.68.68`. The port was checked against every running/stopped container's configured bindings and an actual host socket bind before first deployment. The listener binds the server's LAN IP. Other services were left running unchanged.

The current production release is **`gather:20261106-android`** in `/home/nolan/dockers/gather/releases/20261106-android-verified`; `/home/nolan/dockers/gather/current` points to it. The active volume is `gather_lan_accounts_20261106_android_verified`. The previous `gather_lan_accounts_20261106_copy` volume remains untouched for rollback, and a full pre-upgrade archive is retained in the release directory. The upgrade preserved existing accounts, password hashes, sessions, lists and sharing relationships. See [Android/shared API release verification](docs/ANDROID-RELEASE-20261106.md) and [accounts recovery](docs/ACCOUNTS-SHARING-RELEASE-20261105.md).

Local and LAN installations are independent. The production site is https://gather.takeya.ninja/ with HTTPS and an HTTP-to-HTTPS redirect. Use HTTPS for accounts; the LAN HTTP address remains available for health checks.
PowerShell operations:

```powershell
ssh nolan@192.168.68.68 'cd /home/nolan/dockers/gather/current && docker compose -f compose.lan.yaml ps'
ssh nolan@192.168.68.68 'cd /home/nolan/dockers/gather/current && docker compose -f compose.lan.yaml logs --tail 50'
ssh nolan@192.168.68.68 'cd /home/nolan/dockers/gather/current && docker compose -f compose.lan.yaml restart'
```

Use Settings export for portable owned-list snapshots and full-volume infrastructure backups for accounts and sharing. `compose.lan.yaml` uses a prebuilt image and an external volume selected by `GATHER_DATA_VOLUME` (default `gather_lan_data`). `scripts/deploy-accounts.py` implements the accounts transition: verified image, stopped-volume backup, new cloned volume, only-Gather recreation, health checks and unrelated-container checks. It retains the prior release and volume for rollback. The earlier `remote-deploy.py` is a historical singleton-workspace helper and must not be used against the multi-account schema. Never import an initial prototype snapshot over existing account data.
