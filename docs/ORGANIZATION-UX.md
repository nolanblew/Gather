# Gather: quiet, capable list UX

> UI wording: [copy reference](ORGANIZATION-COPY.md), reviewed by GPT-6.1 Terra on high. This is authoritative over older labels in these design notes and generated mock images.

> Current interaction revision: [progressive disclosure by list](ORGANIZATION-DISCLOSURE.md). It supersedes the full View panel, mixed shared/local Apply flow, and revealing empty item fields merely because a list uses them. Other data and preservation rules remain applicable.

Date: 2026-11-16
Status: Proposed interaction design; supersedes earlier organization-switch, preset-onboarding, and persistent-filter-toolbar proposals in the PRD/TDR. No implementation or usability testing is claimed.

## Product principle

Capture first. Organization grows from details people deliberately add. Users should not have to select a gift, errand, recipe, or trip list type. A list has content and a small number of display choices, not a hidden inferred identity.

Phase 1 must be calm without AI. Phase 2 can reduce effort through suggestions, but must not be required to discover or use any capability.

## 1. Create and capture

New list asks for a title, followed by Create. Default to the existing checklist behavior. Appearance, description, Notes instead of checkboxes, and reusable-template settings live under optional More options or later list settings; none blocks creation. Keep the existing Templates library as an explicit reuse path. Do not add a gallery of list types or starter cards to the default creation flow.

The list opens with its title, ordinary list actions, rows and the existing add-item field. No empty Place/Recipient selectors, unused counters, permission prompts, configuration checklist or suggestion banner. The empty state has one instruction: “Add your first item.” A secondary, non-blocking “You can add details later” hint is sufficient.

Typing “Tea for Maya in Kyoto” initially saves that exact text. Phase 1 does not guess whether Maya is a recipient or whether Kyoto is a location. Ordinary capture remains one text entry and Add; no metadata editor opens automatically.

## 2. Reveal detail at the item

Open an item to edit it. Show its text and existing populated details first. An Add detail action reveals Place, For someone, Quantity, Description, and Section in a compact sheet/menu. Use existing components and accessible labels, not a new nested form system. Selection opens one editor and returns to the same item; preserve its draft.

The first explicit Place or For someone assignment saves normally and makes that dimension available in the list's View control. There is no preceding “enable Places” or “choose Gifts list” step. Owners and editors can add these content details under existing content-edit rights. Adding a recipient never grants access.

Once a dimension is used in this list, its picker is directly available when editing other items; unused dimensions remain under Add detail. Recently used values within the list appear before searchable full choices. Do not copy assignments from the previous item automatically. Explicit active filters may supply visible draft defaults as specified in the PRD.

Creating a dictionary label and assigning it should be one atomic Save from the user's perspective. Canceling a new detail creates no orphan label or item. Existing explicit dictionary management may still create unused labels; that does not trigger a new toolbar.

## 3. One View control, no filter dashboard

Use one stable, labeled View entry in the existing list-actions area. It contains Number items, checkbox/notes display where authorized, All/Not done where applicable, and available Place/For someone filters. Do not add separate persistent Place and Recipient dropdowns to the main canvas. Search uses the existing search affordance or View sheet, without another permanent input row.

The View sheet shows Places only when there are explicit assignments (including Anywhere) or an active place filter; likewise For someone. All other detail creation stays discoverable through item Add detail. If all assignments are removed, retain the open sheet/focus until the user leaves, then omit the empty filter dimension. Disabling/hiding a detail display is a personal presentation choice, not deletion of content.

An inactive view shows no filter summary. An active view shows one compact line, for example “Kyoto · Maya · Not done · 3 shown”, plus Clear. Tapping the line reopens View. Inside the sheet, selected values and result counts are explicit; Apply commits the combined filter selection, Cancel leaves the current view unchanged. Never silently hide results because of a suggestion.

A place selection now takes View → Places → Kyoto → Apply. Replace the earlier two-tap target with discoverability and correctness; do not crowd the canvas merely to save a tap. A visible active summary offers quick return for repeated changes. If testing shows frequent city switching is too slow, evaluate one user-pinned place selector later rather than shipping every selector permanently.

No places set and Not assigned counts live inside the relevant filter picker. In a place-filtered view, the active summary may include a compact “N without a place” action so planning gaps remain reachable. Do not put unassigned badges on every row or treat ordinary unassigned items as errors.

## 4. Row hierarchy and density

Primary line: completion control if appropriate, number if enabled, and item text. Item text keeps normal wrapping and is never truncated to make room for metadata.

Optional secondary line: meaningful populated detail, rendered as quiet text rather than a row of colorful chips. Examples: “For Maya · Tokyo or Kyoto” and “For Alex · Anywhere”. Use one secondary line at standard text size, with an accessible “More details” affordance for overflow. At larger accessibility text sizes allow wrapping; the density target must not defeat readability.

Do not show empty placeholders, repeated field headings, default values, edit icons for every field, drag handles, or move arrows on every resting row. Item details always exposes full metadata. Checkboxes and item editing must have separate targets; tapping metadata opens details and never toggles completion.

Suppress redundant context carefully: under the Kyoto filter, a Kyoto-only assignment need not repeat on each row; “Also Tokyo” identifies alternatives and Anywhere stays explicit because its meaning differs. Recipient text may be omitted when it equals the active recipient. Never conceal other assignments or let a compact row imply exclusive eligibility. Screen-reader labels convey the same information without duplicate announcements.

Numbered mode adds a quiet ordinal next to the existing checkbox, or replaces a notes bullet with the ordinal. It does not add a numbered-list badge, rank field, or controls on every row. Move actions live in item details or an explicit Reorder mode. Explain numbering gaps once through contextual help in View, not with repeated banners.

## 5. Concrete progression

| State | Visible list canvas | Revealed on demand |
| --- | --- | --- |
| New grocery list | Title, items/checks, add item, normal actions | Item Add detail; View formatting |
| First gift has Maya + Kyoto | Same layout; one quiet detail line on that row | View now offers Places and For someone |
| User chooses Kyoto | One active view summary; matching rows | Other places, unassigned count and Clear |
| User enables numbering | Ordinals in the same rows | Reorder controls and full-order explanation |
| User opens recipe steps | Normal numbered checklist | No travel or recipient controls unless used |

Japan shopping is therefore created like any list. The user adds Tea, opens its details and chooses For someone → Maya and Place → Kyoto. Other items can reuse those values. View provides city/person organization from that data, without a setup wizard.

## 6. What “the list knows” means

In Phase 1 the app adapts to explicit data: which pickers are useful, which values can be reused, and which filters have content. It may derive counts and hide redundant labels. It does not infer a persistent gift/trip/recipe type, auto-change list kind, assign people, reorder priorities, infer completion, or infer store-city containment.

Phase 2 has two independent tracks: existing foreground location suggestions, and optional assisted capture. Neither blocks the quiet Phase 1 UX.

Assisted capture is a bounded experiment, not a commitment to hosted AI:

- Prefer explicit interactions and deterministic local recognition. A title alone never changes list behavior. A known place/person name alone is insufficient to silently assign metadata.
- For clear phrasing in a draft, the app may offer “Add Maya as recipient and Kyoto as place?” with the proposed values visible. One acceptance applies the detail; ignoring it saves the original text unchanged. Preserve original text by default even after acceptance; shortening it is a separate edit.
- Keep one suggestion at most in the item editor, outside the keyboard's primary action path. No unsolicited list-level popups, cards between rows, or changes while typing/checking items. Suppress suggestions during unresolved saves/conflicts.
- No repeated nagging: dismiss suppresses that suggestion for the current item content; a device/account preference disables assisted capture. Do not persist raw inferred content in analytics or portable backups.
- Accepting a suggestion is a normal explicit edit with current permissions, reference validation and conflict handling. Allow Remove detail immediately; an Undo implementation must check the expected current values so it does not overwrite a collaborator's later edit.
- Ambiguous phrases such as “read For Whom the Bell Tolls” or “call Jordan about Paris” remain text. No match or unavailable inference is a normal outcome, not an error.
- Any future model/provider choice requires its own evaluation and data-disclosure decision. No new hosted content sharing is authorized by this UX document.

Location remains a separate, intentional suggestion on Home or from Use my location, not another persistent list toolbar. At most one suggestion surface may be visible in a list/editor context; direct user actions and capture take priority. No automatic filter switch or permission prompt at creation.

## 7. Interaction, accessibility and sharing

Keep actions in stable locations; do not move controls as typing changes inferred intent. Opening a sheet, returning from a picker and saving restores focus predictably. No long-press, swipe, hover or drag is the only route to an action. Use labeled controls, adequate touch targets and screen-reader states; distinguish For someone from Share list.

A user can collapse row details through a client-local View preference if desired. This never affects collaborators, assignments or available filters. Shared numbering remains owner-controlled because it defines the shared sequence presentation; content details remain editor-accessible. Preserve existing member-removal, stale-edit and offline behavior.

## 8. Delivery and verification

Phase 1 includes creation simplification, progressive item details, data-derived filter availability, the single View sheet, quiet metadata rows and independent numbering. Build and evaluate these together rather than shipping an overloaded toolbar and promising later cleanup.

Before implementation is considered complete, inspect actual web and Android screens in these states: empty list, plain 30-item list, detail-rich 30-item list, active combined filter, numbered recipe, 360px phone, desktop, foldable and large accessibility text. Use real long labels and mixed-language content. Wireframe intent is not evidence of good visual execution.

Acceptance additions:

| ID | Required result |
| --- | --- |
| U1 | New list requires no kind/preset/organization decision; default item capture has no extra mandatory tap |
| U2 | First place/recipient assignment can be made and reused without a feature-enable screen; Cancel creates no orphan data |
| U3 | Resting plain list has no new filter bar, metadata placeholders, suggestions or per-row reorder controls |
| U4 | Combined filters are discoverable from View, visibly summarized, cancelable before Apply and clearable in one action |
| U5 | Metadata-rich list keeps item text dominant and full details accessible at narrow widths and large font sizes |
| U6 | Users distinguish recipient from collaborator and alternative places from multiple obligations |
| U7 | Numbered recipe has no gift/travel clutter; notes have no checkboxes/progress controls |
| U8 (Phase 2) | Wrong/ignored suggestions never alter saved text, fields, kind, order, checks or filters; dismissal is respected |

Run moderated tasks with participants who have not seen the design: ordinary capture, adding a gift detail, filtering by place, returning to all items, numbering a sequence, and correcting a detail. Target at least 4/5 completing each relevant task without coaching; record mistaken taps, undiscovered controls and recovery, not only completion time. Compare basic capture to the current app and iterate when discoverability is worse. These are proposed gates, not measured results.

## 9. Descriptions and detail-picker flows

Descriptions remain first-class optional content at both levels. A list description explains the whole list and appears below its title when present, initially up to two lines with More/Less for longer text. Edit it through List details (or optional More options at creation). An item description explains that one item. Label the existing item note field Description in the UI; preserve its stored content, paragraph breaks, search behavior and backup representation. Do not introduce competing Note and Description fields for the same item.

In the item editor, show the populated description immediately beneath Item in an expanding multiline editor. If empty, a quiet Add description action occupies that position; it opens the optional editor without requiring any other field. Several sentences, paragraphs or plain-text bullets belong in the same description. This design supports any combination of detail types, not multiple separately managed description records. Existing 4,000-character limits remain unless separately changed; enforce and explain the limit near the field only when relevant.

When Show item details is on, a populated item description gets its own short preview below the title, with More details to read the full text. Populated recipient/place/quantity metadata follows in a quiet separate line. This allows up to two secondary lines at normal text sizes instead of forcing prose and metadata into one line. Hide empty lines. At large text sizes allow wrapping. Hiding previews does not delete descriptions, and the editor always shows the full text. A list description is unaffected by the item-details switch.

Detail combination examples: description only; recipient only; multiple places only; description + recipient; description + multiple places + recipient + quantity. There is no required combination. In the current design an item has one recipient (or a named group without membership expansion), any number of alternative places up to the documented limit, and one multiline description. Independent purchases for different people use separate rows. Descriptions may mention a budget, URL or instruction as text without implying structured budget/link features.

For someone flow:

1. Choose Add detail → For someone (or tap the existing value).
2. Open a compact picker with Search or add a name, existing list-local recipients and No recipient. Selecting one replaces the draft recipient, not the saved item.
3. If a search has no exact match, show an explicit Create “name” action; do not create a person simply from typing. Creating records a pending definition in the item draft.
4. Return to the same editor with one selected value. Remove clears only that field. Save commits all edits; Cancel discards pending definitions and edits. The choice grants no list access.

Places flow:

1. Choose Add detail → Places, or Add place beside selected values.
2. Open a search/add picker with multiple selection and explicit Anywhere / No places set alternatives. Anywhere is exclusive with named selections. Use existing list-local names or explicit Create “name”. No GPS/address required.
3. Choose Tokyo and Kyoto, then Done. Done returns to the editor draft; it does not save the item. Keep the existing description and recipient untouched.
4. Display removable selections and a short helper explaining OR semantics. Save commits the complete draft atomically; Cancel changes nothing. Adding a place defines eligibility, not a second purchase.

View flow: opening a filter picker does not create item assignments. “Anyone” in View means all recipients, including unassigned; “No recipient” in the item editor clears only that item's recipient. Places in View selects one filter context; Places in the editor assigns multiple alternatives. Distinguish these headings/helpers so users do not confuse editing an item with filtering a list.

The View mock is the owner state. Number items is visibly labeled Shared list setting; non-owners cannot change it. Stage toggles and filter selections until Apply, including Show item details. Cancel/close discards the staged choices. If the owner changes numbering, save the guarded list setting first; only after success apply local view choices. On failure keep the sheet/draft open and explain it. If no shared setting changed, Apply is client-local. Do not display a claim that every choice in the sheet is personal.

Additional acceptance: description-only items, combinations with descriptions, long/multiline text, list versus item description, cancel after creating a new recipient/place, removing a place without touching prose, Notes with description but no checkmarks, and dictionary reuse. Verify the populated mock is not implemented as a requirement to fill every field.
