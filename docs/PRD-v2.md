# Gather v2 — product requirements

## Product intent

Gather remains a private, durable home for household lists. V2 supports lists people collect without completing, makes repeated checklists quick to restart, adds comfortable native dark mode, and improves retrieval when people remember an idea rather than its exact wording.

This document extends `PRD.md`; its existing persistence, responsive accessibility, granular mutations, backups, sample removal, and template independence requirements still apply. V2 does not add accounts, collaboration, reminders, automatic schedules, or a conversational assistant.

## Agreed product decisions

- Lists have a persisted `kind`: `checklist` or `notes`. New lists default to checklist; the create/edit form offers the clear choices “Checklist” and “Notes & ideas,” with a short explanation.
- Lists have persisted `repeatCadence`: `manual`, `daily`, or `weekly`. The setting describes a checklist's intended reuse; the user explicitly starts or restarts each cycle. No calendar, timer, notification, or automatic reset runs.
- Template masters and working copies retain their separate identities. Copies inherit kind and cadence and receive independent list, item, and section IDs.
- Switching kind preserves item completion flags and cadence. Notes hide both, allowing conversion back without losing history. This is an ordinary editable property, not a destructive migration.
- Theme preference is local to the browser, with System as the default and explicit Light and Dark choices. Restore/import does not change it.
- Search combines strong lexical/fuzzy retrieval with optional semantic retrieval. It returns existing lists with evidence from their content, never invented items or AI prose answers. Local semantic processing is the default; hosted processing is explicit operator configuration.

## Notes and ideas

A notes list displays a simple bullet for each item, with text, quantity, optional note, section organization, item details, and reorder controls retained. All items remain visible in their sections regardless of historical checked state. Notes have no checkbox, strike-through, Done group, Remaining filter, completion count, progress bar, or reset action. Cards and headers report item count, including a useful empty state.

The list kind is visible in list details and understandable on library cards without adding heavy chrome. Kind changes apply immediately after a successful save, preserve the same list and child IDs, and retain drafts and all content. The API rejects completion edits and reset commands for notes; validated imports may preserve historical checked flags for backup fidelity and future conversion.

Notes may be templates. Starting one creates an independent active notes list using the existing editable name flow. Cloning continues to reset checked flags, including hidden flags, as the established fresh-copy behavior.

## Repeated checklists and templates

Checklist list details offer “Repeat” with Manual, Daily, and Weekly. Help text makes the behavior explicit: “Start again whenever you’re ready. Nothing resets automatically.” Cadence is visible where it helps choose a template or recognize a repeated active checklist, and remains hidden for notes.

An active, unarchived checklist with at least one checked item exposes a visible “Start again” action beside its header or compact toolbar on both layouts. It must not require finding a nested menu. The action opens one concise confirmation stating how many checked items will become unchecked and that items, notes, quantities, sections, and order remain. Confirm resets completion in place and keeps the current list open; Cancel changes nothing. This is two clicks from the editor and works for partially or fully completed lists. It is unavailable when there is nothing to reset, on notes, in Archive, or on a template master.

Template masters expose “Start list” as their primary reuse action and item counts rather than work progress. A checklist copy begins unchecked; a notes copy remains a bullet list. Copies inherit kind, cadence, content, sections, quantities, notes, presentation, and ordering. Working-copy completion and editing never change the master. Existing duplication and “Make a template” flows remain available and preserve kind/cadence appropriately.

Daily and weekly describe intended frequency only. There are no due dates, missed-cycle penalties, cycle history, automatic copies, or background reset jobs in v2.

## Native dark mode

Settings & backup exposes System, Light, and Dark in one accessible theme control on desktop and phone. Save the preference in browser storage and honor it on reload. System responds to the operating system preference, including changes while Gather is open. Initialize the theme before rendering to avoid a bright flash when opening dark mode.

Use a coherent dark palette through semantic color tokens. Cover the canvas, cards, editor, menus, dialogs, forms, buttons, category washes, progress, checked rows, focus, and error/save states. Set the correct native `color-scheme` so browser form controls and scrollbars fit the theme. Do not simulate dark mode by inverting the page. Preserve the existing warm notebook identity and check readable contrast in each theme, including muted text, completed items, notes, and dialog controls.

## Search and retrieval

The library search includes list titles/descriptions, item text/notes/quantities, section names, and category names. Results remain lists. “Current collection” is the default search scope and applies the selected destination and category before retrieving or ranking; Archive remains separate, and template masters do not appear in Library or Favorites. An explicit “Entire workspace” scope may include active lists, templates, and archived lists, while still applying the category filter. Results clearly label template and archived status. Search works for notes and checklists equally, including completed checklist content.

Literal title and item matches have priority over loose fuzzy or semantic similarity. Typographical errors, partial remembered phrases, and reordered words should find relevant lists. Semantic retrieval adds concept matches when configured, with a threshold so unrelated lists are not returned merely to fill space. Empty search restores normal position ordering. Queries retain the current scope and can be cleared without losing navigation context.

Cards show a short evidence excerpt when a matching item, note, section, or description explains the result. An excerpt is actual workspace text; a semantic result may be identified as related content without falsely marking a literal match. Search does not create, check, or rewrite anything. Opening and returning from a result preserves the search and filters.

Lexical/fuzzy results remain usable immediately while semantic processing is pending. Provider unavailability, model loading, timeout, or invalid configuration must not break search or workspace operations. Show a restrained, truthful status when smart search is unavailable and fall back to lexical retrieval. Cancel or disregard stale requests so a slow earlier query cannot replace the current results. Bound query size, provider work, response time, and returned candidates; normal household use must not cause an unbounded request per keystroke.

Semantic search uses a small, suitable local open-source model by default when the validated implementation provides useful quality and fits the self-hosted deployment. A hosted alternative is optional and controlled by server environment configuration for provider, model, endpoint, credentials, and relevant reasoning setting. Use an explicitly available model rather than guessing an API identifier; GPT-6 Luna at low/medium reasoning is the preferred hosted option when the configured endpoint supports it. Secrets stay on the server, outside source and browser bundles. The UI identifies local versus hosted processing accurately. Hosted mode may send query and list content required for retrieval to the configured provider, and setup guidance states that behavior. No provider call occurs in disabled mode.

Search documents and derived indexes must track content edits, completion-independent content, category/section edits, deletes, archive scope, import, and restore. The search contract recognizes `library`, `favorites`, `templates`, `archive`, and explicitly selected `all` scopes. A cached index must never resurrect deleted content or return a list outside the active scope. Derived search data is rebuildable and must not enlarge or alter the portable workspace backup.

## Migration, samples, and portability

The workspace schema/export version becomes 2. Every list contains validated kind and cadence values. Existing version-1 records migrate to checklist/manual, preserving all original IDs, items, checked flags, timestamps, sections, positions, category membership, favorites, template/sample flags, and archive state. Do not infer kind or cadence from a name/category, recreate the workspace, or reseed it during migration.

The running Docker workspace currently demonstrates seven sample lists. Upgrade preserves all seven and any user-created lists. Only a genuinely new database may seed idea/gift examples as notes; retain the seven-list sample footprint. Previously removed sample content remains removed.

Export includes kind/cadence and historical checked flags. Restore accepts valid v1 backups using migration defaults and v2 backups with their exact metadata. Complete validation and replacement remain atomic; unsupported versions, invalid kind/cadence, broken references, invalid content, or limit breaches leave the current workspace unchanged. Preserve ordering and checked state exactly during export/import, including notes. Keep current backup limits and granular concurrent mutation protection.

Before changing the current runtime data, save a portable backup and preserve the durable volume. Rebuilding or restarting the app must not change household content as a side effect of installation.

## Acceptance criteria

1. A user creates a notes list, adds/edit/reorders/moves items and notes on desktop and phone, and sees bullets and counts with no completion affordances. A historically checked item remains visible after converting to notes. Converting back restores its checked state and cadence without changed IDs or lost content.
2. The server rejects `checked` edits and reset commands on notes, while unrelated valid item edits work. Notes backups roundtrip historical flags. Checklist checking and existing remaining/done flows continue working.
3. A daily or weekly checklist template starts an independent unchecked active copy with inherited kind/cadence and unchanged master. A notes template starts a bullet copy. Ordinary duplication preserves metadata and keeps its established fresh-completion behavior.
4. A partly completed checklist can be restarted in two clicks from its editor. Cancel preserves progress; Confirm unchecks only completion while preserving text, notes, quantity, IDs, sections, order, title, and metadata. Notes, template masters, archived lists, and unchecked lists do not present the active Start again action.
5. A daily/weekly label never triggers an automatic reset/copy, including after waiting, reload, or server restart. UI help explains manual initiation.
6. Light, Dark, and System persist correctly across reloads. System follows a live OS preference change; opening saved Dark avoids a bright flash. Desktop/360px phone screens, dialogs, native forms, focus, errors, checked rows, and notes are readable in both themes.
7. Search fixtures include typo queries such as `grocreis` finding Weekly groceries; an item/note query such as `USB-C cable` finding Kyoto packing list; and semantic queries such as `present for mom` finding Birthday gift ideas and `weekend away essentials` finding Cabin weekend. Local/hosted semantic validation reports actual expected results rather than assuming similarity quality. Exact queries for present titles/items rank their direct list above looser concept results.
8. Search finds text in notes, section names, categories, quantities, and completed items. Category/scope filters remain binding for literal, fuzzy, and semantic results; archive-only content never appears in default Library search. Entire workspace includes archived lists/templates only after explicit scope selection and labels their status. Evidence excerpts come from stored content and do not invent matches. Empty/unrelated queries have sensible ordering or no results.
9. Lexical/fuzzy search remains responsive with semantic search disabled, loading, timed out, or misconfigured. Switching queries quickly cannot display stale results. The configured provider mode and fallback status are truthful; disabled/local mode never transmits household content to a hosted provider.
10. Editing/deleting content and importing a backup refresh search results without stale/deleted content. Hosted credentials are absent from browser assets, source control, exports, and user-visible errors. Operator docs give exact supported configuration and startup behavior.
11. A copy of the existing v1 workspace upgrades without changed original content or lost/reseeded lists. Export v2/restored v2 preserves every field and v1 restore uses checklist/manual defaults. Invalid v2 restore leaves the workspace unchanged. Docker restart preserves the upgraded workspace and health check.
12. Required checks include TypeScript/build/server tests, browser flows for notes/reuse/search/theme/backup on desktop and phone, and actual screenshots from the running app in Light and Dark. Report local semantic and hosted-provider verification separately, with any unavailable configuration stated accurately.

## Delivery boundaries

The deployment target is a miniature PC shared with higher-priority media services and other websites. Keep normal startup light, load AI lazily, serialize single-thread CPU inference, cap cached vectors and work per query, and unload the idle model. Default Docker limits are 0.5 CPU and 512 MiB; verify actual Linux inference inside those limits and provide a fuzzy-only option. No extra vector database or always-running model service is required.

V2 adds retrieval assistance, not AI suggestions or authored content. It includes no scheduler, notifications, chat, cycle history, or data-sharing feature. Final delivery updates behavior/API/setup documentation, provides a working local runtime, preserves the current durable data, and reports the checks and screenshots actually completed.
