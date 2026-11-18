# Simplified UX mock review

Date: 2026-11-16

Selected: editor-v1.png, add-detail-v2.png, filter-v2.png. Generated with built-in image generation. One attempt for editor; two each for picker/filter; below five-attempt maximum.

Reviewers: GPT-6.1 Terra high (copy_editor, all copy signoff), GPT-6.1 Sol high (organization_debater and location_order_debater). Independent image inspections. Gate per image: each category mean >=8.5; every individual category >=8. Selected images pass. Primary visual inspection concurs but is not included in means.

| Image | Reviewer | Simplicity | Discoverability | Readability | Fidelity |
|---|---|---:|---:|---:|---:|
|editor-v1|Terra|9|8.5|9|9|
|editor-v1|SolA|9.5|9|8.5|9.5|
|editor-v1|SolC|9|9|9|9.5|
|editor-v1|Mean|9.17|8.83|8.83|9.33|
|add-detail-v1|Terra|9|8|9|8|
|add-detail-v1|SolA|9|8.5|9|8|
|add-detail-v1|SolC|9|9|9|8.5|
|add-detail-v1|Mean|9.00|8.50|9.00|8.17|
|filter-v1|Terra|9|8.5|9|8|
|filter-v1|SolA|9|9|9|8.5|
|filter-v1|SolC|9|9|9|9|
|filter-v1|Mean|9.00|8.83|9.00|8.50|
|add-detail-v2|Terra|9|8.5|9|9|
|add-detail-v2|SolA|9|9|9|9.5|
|add-detail-v2|SolC|9|9.5|9|9.5|
|add-detail-v2|Mean|9.00|9.00|9.00|9.33|
|filter-v2|Terra|9|8.5|9|9|
|filter-v2|SolA|9|9|9|9.5|
|filter-v2|SolC|9|9|9|9.5|
|filter-v2|Mean|9.00|8.83|9.00|9.33|

Initial picker fidelity failed because the editor backdrop changed and the For someone helper was missing. Revision overlays the original editor and includes the canonical helper. Initial filter was numerically acceptable but revised to add the unassigned count and distinguish square checklist controls from filter radios.

## How to read the flow

1. Editor shows Item and populated Description; no empty optional fields.
2. Add detail opens a compact list. Existing Description is omitted. Choose Place to open the place picker, select or create values, then Done returns to the unsaved item draft. Save commits it. The place-selection screen is not pictured in this set.
3. Filter is a separate places-only list with no checked-item history, so it has neither recipient nor completion controls. Kyoto is staged; Apply uses it, Cancel discards. One item has no place. The unassigned count belongs to the full list, not the Kyoto result set.

## Limits

Images are design mocks, not live application screenshots or tested interactions. Review did not establish contrast compliance, tap targets, keyboard/focus behavior, large text, or real persistence. Implement the count accessible name as '1 item without a place'. Quantity icon is generic; use the app's actual icon system during implementation. Prompts are saved in PROMPTS.md and REVISION-PROMPTS.md. All versions are retained. No application code changed.
