# Requirements review — September 30, 2026

Reviewed the original household-list intent, PRD.md, PRD-v2.md, palette preferences and shared-mini-PC deployment requirements. Three GPT-6 Sol agents reviewed product flows, search, and data behavior; the orchestrator integrated the fixes and exercised desktop and 360px browser flows. No remaining major blocker was found for the agreed household MVP.

## Findings fixed

| Finding | Delivered behavior |
| --- | --- |
| Refreshing a list lost the editor, and browser Back/Forward could not navigate within Gather. | URLs retain list, collection, category, query and scope. Reload/bookmarks reopen the same list. Back/Forward restore navigation context. Query typing replaces the URL without adding a history entry per keystroke. |
| A title-only list created in a category was uncategorized and disappeared on return; creation from Favorites had the same visibility problem. | New lists inherit the active category and Favorites status. Create/clone returns to the appropriate Library, Favorites or Templates collection, clearing creation-time search. Template copies return to Library rather than back into their master. |
| Semantic indexing stopped after ten client retries, leaving later content unindexed. | Visible search results keep requesting bounded indexing batches. Retry work pauses in the editor and hidden tabs. At the cache cap a stable target set prevents eviction/re-embedding churn; scope changes and edits can still replace vectors. Partial coverage is stated in the UI. A delayed query vector cannot prematurely mark capped search ready. |
| Moving a checklist item could move past a hidden completed sibling and produce no visible movement. | Move up/down targets the neighboring remaining/done item within the section. Notes and template masters still order all items. Position labels describe the visible group. |
| LAN Compose omitted two supported search environment overrides. | Local-model and hosted-base-URL settings are passed through consistently with local Compose. |

## Requirement coverage

| Area | Evidence |
| --- | --- |
| Capture, notes/checklists, sections, quantities, editing | Browser create/add, notes conversion, mobile section creation and item-note/section editing; source review and store tests. Notes show bullets without completion controls. |
| Reuse and restart | Browser weekly template creation, unchecked independent copy, completion and Start again confirmation/reset. Store tests cover copy IDs, metadata, historical checks, reset preservation and notes guards. |
| Organization and retrieval | Browser category capture, favorite capture, query/category return context, desktop and mobile history/reload. Existing tests cover strict search scope, archive separation, edits/deletes/import freshness. |
| Theme and phone UI | Midnight picker and editor exercised at 360×800; native dark theme persists across reload; no horizontal document overflow. Existing palette implementation and prior four-palette QA reviewed. |
| Search quality and resources | All search tests passed. Real MiniLM evaluation: exact 5/5 top one, typo 5/5 top three, semantic 9/10 top one and 10/10 top three, five unrelated queries empty. New tests cover >10 indexing passes, capped-cache stability/scope refresh, and delayed query-vector completion. Live LAN “present for mom” returns Birthday gift ideas first with local smart search ready. |
| Data safety and persistence | Existing integration tests cover atomic invalid imports, export/restore, restart, seed once and concurrent additions. Both live local and LAN exports before/after this upgrade were byte-identical; browser mutation testing used isolated output/review-data. |
| Deployment | Local image build, checksum-verified transfer, fresh server-side export before update, persistent volume preserved, healthy LAN container and local endpoint. All unrelated containers retain their IDs/start times/restart counts. |

Final automated gate: **30 tests passed**, TypeScript check passed, Vite and Docker production builds passed. Browser proof: `output/playwright/review-live-search.jpg` and `review-live-mobile.jpg`.

## Deployed release

- Image: `gather:20260930-review`
- LAN: http://192.168.68.68:48732/ ; local: http://localhost:3000/
- Server release: `/home/nolan/dockers/gather/releases/20260930-review`
- Backup: `backup-before-update.json` in that release, permissions 600.
- Transfer SHA-256: `EBE80C6DBD44F6E3C3D9E4576D74EA4D712FA6022944DAFE65CE244E5A451C06`
- Limits confirmed: 0.5 CPU, 512 MiB. Observed after live semantic search: about 103 MiB, healthy, zero restarts.

## Limits of this review

Phone tests use Chromium viewport emulation; physical soft-keyboard and Safari behavior remain unverified. Hosted AI adapter tests are mocked; no paid API key or hosted model entitlement was exercised. Very large workspaces above the configured vector cap have explicitly partial semantic coverage, with full scoped text search. Automatic resets/reminders, offline synchronization, accounts and collaboration remain agreed non-goals. Existing migrated sample lists retain their original checklist kinds until changed by the user; the upgrade does not rewrite household content.
