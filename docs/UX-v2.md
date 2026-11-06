# Gather v2 UX specification

Approved product direction in docs/PRD-v2.md. This extends docs/UX.md; the existing typography, layout, navigation, capture, accessibility and backup requirements continue unless specified here. These are implementable screen decisions, not generated prototype evidence.

## Native appearance

Settings & backup begins with Appearance: Light, Dark, System. Use labelled buttons with pressed state and a short “Follows this device” hint for System. Preference persists on this browser; System reacts to operating-system changes without a reload. Default is System. Theme changes apply immediately while the dialog stays open.

Use an external head bootstrap to set the initial theme before React/styles render, respecting the self-only script policy. Native inputs, selects, dialogs and scrollbars use the effective color-scheme; theme-color follows that same effective theme. Storage denial falls back to System without breaking the app.

| Token | Light | Dark |
| --- | --- | --- |
| Canvas | #F7F4ED | #191E1B |
| Surface | #FFFDF8 | #222A25 |
| Raised/input surface | #FCFBF5 | #29322C |
| Ink | #263D36 | #E7EDE4 |
| Muted | #5F7065 | #ADBBAE |
| Primary | #276653 | #A4C5AB |
| Primary button ink | #FFFFFF | #18251D |
| Border | #E2E5D9 | #435047 |
| Selected wash | #E2EADC | #354B3B |
| Coral/favorite | #A86F4D | #E2A388 |
| Error ink | #783C30 | #F3B7A4 |
| Error wash | #FAEAE2 | #442E29 |

Dark category colors are desaturated accents with deep category washes, not the light palette inverted. Every color role, including hover, input, checkbox, quantity, master badge, progress track, modal backdrop and mobile capture gradient, has an explicit token. Shadows stay subtle. Preserve text contrast, readable completed items and visible focus in both themes.

## Checklist and notes

Create/Edit details includes a labelled Checklist / Notes choice. Checklist is the default. Explain each choice briefly: “Check off tasks and essentials” / “Keep ideas and bullet points.” Use accessible buttons with pressed state; selecting one does not submit the form.

Notes cards and editor headers show a Notes badge and item count. Notes rows use quiet bullets in place of checkbox controls; tapping text or the details button opens the existing edit dialog. Sections, ordering, quantities, per-item notes, quick add, favorites, archive and templates continue to work. No progress bar, Done group, Remaining filter, checked count or reset action appears for notes. All note entries appear in their saved section/order even if dormant completion history exists.

Changing an existing checklist to Notes preserves item text, sections, quantities, notes, order and stored completion/cadence history. Show a concise helper explaining that checkmarks are hidden and restored if the user switches back. Returning to Checklist reveals the retained state. Do not silently clear history during editing.

Template masters show item count and kind, not task progress. Notes templates create independent notes copies. Existing version-1 lists remain checklists with Manual cadence after migration; do not infer types from titles or categories. Fresh sample ideas/gifts can demonstrate Notes.

## Reuse and restart

Checklist details expose Restart rhythm: Manual, Daily, Weekly. This is a saved reminder of how the user reuses the list; no scheduler checks, clears, creates or sends anything automatically. Helper: “Start again when you’re ready. Items never reset automatically.” Notes hides this setting but retains a prior saved value.

Checklist cards/editor may show a compact Daily or Weekly badge; Manual needs no extra badge. Template copies inherit kind and rhythm plus existing presentation/content. Start list remains the template master’s primary action and creates a separate working copy with checks reset; its name is dated and editable.

An active checklist with at least one checked item exposes Start again beside its progress. Keep it available when partly completed. Completed active checklists frame it as the next useful action, while retaining access to Done items. Confirmation: “Start this list again?” with “Reset X checked items? All item text, notes, quantities and sections stay as they are.” Confirm label: Start again. Cancel changes nothing. The operation clears checks on the same list; it does not duplicate or edit the template. Hide restart on notes, archived lists and template masters.

## Ranked search

Library search searches meaningful stored content: list title/description, item text/quantity/note, section titles and category names. Current collection is the default scope and applies the selected Library/Favorites/Templates/Archive destination plus category before every matching/ranking tier. While searching, offer a labelled Current collection / Entire workspace choice. Entire workspace includes active lists, templates and archived lists only after explicit selection, with category still binding and Template master/Archived result badges. Do not apply a client substring filter to server-ranked results.

Use the existing search field, debounce input and expose a restrained Searching status with aria-busy on results. Preserve the query and useful results on refresh/failure; avoid a blank-grid flash between keystrokes. Clear removes search and returns to the chosen collection. Search is a ranked set of list cards, not a chat interface.

Cards show short evidence from the actual matching field, such as “Item note: spare batteries for the cabin,” with the original stored excerpt. Never invent or summarize unsupported content. Opening a result opens its list with all items visible; do not pass a semantic phrase into the literal item filter and hide the evidence. Results in Archive/Templates retain their explicit badge and appropriate editor actions.

Status reflects real capability: Smart search when semantic ranking actually succeeds; Fuzzy search when lexical/fuzzy fallback supplies the response. A missing provider remains useful without pretending semantic readiness. Settings exposes a small read-only search capability explanation. Environment credentials and model/endpoints belong in deployment documentation, never in browser forms or bundles.

## Mobile and accessibility

Keep the current compact brand and one list-actions menu. At 360px, kind/appearance buttons may wrap and restart/action rows stack; the header does not gain a settings/search dashboard. Search capability/scope status is a short line beneath search rather than squeezed into the title. Bottom navigation and quick add continue to reserve enough last-row scrolling space and safe-area padding in both themes.

Notes bullets are decorative, and item text/details remain keyboard operable. Checklist controls retain accessible names. All segmented choices expose their selected state. Search status is a polite restrained live region; repeated typing must not produce excessive announcements. Native dark controls and all dialog/error states require actual browser inspection, alongside theme persistence/system changes and 360px overflow checks.

## Delivery evidence

Final verification includes actual dark desktop library, dark mobile notes/checklist and relevant Settings/form screenshots. Review visual hierarchy, density, contrast potential and phone layout against the existing >=8 mean / no category <7 threshold. Runtime tests must separately prove notes have no completion controls, restart preserves content, template copies inherit kind/rhythm, version-1 backup compatibility, and typo/semantic search returns useful evidence. Provider unavailable/timeout paths must remain usable and honestly report fallback.


