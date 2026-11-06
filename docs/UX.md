# Gather UX specification

Approved implementation direction for the MVP in PRD.md. Imagegen references illustrate the visual structure; this specification defines behavior and corrects image limitations. No application code was created during UX work.

## Visual system

Gather should feel like a tidy personal notebook. Use warm ivory, forest ink, modest category accents, useful line icons, and clear list content. Do not add decorative charts, stock photos, or dashboard metrics.

| Token | Value / use |
| --- | --- |
| Canvas | #F7F4ED |
| Surface | #FFFDF8 |
| Ink | #263D36 |
| Muted text | #5F7065; final runtime contrast refinement from prototype #66756B |
| Primary | #276653; white button text |
| Coral accent | #D88A70; decorative/favorite accents, never sole text contrast |
| Border | #E2E5D9 |
| Category wash | Everyday #E7EDDF, Ideas #FFF0D4, Gifts #FBE7DE, Trips #DFECE8 |
| Display font | Georgia, serif; 40px desktop / 30-32px phone, line-height 1.15 |
| Body font | system-ui, sans-serif; 16px, line-height 1.45 |
| Supporting text | 14px; avoid smaller critical labels |
| Section title | Georgia 22px desktop / 20px phone |
| Radius | 12px cards/dialogs, 8px inputs/buttons, full pills |
| Spacing | 4, 8, 12, 16, 24, 32, 48px |
| Shadow | none normally; subtle shadow for raised menus/dialogs only |

Use a consistent 20-24px line-icon set. Brand may use a simple leaf icon; avoid recreating the oversized desktop leaf in compact layouts. Page brand and tagline: Gather; “A little order. A little more space.”

## Layout and navigation

Desktop >= 1100px: 232px persistent sidebar, 32px content gutter, 3-column card grid with 20px gaps. Library destinations are Library, Favorites, Templates, Archive. Sidebar also exposes editable category filters and Settings & backup. At 768-1099px use a narrower sidebar or compact navigation and a 2-column grid. At <768px show one-column library and a focused single-list screen with a clear Library return link. No giant phone brand block.

The active library destination remains separate from the selected editor. Opening a list does not turn Templates or Archive into an ambiguous editor destination. Return restores destination, search and category selection. A template editor retains its “Template master” label.

Phone bottom navigation: Library, Templates, More. More opens Favorites, Archive, categories and Settings & backup. The focused list shows a small header with Back/Library, compact brand and list menu. Do not repeat the tagline here.

## Library screens

Top area: useful search (“Search lists and items”), primary New list, heading for the selected destination and restrained category pills. New list requires only a title; category optional. Search matches title, description and items, returning list cards with a matching item preview when relevant. Archive searches only archived lists.

Cards show icon, title, category, favorite control, optional description/matching preview, and textual “3 of 12 done” plus a thin progress line. An empty list says “No items yet.” Template cards say “Template master” and show item count plus “Start list”; do not show template completion as work progress. Library and Favorites exclude masters and archived lists. Archive cards expose Restore plus secondary delete. Cards should keep text wrapping reasonable and titles readable. Entire card can open the list, but favorite and menus must be separate controls with no nested buttons.

Provide visible Sample badges on seeded cards and list headers, with removal through list management/Settings. Seeded removal never silently reseeds. Library empty state: “A little space for your next list” plus New list. Search no-results state preserves search input and offers clear search.

## Focused list editor

Centered editor max-width 900px on desktop with title, description, category badge, favorite and contextual menu. Header progress states total done and remaining; zero items displays “No items yet.” Progress counts all items even when filtering.

Toolbar: All items / Remaining, subordinate Filter items control, Add section. On phone collapse secondary controls into menu or compact controls without horizontal overflow. Optional sections are titled groups; blank list begins unsectioned. Rows use 44px checkbox hit area, 16px text, optional 14px quantity/note preview and a 44px details/menu hit area. Rows roughly 56-72px depending on content. Completed items use checked state plus strike-through and muted text, with enough contrast to remain readable; a Done disclosure always shows its count and can reveal/unchecked items.

Item details dialog or sheet edits text, free-text quantity, note, section, and provides Move up / Move down and Delete. Desktop can expose modest arrow controls; phone and keyboard users must have menu reorder controls. Do not require drag-and-drop. Section menu includes Rename; removal, if offered, moves items into unsectioned group. List menu includes Edit details (title, description, icon, color, category), Duplicate, Archive, Delete. Archive and Delete require clear confirmation; Cancel retains data.

Quick add: single text input and Add/+ button. Enter submits. Blank input keeps focus and provides a useful message. Preserve valid drafts on failures; show saving/error status and prevent repeated taps creating duplicates. A successful add clears input and retains focus. Save status is truthful: saving, saved, or failed with retry.

On phone, quick add sits immediately above bottom navigation with safe-area padding. Scroll content reserves space equal to the combined bottom controls plus 16px so the last item and Done disclosure remain accessible. When the keyboard opens, navigation may hide; input stays visible above keyboard and content scrolls naturally. Do not hard-code fixed viewport height or overlay the last row. No horizontal scrolling at 360 CSS px.

## Templates and backup

Templates is a separate editable master collection. Open master editor shows explicit Template master badge and a primary Start list action. Starting opens editable proposed dated copy name; confirm creates independent active list with unchecked items, then opens that copy. Copy preserves sections, notes, quantities, order and presentation. Ordinary Duplicate creates unchecked active copy and names it clearly.

Settings & backup exposes Export JSON, Restore from JSON and category management on desktop and phone. Restore explains replacement of current workspace, offers export first and requires confirmation after schema validation. Show successful restore feedback; malformed files leave current content intact. Keep destructive actions visually distinct and secondary.

## Accessibility and responsive states

Use semantic buttons/forms, labelled search and quantity inputs, accessible checkbox labels, keyboard-visible 2px primary focus outline and logical tab order. Maintain 44px targets where practical. Color never carries completion/category/status alone. Primary text must meet WCAG AA; check contrast of muted labels in implementation. Menus/dialogs close with Escape and return focus; dialogs trap focus. Announce save errors and add/check changes through a restrained live region. Confirm actual mobile overflow, keyboard behavior and contrast in browser QA; an image cannot prove these.

Loading state is modest skeleton/content message. Failed loads show Retry. New/empty lists have direct Add item focus. Long titles, long notes and long category names wrap or truncate with accessible full text. Do not lose draft content while fetching or retrying.

## Reviewed imagegen prototypes

Generated using the built-in imagegen tool, one attempt per screenshot. Each was inspected visually. Acceptance: average >=8/10 and no category <7, up to three attempts per screen. All accepted on attempt 1; no regeneration was required. Ratings describe design potential, not working app verification.

| Screen | Visual quality | Hierarchy | Usability / task clarity | Completeness | Responsive potential | Accessibility potential | Feasibility | Mean |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Desktop library | 9 | 9 | 9 | 8 | 8 | 8 | 9 | 8.57 |
| Mobile checklist | 9 | 9 | 8 | 8 | 8 | 8 | 8 | 8.29 |
| Desktop editor | 9 | 9 | 9 | 9 | 8 | 8 | 9 | 8.71 |

Desktop library has polished hierarchy, meaningful progress and useful navigation. Desktop editor makes row actions and quick add evident. Mobile checklist demonstrates clear checking, quantity/note previews and separated sticky add/navigation, but the heading must be 30-32px in implementation, rows denser and Sample badge restored. Mobile screenshot illustrates only a subset of 12 items; the real list scrolls. Prototype up-arrow opacity is decorative: enabled reorder controls need sufficient contrast. Slight icon/font variations across generated screens should resolve to the tokens above in code.

| Reference | Workspace image | Saved prompt |
| --- | --- | --- |
| Desktop library | prototypes/desktop-library.png | prototypes/desktop-library.prompt.txt |
| Mobile checklist | prototypes/mobile-checklist.png | prototypes/mobile-checklist.prompt.txt |
| Desktop editor | prototypes/desktop-editor.png | prototypes/desktop-editor.prompt.txt |

Original built-in source directory: C:\Users\nolan\.codex\generated_images\01a0efc4-1752-70d3-8913-18967136ee57. Library source: exec-c155e6de-f6c7-430c-a266-c71ffd636165.png. Mobile source: exec-e99bc1fd-2c95-4e16-a107-401d3d3ecbb3.png. Editor source: exec-05e40498-9033-4191-a1be-914da12b5a6b.png. All three copied into this project. Generated UI images are design references; final delivery still requires screenshots and checks from the running app.

## Actual implementation screenshot review

Reviewed output/playwright/desktop-library-first.png and output/playwright/mobile-checklist-first.png from the running app. This review judges visible implementation, using the same seven categories as the references. Interaction and accessibility scores are provisional until browser/keyboard checks; source and raster images alone do not establish conformance.

| Screen | Visual quality | Hierarchy | Usability / task clarity | Completeness | Responsive presentation | Accessibility potential | Feasibility (implemented) | Mean |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Actual desktop library | 8.5 | 9 | 9 | 9 | 8 | 8 | 10 | 8.79 |
| Actual mobile checklist | 8 | 8 | 8 | 9 | 8 | 8 | 10 | 8.43 |

Both pass the >=8 average / no category below7 criterion. Desktop closely carries the accepted composition: restrained sidebar, useful search, category filters, distinct cards, sample marks, completion text and progress. Smaller actual headings/body density are appropriate. Its category accents differ slightly from the reference while preserving the intended calm identity.

Phone applies the specified smaller heading, compact branding, visible Sample badge, useful progress and separate quick add/bottom navigation. Quantity chips and section counts help scanning. The multi-line quick-add area includes a useful destination selector; content is expected to scroll behind the fixed capture area. The screenshot cuts the next row near that area, so review of scrolled last rows and keyboard-open behavior remains necessary and is assigned to runtime QA. The orchestrator reported coherent add/navigation bounds and no 390px overflow; this UX review did not independently remeasure them.

No threshold-driven redesign is needed. Two visible phone ellipsis buttons (global header and list heading) create slight ambiguity, particularly if one opens editing and the other full list management. Prefer one consistent list-actions menu if a small simplification is made; this is a polish opportunity, not a blocker. Tiny category, sample and progress supporting labels merit real contrast/zoom verification. Keep menu and checkbox tap areas 44px even where their visible icons are smaller.
