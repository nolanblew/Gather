# Gather verification inventory

## Claims and evidence

| Claim / control | Functional verification | Visual state / evidence |
| --- | --- | --- |
| Library, favorites, categories, archive, templates | Switch each destination and filters; verify collection context | Desktop library and phone library |
| Fast capture and checklist progress | Create a list; add, check, uncheck; blank-entry handling | Populated desktop and phone editor |
| Item details and organization | Edit text, quantity, note; add/rename section; reorder and move | Item detail dialog and dense sectioned checklist |
| List management | Rename, change presentation, favorite; duplicate; archive/cancel/restore; delete/cancel | List dialog and contextual menu |
| Reusable trip checklists | Start template copy; edit/check copy; inspect unchanged master | Template destination and working-copy editor |
| Search | Match list title and item text; clear search; no results | Filtered and empty collection |
| Data durability | Reload; second session; restart server/container | Saved changes visible after refresh |
| Concurrent capture | Two clients add unrelated items; retain both | Updated item rows |
| Backup and recovery | Export/import round trip; reject malformed import atomically | Backup settings and restore confirmation |
| Sample removal | Mark samples; remove explicitly; restart; no reseed | Sample notice and resulting empty library |
| Responsive and keyboard use | 1440px, 390px, 360px; focus/navigation and dialog Escape | No clipping or horizontal overflow; quick-add reachable |
| Docker delivery | Build/start; health; persisted volume after restart | HTTP health response and browser rendering |

## Exploratory cases

- Long list names, long item text/notes, empty titles, whitespace entries, and empty search results.
- Cancel destructive actions; malformed backup; stale IDs; save/network failure keeps draft and communicates failure.
- Switch filters while a selected item/list changes; keyboard open/close dialogs; mobile menu and scrolled long list.

## Signoff

Completed September 29, 2026 (America/Los_Angeles). The final Docker app is running at http://localhost:3000, bound to loopback, with persistent volume `listz_gather-data`.

- **TypeScript and production build:** `pnpm check` and `pnpm build` passed cleanly after final UI refinements. Docker built the same frontend successfully.
- **Server integration:** all 8 Node integration tests passed. Coverage includes granular concurrent additions, database restart, independent template children, exact backup values and array ordering, atomic malformed import, parent IDs, request/workspace size bounds, sparse imported ordering, safe section/category removal, seed-once behavior, origin/security headers, and exact created-list response IDs.
- **Desktop browser flows:** searched by contained item text; created and edited a list/presentation/category; added items; edited quantities and notes; created/renamed sections; moved and reordered items; checked/un-checked through Done; used Remaining; duplicated; archived/canceled/restored; favorited/unfavorited; started a dated template copy and verified the unchanged master; canceled and applied reset; tested category create/rename/delete cancellation and application; canceled/applied item and list deletion; searched an empty result; switched destinations and filters.
- **Backup and failures:** downloaded real JSON through Settings, restored through file selection and explicit confirmation, and compared all data values and array ordering. Malformed JSON/schema preflight showed a useful message and left server state unchanged. An aborted save retained the quick-add draft, and a retry saved exactly one item.
- **Two browser clients:** independent contexts added different items to the same list from their own snapshots. Reloading showed both additions intact.
- **Phone browser flows:** checked 390×844 and 360×760, including library, templates, More/backup, list creation, sticky quick add, section creation, item quantity/note, reordering, checking/unchecking, Remaining, long-title wrapping, and deletion. The initial view had no horizontal overflow; navigation and quick add had adjoining, non-overlapping bounds. Scrolling brought the last expanded Done row fully above quick add (last row bottom 532px, quick-add top 588px at 360×760). A 900×700 tablet/laptop viewport used two card columns without overflow.
- **Keyboard and visual checks:** named native dialogs contained Tab focus and Escape returned focus to New list; list menu Escape closed correctly. Reviewed whole library/editor and phone captures for clipping, layering, overflow, readability and layout balance. Final supporting text contrast is 4.79:1 against the ivory canvas; sample badge contrast is 4.88:1. No runtime page errors were recorded. The phone header now has one list-actions menu.
- **Docker acceptance:** primary container reports healthy; a list and item created through the packaged browser app survived container restart with exact workspace data. An isolated container exercised sample-removal cancellation, actual sample removal and restart; samples did not reseed. Temporary acceptance container/volume were removed, and primary test lists were deleted. The delivered workspace has 5 active sample lists and 2 sample templates. Native development test data was archived under `output/qa-native-data` so a future development launch starts cleanly.
- **Independent review:** all nine concrete findings resolved; see REVIEW.md. UX rated the actual desktop library 8.79/10 and phone checklist 8.43/10, both above acceptance.

## Final screenshots

- `output/playwright/desktop-library.png`
- `output/playwright/desktop-checklist.png`
- `output/playwright/mobile-checklist.png` (390px)
- `output/playwright/mobile-360.png`

These are captures of the built application served from Docker, distinct from the generated design prototypes. Prompts and prototype ratings remain under `docs/prototypes` and UX.md.

## Verification limits

Mobile checks used Chromium viewport/touch emulation. Physical phone soft-keyboard positioning, Safari, screen readers, and full assistive-technology coverage were not verified. No production server deployment was performed. This MVP needs a server connection and has no offline synchronization.

## V2 signoff — September 29, 2026

Delivered Docker runtime at **http://localhost:3000** with the same persistent `listz_gather-data` volume. The in-app browser was refreshed and left open in Dark appearance. No remote production deployment occurred.

- **Migration and persistence:** fresh backup `output/backups/gather-before-v2-upgrade-20260929-224209.json` captured all seven version-1 lists immediately before upgrading. Compared the complete version-2 export against that snapshot after removing only new kind/cadence fields and changing the version back to one: every original value and array ordering matched. All migrated records use checklist/manual defaults. Compared complete version-2 documents before/after Docker restart: exact values and array ordering matched. Health and Docker healthcheck are good.
- **Checks:** TypeScript passed; final production frontend built in Docker. All **25** automated tests passed. They cover transactional migration/import, completion-history preservation, notes API guards, metadata inheritance and independent clones, scope and content freshness, typo matching, index/vector budgets, persisted-cache reuse across actual service restart, hosted exact-priority/ID validation/throttling/freshness, and no-key/invalid-provider/idle behavior. Search bodies above 8 KiB are rejected; existing backup limits remain.
- **Native themes:** exercised Light, Dark, System and live emulated device preference changes in both directions. Saved Dark survived reload. Visually inspected dark library, notes editor, checklist, settings and creation dialog. At 360px, page scroll width matched the viewport; creation dialog fit at 322px. Native forms follow color-scheme. Template and archive badges remain readable.
- **Notes and conversion:** notes editor has zero completion checkboxes and displays every item as a bullet. Converted a checklist containing a checked Lemons item and Daily cadence to notes: the item stayed visible and its hidden checked flag/cadence persisted. Converted back: completion and Daily cadence returned. Created a notes template through the 360px UI, added an idea, and started an independent notes copy with no completion controls. Edited quantity and note on that copy through the item dialog.
- **Reuse:** changed an isolated Cabin weekend master to Weekly, started a named copy through mobile, confirmed inherited type/cadence and six unchecked children. Checked an item and used the visible Start again action on mobile. Desktop reset cancellation left the complete workspace unchanged; confirmed reset cleared checks. Template masters show counts and bullets, without work-progress framing.
- **Search browser flows:** typo `grocreis` returned Weekly groceries; `present for mom` returned Birthday gift ideas with actual evidence “A pottery class for Mom.” Meaning results opened the full editor with an empty item filter; Back retained the query. Current collection excluded masters; explicitly selecting Entire workspace admitted Cabin weekend/Camping essentials for `weekend away essentials`. Category filters remained binding. Unrelated query returned no results. Artificially delayed an older Weekly groceries response, searched Kyoto packing list, then released the older response: Kyoto stayed current. Cold deployed semantic query showed a truthful warming/fuzzy fallback and automatically improved to ready local results.
- **Portable restore:** restored an exported v2 workspace through mobile file selection and the explicit replacement dialog in an isolated test workspace. All data values/order matched; Dark appearance remained selected independently. Backend tests additionally verify v1 restore and malformed/missing v2 metadata rollback.
- **Actual model:** ran the permanent integrated MiniLM q8 CPU evaluation on Windows and Linux Docker. Both passed exact 5/5 top one, fuzzy 5/5 top three, paraphrase 9/10 top one and 10/10 top three; all five unrelated queries empty; long-note-tail, section and category checks passed. Linux cached fixture took 1768 ms including initialization, process RSS 179 MiB. This is a small fixture, not a guarantee for all personal vocabularies.
- **Shared-server resource budget:** inspected the actual container: 0.5 CPU, 512 MiB, PID cap 128. Observed service memory around 19 MiB before model use, 104 MiB after a query, 89 MiB after a shortened idle-disposal test. Model libraries/allocators retain some memory after disposal. Cold query returned warming in 1072 ms; cached query ready in 202 ms. Model cache/index remain on disk, ordinary startup/health are independent of AI, and inference is serialized with one thread. Fuzzy-only mode avoids model loading. These observations are not maximum-memory claims or measurements on the eventual physical server.
- **Review:** independent reviewer resolved all actionable findings and independently ran 25 passing tests. See REVIEW.md and SEARCH-RESEARCH.md. Hosted tests use simulated upstream responses; no configured API key was available and no paid API call occurred. No claim is made about live GPT-6 Luna entitlement/quality.

### V2 screenshots

Delivered Docker app: `output/playwright/v2-live-desktop-dark.png`, `v2-live-dark-search.png`, `v2-live-desktop-light.png`.

Same v2 frontend in an isolated test workspace: `v2-desktop-dark-notes.png`, `v2-desktop-dark-settings.png`, `v2-mobile-dark-notes.png`, `v2-mobile-dark-checklist.png`, `v2-mobile-dark-create.png` under `output/playwright`. Test workspace examples demonstrate notes/repeat settings without rewriting the preserved household records.

Mobile verification remains Chromium touch/viewport emulation; physical phone keyboard, Safari and complete assistive-technology coverage remain unverified. Large collections can have partial semantic coverage under the bounded demand-index budget; lexical search still covers scoped content. Hosted mode deliberately uses only 40 candidate lists and short excerpts.

