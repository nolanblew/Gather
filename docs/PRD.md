# Gather — product requirements

## Product intent

Gather is a calm, organized home for the lists people return to throughout their lives. Groceries, project ideas, birthday gifts, Christmas plans, packing, camping, and cabin trips should be easy to find, capture into, and reuse from a phone or desktop. The initial product serves one private household workspace on a self-hosted server. It is a responsive web application, with focused phone interactions and richer desktop navigation.

The primary promise is: **find the right list, add something quickly, and trust that it will still be there next time.**

## Users and core journeys

1. **Capture:** Open Gather, find or favorite a list, and add an idea without completing a form.
2. **Do:** Open a grocery or packing list on a phone, check items, and inspect remaining progress.
3. **Organize:** Create, rename, describe, categorize, color, and favorite lists; arrange items into optional sections.
4. **Reuse:** Maintain a cabin or camping template and start a dated, unchecked working copy for each trip.
5. **Recover:** Restore an archived list and export a portable backup of the household workspace.

## MVP scope

### List library

- Active lists, favorites, templates, and archive are distinct destinations.
- Categories begin with Everyday, Ideas, Gifts, and Trips. A list may be uncategorized. Category choices must be editable; avoid requiring a category to capture a new list.
- Search matches list title, description, and item text. Results are lists with clear context, not a disconnected global task feed. Archive is searched only within the Archive destination.
- Lists show title, icon, category, and meaningful completion progress. Distinguish a template from a working list.
- New lists need only a title. Sections are optional; an empty list has a direct, inviting add-item action.
- Lists support rename, description, icon/color/category editing, favorite toggling, duplicate, archive, restore, and delete.

### List editor

- An inline add field creates an item from its text. Empty or whitespace-only items are rejected without losing a valid draft.
- Items have text, completion state, optional free-text quantity, and optional note. Quantity accommodates values such as `2`, `1 bottle`, and `as needed`; no unit conversion is required.
- Items can be edited, deleted, reordered, and moved between sections. Ordering controls must work on phones and keyboards; drag-and-drop may supplement them.
- Sections can be added and renamed. Blank new lists do not force a section. If section removal is implemented, preserve its items by moving them to the unsectioned group.
- Completed items remain recoverable and can be unchecked. A visually subordinate Done area may collapse completed items, with its count always clear. Completion must not silently delete or permanently hide data.
- Progress counts all items, with an empty-list state rather than a misleading completed percentage.
- A focused item filter is useful for longer lists but is subordinate to the primary quick-add field.
- Notes and quantity detail are available on demand so that a grocery or packing checklist stays fast to scan.

### Templates and reuse

- Templates are separate master lists, edited explicitly in the Templates destination.
- Starting from a template creates a new active list, preserving item text, sections, notes, quantities, order, and presentation while resetting completion flags. The default copy name includes a readable local date and is editable.
- Checking, editing, archiving, or deleting a working copy never changes its template.
- Duplicating an ordinary list also creates an unchecked active copy; its title clearly identifies it as a copy.
- No automatic recurring scheduler is included. The user starts each packing or trip copy when needed.
- If a reset-progress action is included, require confirmation and leave text, notes, quantities, and organization intact.

### Sample content and data portability

- First-run sample lists demonstrate groceries, birthday ideas, and a reusable Cabin weekend template. Mark sample content visibly and provide a clear removal action. Seed only once; deletion must not cause samples to reappear after restart.
- Export JSON includes a versioned workspace document with lists, templates, sections, items, categories, metadata, and completion state.
- Import validates the schema and rejects malformed data without partial writes. Import is an explicit restore operation with a clear confirmation explaining that the current workspace will be replaced; users can export the current workspace first. Preserve exported ordering and completion exactly.
- Export and import must be available from a small Settings or Backup area on both layouts.

## Responsive UX requirements

### Desktop

- A persistent navigation/sidebar exposes library destinations, categories, search, and new-list creation.
- The list collection gives enough context to choose a list quickly. The selected editor is the visual focus; avoid multiple competing dashboards or analytics.
- List management and item details can use contextual menus or compact panels.

### Phone

- Prioritize a library view and a focused single-list view with an obvious return path.
- Keep quick add close to the bottom of the screen without covering the last item or keyboard interactions.
- Use compact category/favorite controls and large check targets. Secondary list management can live in a menu.
- No horizontal page scrolling at 360 CSS pixels. Tap targets are at least 44 CSS pixels where practical, with visible keyboard focus, accessible control names, and adequate contrast.

### Visual direction

- Warm, restrained, and personal: legible typography, soft neutral canvas, thoughtful color accents, generous spacing, and purposeful iconography.
- Density should serve a real grocery list, not only a sparse screenshot. Mobile should preserve the same product identity while reducing navigation chrome.
- UX will create separate desktop and phone screenshot prototypes and score each for visual quality, hierarchy, usability, completeness, responsiveness, and accessibility. Acceptance threshold: average at least 8/10 with no category below 7/10. Regenerate a failed prototype up to three attempts total per screenshot before documenting any unresolved limitation.
- Final UX specifications must describe implementable screens, controls, states, and behavior; prototypes are design references, not proof of working functionality.

## Data, deployment, and reliability

- Server persistence is authoritative. Refreshes and application/container restarts preserve saved data. Use a persistent Docker volume for the database.
- Mutations are granular server commands (for example, add item or check item), not replacement of a client snapshot of the entire workspace. Two clients adding unrelated items must not overwrite each other's changes.
- Show a useful error if saving fails, keep editable drafts, and do not imply a failed change was saved. Pending actions should not result in duplicate items from repeated taps.
- Bind the default local host application to loopback. A production Docker deployment can expose the application through a reverse proxy, but public exposure requires reverse-proxy authentication. The local MVP has no accounts or login.
- Validate input, use safe parameterized database access, avoid interpreting user content as HTML, and limit import/request size. Basic security and performance are appropriate to a small private household application.
- Provide Docker configuration, a health check, and documented development/run/backup instructions. No production deployment is part of this delivery.

## Explicit non-goals

Accounts, invitations, sharing, permissions, realtime multi-person presence, offline synchronization, push notifications, automatic recurring schedules, AI suggestions, shopping prices, gift procurement, nested tasks, due-date calendars, and third-party integrations are outside the MVP.

## Acceptance criteria

1. A user can create a blank list, add several items, edit text/quantity/note, check and uncheck items, reorder them, add/rename sections, and move items between sections on desktop and phone.
2. A user can find a list by title or contained item text; favorites and categories narrow the library; Archive is separate and archived lists can be restored.
3. Archive, delete, and any reset-progress action clearly explain their effect and require confirmation. Cancel leaves data unchanged.
4. A Cabin weekend template starts a dated working copy with unchecked items. Edits and completion in the copy do not alter the master. Ordinary duplication also resets completion.
5. Saved changes survive browser refresh and a server/container restart. A fresh browser session sees the same workspace.
6. Two browser clients can add items to the same list without either item being lost. Granular edits to unrelated records preserve both changes.
7. A workspace export can be restored through validated import with ordering, notes, template status, and completion preserved. Invalid import leaves existing data intact.
8. Samples are marked, removable, and never reseeded after their removal or an application restart.
9. The interface has no horizontal scrolling at 360px, is usable with keyboard navigation, and keeps checklist controls and quick add accessible on a phone.
10. Docker starts the app with durable storage and a working health check. Setup and production-authentication guidance are documented.
11. Final delivery includes actual desktop and phone screenshots from the running app and a working local URL, with verification limits stated accurately.

## Product decisions agreed before UX

The orchestrator and product manager agree that reusable master templates best address the repeated-trip use case; working copies are always independent. The first release is one private household workspace with local-only access by default, server persistence, reversible archiving, and portable backup. Fast capture and retrieval take priority over scheduling or collaboration features.
