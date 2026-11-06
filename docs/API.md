# Gather API

All workspace and search routes require a valid browser session cookie or native bearer session and return 401 when signed out. Health, capabilities and account entry routes are public. Session responses and private API responses use `Cache-Control: no-store`.

## Account endpoints

- `GET /api/auth/session`: `{ authenticated, userId?, username?, setupRequired: false }`.
- `POST /api/auth/register`: `{ username, password }`, creates a unique account and signs it in. Usernames use case-insensitive normalized uniqueness. Each new account has an empty library.
- `POST /api/auth/setup`: retired; returns 410. The original owner is migrated automatically, never claimed by public registration.
- `POST /api/auth/login`: `{ username, password }`.
- `POST /api/auth/logout`: `{}`, revokes the current session and clears its cookie.
- `POST /api/auth/password`: `{ currentPassword, newPassword }`, requires authentication, changes the password, revokes other sessions, and replaces the current token.

Successful browser account mutations return session state. Browser tokens are opaque 256-bit random values with a 90-day lifetime; `/api/auth/*` never returns tokens in JSON. Authentication uses bounded password hashing and attempt throttling. Writes require same-origin JSON requests. Account data is not included in workspace imports or exports.

## Native Android authentication and shared API

The web and native apps use the same accounts, workspace commands, permissions, search, invitations, import and export endpoints documented below. There is no device-specific workspace or synchronization copy. Refresh `GET /api/workspace` when returning to the foreground and after a successful mutation; mutation responses already contain the authoritative workspace. Serialize mutations and do not automatically retry ambiguous create/import requests after connection loss; fetch the workspace to check the result first.

Native clients use HTTPS in production and these explicit endpoints:

| Endpoint | Input / authentication | Response |
| --- | --- | --- |
| `POST /api/native/auth/register` | `{ username, password }` | Native session plus a new bearer token |
| `POST /api/native/auth/login` | `{ username, password }` | Native session plus a new bearer token |
| `GET /api/native/auth/session` | Bearer header | `{ authenticated, userId?, username?, setupRequired: false }`, without token; signed-out state is HTTP 200 |
| `POST /api/native/auth/logout` | `{}`, bearer header | `{ authenticated: false, setupRequired: false }`; revokes this token, idempotent when already expired |
| `POST /api/native/auth/password` | `{ currentPassword, newPassword }`, bearer header | New native session and token; revokes **all** this account's existing native and browser sessions |

The successful register/login/password response is:

```json
{
  "authenticated": true,
  "userId": "uuid",
  "username": "Alice",
  "setupRequired": false,
  "accessToken": "43-character-base64url-value",
  "tokenType": "Bearer",
  "expiresAt": "2027-02-04T12:00:00.000Z"
}
```

Send `Authorization: Bearer ACCESS_TOKEN` on the shared `/api/*` routes. Native authentication never sends `Set-Cookie`; browser endpoints remain cookie-only. Tokens are random 256-bit secrets, persisted only as SHA-256 hashes, expire 90 days after issue, and do not refresh automatically. Cookie and bearer sessions are transport-bound: a cookie token cannot authenticate as a bearer, and a native token cannot authenticate as a cookie. An invalid/malformed Authorization header on shared routes returns 401 even if a valid cookie is also supplied. `/api/native/auth/*` ignores browser cookies, while `/api/auth/*` ignores bearer tokens. Password changes and operator recovery revoke both transports for that account; other accounts remain signed in.

Store the native token using Android Keystore-backed encrypted application-private storage, exclude it from backups/logs/URLs, and clear it on logout or 401. Replace it atomically after password rotation. On a failed logout request, retain enough session state to retry revocation; deleting a local token alone does not revoke the server session. On authentication failures show the server's safe `{error}` message. Username policy, password policy (6–128 characters and any two of uppercase/lowercase/numbers/symbols), 8 KiB auth body limit, bounded hashing, 429 throttling and temporary 503 contention are shared with browser authentication; switching transports does not bypass attempt limits.

Native clients send JSON for POSTs and omit browser `Origin`, `Sec-Fetch-Site` and cookies. Native routes and bearer-authenticated reads enforce the same origin validation as writes: supplied cross-site origins/Fetch Metadata are rejected, and no cross-origin CORS access is enabled. Tokens are accepted only in Authorization headers, never query parameters or request bodies. TLS termination remains the responsibility of the deployment proxy.

`GET /api/capabilities` is a public, non-cacheable compatibility probe returning `{ "apiVersion": 1, "workspaceVersion": 2, "authentication": ["cookie", "bearer"], "features": ["sharing", "search", "import", "export"] }`. `apiVersion` describes this HTTP contract independently of backup `version`. Native clients should verify bearer support before presenting sign-in against a configured server.

All URLs are same-origin. Errors have `{ "error": "Human-readable message" }` and a non-2xx status. Writes require JSON, are limited to 5 MiB, and reject cross-site origins. Auth, invitation and sharing bodies are limited to 8 KiB. Vite's development proxy must preserve the incoming Host (`changeOrigin: false`). A successful list mutation returns the account's authoritative workspace. Clients send granular commands and serialize pending actions. Identity and permissions come from the session, never from submitted owner fields.

## Sharing endpoints

Every endpoint below requires a session. List IDs are not authorization. Only owners can create/revoke invitations or remove members; owners cannot leave or remove themselves.

| Endpoint | Input | Response |
| --- | --- | --- |
| `GET /api/sharing?listId=...` | Accessible list ID | `{ owner, ownerUsername, role, members: [{ userId, username }], inviteActive, inviteExpiresAt }` |
| `POST /api/sharing/invite` | `{ listId }` | Sharing state plus one-time `token`; replaces any pending invite |
| `POST /api/sharing/revoke` | `{ listId }` | Sharing state; accepted people keep access |
| `POST /api/sharing/remove` | `{ listId, userId }` | Sharing state after removing editor |
| `POST /api/sharing/leave` | `{ listId }` | Actor's workspace without the joined list |
| `POST /api/invitations/preview` | `{ token }` | `{ listId, listTitle, ownerUsername, expiresAt, alreadyMember }`; no items |
| `POST /api/invitations/join` | `{ token }` | Workspace plus `joinedListId` |

Invite links use `/#invite=TOKEN`; the client removes the fragment and retains it only for the current tab's sign-in flow. Preview and join send the token in a JSON body so it does not enter URL access logs. Only token hashes persist. Links expire after seven days and are consumed atomically by one new member. A successful recipient retry is idempotent while membership remains valid. An existing member/owner does not consume a pending invitation. Removed or departed members cannot use a previously consumed token to regain access.

Workspace list records additionally include `role` (`owner` or `editor`), `ownerUsername` and `shared`. These are response metadata and are never trusted from imports. Editors may edit content, reset checkmarks and create private copies. Only owners may delete a canonical list or change kind, cadence or template status. Favorites, categories, ordering and archive state are personal, including for the owner. Item and section IDs must belong to the authorized list; category IDs must belong to the actor.

`GET /api/workspace` returns owned and joined lists. `GET /api/export` returns only owned list snapshots and personal categories, omitting sharing metadata, members and secrets. Their common document shape is:

```json
{
  "version": 2,
  "categories": [{"id":"uuid","name":"Everyday","color":"sage","position":0}],
  "lists": [{
    "id":"uuid", "title":"Groceries", "description":"", "icon":"basket", "color":"sage",
    "categoryId":null, "favorite":false, "archived":false, "isTemplate":false, "isSample":false,
    "kind":"checklist", "repeatCadence":"manual",
    "createdAt":"2026-09-29T00:00:00.000Z", "updatedAt":"2026-09-29T00:00:00.000Z", "position":0,
    "sections":[{"id":"uuid","title":"Produce","position":0}],
    "items":[{"id":"uuid","text":"Apples","quantity":"3","note":"","checked":false,"sectionId":null,"position":0}]
  }]
}
```

IDs are globally unique. `categoryId` and `sectionId` may be null. Items belong to the enclosing list; section IDs must reference that same list. Render categories/lists/sections by `position` and items by `position` within their section (null is the unsectioned group). Colors: `sage`, `peach`, `lavender`, `sky`, `sand`, `rose`. Icons are short names mapped by the UI. Import remaps all IDs and references and appends private copies. New entries append after existing content. Relevant reorder/delete operations may normalize positions while preserving relative order.

`POST /api/actions` accepts `{ "type": "item.create", "payload": {...} }`:

Successful `list.create` and `list.clone` responses include an additional ephemeral `createdListId` alongside the full workspace fields. This is the exact list ID created by that command, so clients can select their own new list even when another client concurrently creates a list. It is response metadata only: GET workspace, export, and stored backup documents never include it.

| Type | Payload |
| --- | --- |
| category.create | name, color? |
| category.update | categoryId, name?, color? |
| category.delete | categoryId; lists become uncategorized |
| list.create | title, description?, icon?, color?, categoryId?, favorite?, archived?, isTemplate?, kind?, repeatCadence? |
| list.update | listId and any editable property from list.create |
| list.delete | listId |
| list.clone | listId, title? |
| list.reset | listId; resets completion only; active checklist working lists only |
| section.create | listId, title |
| section.update | listId, sectionId, title |
| section.delete | listId, sectionId; items append to unsectioned group |
| section.move | listId, sectionId, index (zero-based within sections) |
| item.create | listId, text, quantity?, note?, checked?, sectionId? |
| item.update | listId, itemId, text?, quantity?, note?, checked?, sectionId? |
| item.delete | listId, itemId |
| item.move | listId, itemId, sectionId, index (zero-based insertion index within destination group) |
| samples.remove | empty object |

New lists default to icon `list`, color `sage`, no category, kind `checklist`, repeatCadence `manual`, and all flags false. `isSample` is server-owned. Kind is `checklist` or `notes`; cadence is `manual`, `daily`, or `weekly`. Cadence is metadata only, with no automatic resets. Kind changes preserve every item, including historical checked flags, and cadence. Notes reject all item.update checked properties and checked:true on item.create (omitted/false is allowed); ordinary content edits and moves remain available. Reset rejects notes, template masters, and archived lists. Imports may retain checked history on notes. Clone generates independent child IDs, inherits kind/cadence and content/presentation, and resets completion, favorite, archive, template, and sample flags. Default clone title is `Title (copy)` for ordinary lists or `Title — Sep 29, 2026` for templates (server local date). The client may provide its local dated title.

`POST /api/import` accepts a full version-1 or version-2 document and atomically adds private copies after complete validation. All list, item, section and category IDs are remapped; existing lists and memberships remain unchanged. Completion history is preserved, and no sharing permissions are imported. Version 1 always migrates each list to checklist/manual, preserving all existing content and metadata. Version 2 requires valid kind/cadence on every list; missing fields reject. Unknown properties are discarded. Limits: 100 categories, 1000 lists, 100 sections/list, 5000 items/list, 20000 total items; title/section title 200 characters, category name 80, item text 500, quantity 100, notes/descriptions 4000, icon 40. Required text rejects whitespace-only input. Malformed JSON, duplicate IDs, invalid parent references, invalid flags, positions, colors, dates, kind/cadence, and limit breaches reject the whole import. Export is always version 2 and includes all samples, archived lists, templates, historical completion, timestamps, and metadata. Confirm restore/destructive actions in the UI before sending.

`GET /api/health` returns `{ "status": "ok" }` after checking database readability.

The complete persisted/exported JSON workspace is also limited to 5 MiB in UTF-8 bytes so every exported document remains importable. Growing actions that exceed this aggregate limit reject with status 413 and roll back entirely. The error directs the user to remove content or shorten notes; reducing actions remain available. Import validates the same byte limit, including direct store use.

The Node server entry is `server/index.js`. `HOST` defaults to `127.0.0.1`, `PORT` to `3000`, `DATA_DIR` to `./data`. The built frontend is served from `./dist` with SPA navigation fallback. SQLite stores the workspace document in a durable WAL database, with granular server commands applied inside `BEGIN IMMEDIATE` transactions. Existing v1 databases migrate atomically at startup without changing IDs, text, checks, positions, timestamps, or sample removals. Only new databases seed seven samples, including Birthday gift ideas, Christmas plans, and Project ideas as notes. Empty or sample-free documents remain authoritative after restart. Persist the whole data directory (database and WAL files) in Docker.

`POST /api/search` is a read-only retrieval operation with the same origin/JSON/body checks as other POST endpoints. Payload: `{ "query": "USB-C cable", "scope": "library", "categoryId": null, "limit": 60 }`. Query is text up to 500 characters; scope is `library`, `favorites`, `templates`, `archive`, or explicit `all`; categoryId is null/omitted or an existing category ID; limit is an integer 1–125 (default 60). Results are scoped before ranking. Library/favorites exclude templates and archived lists. Search never changes workspace data.

The response is `{ "results": [{ "listId": "uuid", "score": 1, "match": { "text": "actual stored content", "field": "note", "itemId": "optional uuid", "sectionId": "optional uuid" }, "matchType": "exact" }], "provider": "fuzzy", "status": "ready" }`. Match fields are title/description/category/section/item/note/quantity; match type is exact/fuzzy/semantic. Provider is local/fuzzy/openai; status is ready/warming/degraded, with an optional safe reason. Evidence comes from existing workspace content. Frontends must disregard stale queries and use result order without an additional substring filter. Model/provider failures retain lexical/fuzzy retrieval.

`GET /api/search/status` exposes only safe capability metadata `{ "provider": "local", "model": "configured model", "state": "warming" }` with optional safe reason. No credentials are included. Search uses server-only derived data and never adds index/model data to exports. Health and workspace requests do not wait for semantic model loading. `SEARCH_PROVIDER=fuzzy` disables model loading and all provider network calls; local is the default, with lazy warmup on the first nonempty query.
