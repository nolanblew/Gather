# Gather: simple organization — product requirements

Final Phase 1 design, 2026-11-16. Implemented for web and Android; local verification is recorded in [implementation checks](ORGANIZATION-IMPLEMENTATION-VERIFICATION.md). See the [production release record](ORGANIZATION-RELEASE-20261116.md).

## Purpose

Make ordinary lists more useful without making ordinary capture harder. People can add descriptions, alternative places and a person an item is for, then filter a list using only the details it actually contains. Optional numbering supports priorities and sequences. Japan shopping is an example, not a special list type.

Authoritative companions: [technical design](TRIP-ORGANIZATION-TDR.md), [copy](ORGANIZATION-COPY.md), [progressive disclosure](ORGANIZATION-DISCLOSURE.md). Approved mock states and scores: [simple editor and filters](ux/organization/REVIEW.md). Older options-panel mocks are superseded.

## Capture and editing

- New list asks for its name. Default checklist; More options reveals existing description, notes/template and appearance choices. No list-purpose or feature-enable questionnaire.
- Quick capture remains item text plus Add. Adding details is optional and deliberate, never inferred from title, text or another list.
- Item editor shows Item and populated Description, or Add description if empty. Add detail reveals only applicable choices: Description, Place, For someone, Quantity, Section. Already populated fields are edited directly and are not duplicated in the picker.
- Do not show empty fields just because another item or list uses them. List-local usage may promote relevant choices inside Add detail, never expand them automatically. Ninety-eight ordinary lists remain unchanged when two lists use places.
- Descriptions are first-class prose: list description describes the whole list; item Description preserves existing note content. Both accept multiple paragraphs, with existing length limits. Description alone and any combination of optional fields are supported.
- Populated list description appears below its title with expansion. Item description preview and metadata may occupy separate quiet secondary lines. Empty lines/placeholders are omitted; full content remains available in details. Hide details changes only the local display.
- Save commits the complete draft. Cancel discards changes and any newly drafted names/places. Picker Done returns to the unsaved draft; it never independently saves the item.

## Places and people

Places are list-local named contexts: cities, stores, Home, Work, online or other names. No address or permission required. Several selected places mean alternatives for ONE item, not work required at every location. Separate obligations use separate rows.

Three distinct states: selected places; Anywhere (intentional place-independent); No places set (unknown/unassigned). Anywhere matches every named place filter; unassigned does not. Place hierarchy/automatic city-store containment is not implemented. Rename preserves references; deletion clears only its assignments, leaving an item unassigned when its last named place is removed.

For someone means who the item is for, not the collaborator responsible for doing it. One recipient label per item; any number of items per person. A named group has no member expansion. Assigning never shares/invites/notifies. Shared-list members can see every detail. Use private lists for surprises. Use independent rows for independent purchases; duplicate-for-someone clears completion and recipient in a new draft.

Names can be entered inline or reused from this list. New definitions and assignments save atomically. Definitions are not a global address book. Renaming a recipient retains references; deletion leaves items unassigned without deleting their prose or completion.

## Focused filters

Filter this list opens a sheet with only relevant current-list dimensions. Places-only lists show Places alone. For someone appears only if used. All items / Not done appears for a checklist with checked history; never for notes. Unused definitions alone do not create filters.

One selected place filter at a time; named-place membership on an item is OR. Combine place, person, text and completion dimensions using AND. Return each item once. Show projected result count, retain full-list progress separately, expose No places set count, and distinguish empty results from an empty list.

Apply commits personal filter choices; Cancel leaves them unchanged. A compact active summary plus Clear remains visible, including zero results. Active filters survive polling/focus refresh but not necessarily application restart. Deleting an active dictionary entry clears that dimension with an explanation. Removing its last assignment alone preserves the empty active view until cleared.

No appearance, numbering, list-kind, sharing or feature-enable controls in the filter sheet. Keep existing search available. New item defaults from explicit active filters must be visible and changeable; All never implies Anywhere. Existing item assignments never change merely because of filtering.

## Numbers and order

Number items is a separate owner-authorized shared setting, independent of checklist/notes. Continuous ordinals follow unsectioned items, then ordered sections, then each section's item order. Ties preserve source order. Ordinals are derived, not permanent IDs.

Numbered checklists keep checked rows inline. Filtering/checking retains original numbers; adding/deleting/moving items or sections recalculates them. Notes show numbers without completion controls. Accessible move actions operate on the full order; filtered views offer Edit order to clear filters and retain the selected item. Moves remain section-local, with explicit cross-section movement.

Unnumbered lists retain existing ordering/completion behavior. No dependencies, automatic priority ranking or per-section numbering modes are added.

## Compatibility and permissions

Web and native Android deliver equivalent behavior. Owners control list kind/template/numbering; editors manage content, dictionaries, assignments and shared order. Personal display/filter preferences never affect collaborators. Data must survive scalar edits from old clients, clone, template start, export/import and upgrade. Old clients must fail safely on unsupported numbered structural writes.

Network is required for changes. Current in-memory content and local filters stay readable offline; no promise of restart-offline or synchronization queue. Do not deploy automatically as part of implementation.

## Acceptance gates

1. Plain list creation and capture add no mandatory fields/taps; two place-enabled lists do not change 98 unrelated lists.
2. Description-only and mixed-detail items preserve multiline text through picker navigation, Save/Cancel, copy and backup.
3. Tokyo-or-Kyoto item appears once in either view; one checkmark updates it everywhere. Anywhere included; unknown remains discoverable separately.
4. Recipient filters intersect place filters; self labels stay stable for all collaborators. No access is granted.
5. First-use names/places save atomically; canceled/rejected drafts create no orphan definitions.
6. Places-only filter has no recipient, formatting or unnecessary completion controls. Apply/Cancel and Clear behave as labeled.
7. Numbered recipe and notes preserve ordinals under filtering/checks; checked steps stay inline. Cross-section moves and empty/tied sections work consistently across clients.
8. Competing numbered structural changes reject stale operations and refresh without automatic replay; metadata conflicts preserve drafts.
9. v1/v2 upgrades, v3 backups, clone/import remapping, permissions and near-limit workspaces preserve data.
10. Desktop, narrow web, Android and adaptive layouts keep descriptions legible and actions accessible without drag-only/hover-only gestures. Actual builds/tests and runtime checks are recorded, not inferred from mock ratings.

## Deferred Phase 2

Foreground location suggestions and assisted capture remain separate future work. No background geofencing, automatic list classification, AI metadata assignment, new hosted-data transmission, arbitrary field builder, task-assignee workflow or offline editing in Phase 1. Existing vetted concepts can be revisited after the simple manual flow is validated.
