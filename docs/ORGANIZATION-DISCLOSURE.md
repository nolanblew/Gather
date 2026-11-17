# Gather: progressive disclosure by list

> UI wording: [copy reference](ORGANIZATION-COPY.md), reviewed by GPT-6.1 Terra on high. This is authoritative over older labels in these design notes and generated mock images.

Date: 2026-11-16
Status: Current UX direction. Supersedes the full View-panel mocks options-v1/options-v2 and the always-expanded populated editor as a default form. Those images remain explorations, not accepted default UI.

## Decision

A list only exposes details and filters it actually uses. Feature availability is global; visibility is local to each list and current editing task. Using Places in two of one hundred lists does not add Places anywhere in the other ninety-eight. Never infer list capability visibility from account-wide usage, a title, or a previously edited list.

No enable-feature settings, list-purpose questionnaire, or fixed growing options panel. New capabilities earn an entry in Add detail, not a permanent position in the editor, list header, or View menu.

## Three surfaces

### The list

Plain list: title, list description when present, items, Add item and the existing action menu. A list does not advertise that it supports Places, Recipients or other unused fields.

The list-actions menu offers Number items as a direct formatting action. It offers Filter this list only if useful filters exist: explicit place/recipient assignments or checklist completion history. Existing search stays accessible through its current route. Show/Hide item details appears only when there is actual item detail content. These actions remain discoverable without adding persistent controls to the list canvas. A dedicated View button is optional only if validated as helpful; the full options sheet is not the default design.

Filter this list opens a focused sheet containing only relevant filter dimensions. A place-only list shows Places, not For someone. A checklist may show All/Not done when there are checked items; notes never show this. Local filter changes use Apply/Cancel. Numbering and list kind are NOT in this filter sheet. An active filter remains visible in the existing compact summary with Clear, even when its result is empty. Tapping it returns to the focused filter sheet.

Keep list kind, template settings, appearance and list description in existing List details rather than duplicating them inside filtering. Numbering remains an explicit owner-authorized shared setting; filtering stays personal. This also removes mixed shared/server and personal/client changes from one Apply button.

### The item editor

Start with Item, a populated multiline Description (or a quiet Add description action), and Save/Cancel. Show all details already assigned to THIS item. Empty Quantity, Places, For someone and Section fields do not appear merely because other items have values.

An Add detail action opens a compact picker: Description, Place, For someone, Quantity, Section. Omit or change the action for fields already present so users do not create duplicate fields. The name For someone is a beneficiary label, not sharing or task assignment. Existing values are edited directly in their respective row.

If other items in THIS list use Places, place Add place first in the Add detail picker; do not pre-open an empty Places field. The same applies to recipients. Keep the rest of the picker in a predictable fixed order, do not constantly reorder it based on speculative inference, and do not change an open picker during polling. Do not promote a field based on another list's usage.

Select a field, fill it, and return to the item draft. Place can select several alternatives; recipient is one value; description accepts multiple paragraphs. Save commits the combination atomically. Cancel discards changes and newly drafted definitions. A populated item may legitimately have several details, but the screenshot of that item is not the starting form for every item.

### The Add detail picker

This is the deliberate discovery surface for optional power. Use plain-language labels and one short explanation where meaning is ambiguous, such as For someone → Who this item is for. No setup tour or nagging “try Places” card.

The initial five choices fit a compact picker. If the supported set later exceeds six choices, keep a small stable common group and put less-used details behind More details with search. Search is not required while the picker is small. Do not add disabled future features or a marketplace. New features must specify where they appear, when they stay hidden, how to clear them, and what filters they contribute before being admitted to the UI.

## Exact examples

| List / item | Visible by default | Optional path |
| --- | --- | --- |
| Groceries, text-only items | Plain capture and existing list actions | Item → Add detail → Place if wanted |
| Japan shopping with places only | Place summaries on assigned rows; Places in Filter this list | For someone remains in Add detail |
| Gift list with recipients only | Recipient summaries; recipient filter | No Places control until an explicit assignment exists |
| Item with description only | Title and description preview; editor shows full description | Other detail types remain behind Add detail |
| Item with description + two places + recipient | Those populated details in editor; restrained list previews | No unused fields |
| Other 98 unrelated lists | Unchanged by travel-list usage | Same Add detail entry point if later needed |

## Visibility rules

- Field rendering: derive from the current item's populated values, not a list-level enable flag.
- Filter availability: derive from assignments in the current list, plus an active filter that must remain recoverable. Unused dictionary definitions alone do not reveal a filter.
- Picker recommendations: derive only from explicit usage in the current list. No account-wide promotion or inferred list types.
- Empty description is an optional Add description action. Populated list/item descriptions are preserved and remain readable independent of metadata.
- Clearing a field removes only that field; other values/prose survive. The empty editor row may remain until the editing session ends to avoid focus jumps. Do not remove controls while the user is editing them.
- Removing a dictionary value selected in an active filter resets that dimension with an explanation. Removing its last item assignment alone preserves the visible empty filter until cleared.
- Local Show/Hide item details never changes stored values or what other people see.
- Required validation errors may reveal their relevant field. They are not ordinary discovery prompts.

## Verification

1. Seed 100 lists, add places to two, and verify no Places field, selector or prompt appears on the other 98 outside an explicitly opened Add detail picker.
2. Verify a location-only list never shows a recipient filter and its new item editor does not pre-open either empty field.
3. Save description-only and mixed-detail items; reopen, copy and export/import without losing text or assignments.
4. A new user finds Place from Add detail without coaching; a returning user reaches existing place values directly. Test discoverability rather than compensating with permanent explanatory banners.
5. Verify switching lists/accounts, polling, active-filter empty states and new-feature additions do not leak UI state across lists.
6. Evaluate plain, single-detail and fully populated states separately. A rich-editor screenshot is not proof that basic capture stays simple.

## Next mock scope

Replace the settings-heavy options mock with a plain-list item editor and its small Add detail picker, then a place-only filter sheet. Show the fully populated editor only as an advanced state reached by explicit actions. Reuse the existing independent review thresholds, but assess unwanted controls as a first-class simplicity failure.

