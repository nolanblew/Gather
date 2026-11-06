# Gather Android: adaptive layout research

Research for the 1.1.1 adaptive design, 6 November 2026. The inspected 1.1.0 baseline used a single scrollable, centered authentication column capped at 460 dp with a 124 dp artwork strip; its signed-in shell used bottom navigation and switched entirely between library, details, and settings. Library and settings content were capped at 840 dp. This baseline read clearly on a phone but underused a wide window. For implementation and observed test evidence, see [adaptive verification](ANDROID-ADAPTIVE-VERIFICATION.md); this research is not itself proof of device compatibility.

## Guiding decisions

- Use the **current app window**, its insets, width, height and actual usable pane constraints. Do not switch on model name, physical pixels, screen aspect ratio or a one-time launch measurement. Window dimensions can change continuously on rotation, folding, split screen and desktop resize. Width is useful for the high-level layout; short height and large text still determine whether a split can fit. [Android window size classes](https://developer.android.com/develop/adaptive-apps/guides/use-window-size-classes) and [support different display sizes](https://developer.android.com/develop/adaptive-apps/guides/support-different-display-sizes) describe this distinction.
- Preserve the user's task across adaptation: destination, selected list, filter/query, scroll position, editable form contents, focus and keyboard when feasible. Samsung explicitly recommends continuity of scroll, entered text and keyboard between cover and main screens: [Designing for foldables](https://developer.samsung.com/one-ui/largescreen-and-foldable/designing_for_foldable.html). Avoid claiming these are preserved until tested.
- Let the work remain visually primary. The auth screen can feel intentional through a proportioned illustration, spacing and alignment; it needs no extra marketing headline or explanatory paragraph. Existing labels, invitation context, password rules when registering, and accessible recovery remain. Visual beauty comes from a restrained palette, clear text hierarchy, consistent corners, deliberate whitespace and one emphasized action.

## Layout direction

### Authentication

On a narrow phone or cover display, use one comfortably bounded column: brand, artwork, mode selector, fields, primary action and recovery. Allow the art to have enough height to feel intentional on a tall screen, but cap it and let the page scroll when the keyboard, text scaling or a short landscape window reduces height. Avoid making useful controls depend on an image crop. Keep a clear tab order; preserve input and mode while resizing. Do not persist sensitive password text more broadly than necessary.

When the **actual** available width and height can fit both a useful illustration and an accessible form, balance an artwork region beside a bounded form region. Use that option based on measured space, with a single scrollable fallback when it cannot fit, including phone landscape, short DeX windows and large font scaling. A pane should not shrink form controls to fit. Keep the form anchored and predictable as the window grows; avoid stretching fields or placing ornamental blank space between inputs. Treat the illustration as decorative for screen readers if it communicates no information.

### Library and detail

Compact and insufficiently roomy windows retain a single active destination, bottom navigation, list search and New list. On a sufficiently wide and tall window show navigation at the side and a bounded library pane next to the selected list. Highlight the selected list; keep independently usable scroll areas, context-specific toolbar actions and meaningful reading widths. A two-pane mode must satisfy the actual minimum widths of both panes after navigation, gutters, insets, font scaling and any hinge exclusion. Google describes one-pane compact/medium and list-detail expanded as a starting pattern, not a substitute for verifying content fit: [adaptive app overview](https://developer.android.com/develop/adaptive-apps/guides/get-started-with-adaptive-apps) and [canonical layouts](https://developer.android.com/develop/adaptive-apps/guides/canonical-layouts).

Before a selection, use one quiet instruction in the detail region: **Select a list**. No faux content or extra feature explanation. Do not automatically select a list and thereby obscure what changed. When a wide layout narrows while a detail is active, keep that detail visible; when widened again, reveal the library with the same selected row. Back returns from compact detail to its list; expanded back behavior must be consistent and not accidentally exit, clear selection, or discard edits. Explicitly test filters, archive/favorites/templates, deletion and a list that becomes unavailable while selected.

### Settings and sheets

Use a readable bounded settings column on wider windows instead of making individual controls span a desktop; a side rail or app-level navigation can remain visible where there is room. Preserve account, backup and destructive explanations at their decisions. Forms and sheets need adequate width, scrolling, keyboard access and a clear close/cancel action on short and resizable windows. Do not assume that an expanded width implies a tall window. A system or custom window title bar and insets must not cover controls.

### Hinges and postures

A fold or hinge is not always an occlusion or separator. Use current display-feature bounds, orientation, occlusion and separating status when available. Do not place important content or controls under an occluding hinge; a separating feature may inform pane placement when both sides are usable. Avoid controls at a crease in partially folded posture, and gracefully fall back to a single pane when a region cannot fit. Do not fabricate a fold based on device model or simply split all wide windows down the middle. Samsung notes settings screens may not need a special Flex layout: [foldable design](https://developer.samsung.com/one-ui/largescreen-and-foldable/designing_for_foldable.html); [Android fold awareness](https://developer.android.com/develop/adaptive-apps/guides/foldables/make-your-app-fold-aware) details feature properties.

### Desktop, keyboard and pointer

A freeform DeX window must resize in both directions and remain usable at small dimensions. Desktop space should expose useful list and detail context, not enlarge every control. Support visible Tab/Shift-Tab focus, Enter/Space activation, Escape to dismiss temporary UI without losing unrelated work, keyboard scrolling and normal input editing. Offer clear hover/focus states and keep every primary task reachable with primary click and touch; hover or right-click may add convenience but must not be the sole route. Preserve touch targets for mixed-input use and inspect system window controls/insets. Google’s [desktop guidance](https://developer.android.com/design/ui/desktop/guides/foundations/get-started), [pointer interactions](https://developer.android.com/design/ui/desktop/guides/interaction/pointer-interactions) and [keyboard interaction](https://developer.android.com/design/ui/desktop/guides/interaction/keyboard) explain these expectations. Actual Samsung DeX behavior must be tested rather than inferred from generic Android desktop guidance.

## Reference device shapes, not layout switches

Samsung publishes the following native pixel dimensions; these describe full physical displays, not the density-independent app window. Insets, rotation, scaling, split screen and user settings alter the space available to Gather. Rounded corners and camera cutouts reduce visible area. Pixel ordering below follows Samsung’s published numbers; both orientations must be tested.

| Device | Main | Cover | Source |
| --- | --- | --- | --- |
| Galaxy Z Fold7 | 8.0 in, 2184 × 1968 (roughly 1.11:1) | 6.5 in, 2520 × 1080 (21:9) | [Samsung specifications](https://news.samsung.com/global/samsung-galaxy-z-fold7-raising-the-bar-for-smartphones) |
| Galaxy Z Fold8 | 7.6 in, 1848 × 2448 (roughly 3:4) | 5.5 in, 1248 × 1972 (roughly 0.63:1) | [Samsung specifications](https://news.samsung.com/global/samsung-galaxy-z-fold8-ultra-fold8-and-flip8foldables-perfected-for-every-way-of-living) |

The Fold8 and Fold8 Ultra are different devices; do not substitute Ultra dimensions for Fold8. Their published screen aspect ratios are illustrative test cases, not evidence of actual dp or pane fit. A broader book-shaped test range is also useful because future hardware and resizable windows need not match either device.

## Acceptance matrix

Verify separately, and report the device or emulator, configuration and actual observed outcome:

1. Phone and Fold7 cover: portrait and landscape, normal and large text; auth with/without keyboard; library, detail, settings and editors; scrolling and touch targets.
2. Fold7 inner and Fold8-like book proportions: portrait and landscape, flat and supported partially folded posture; check occlusion and content alignment. Samsung real-device Fold8 only counts if actually available; a custom emulator or viewport is a shape simulation, not device proof.
3. Fold/unfold or resize while selected, searching, scrolled and editing: state, focus and back behavior; narrow → wide → narrow without silently losing text or selecting a different list.
4. DeX or desktop freeform: continuously resize through the pane transition and into short/narrow windows; use mouse, wheel, physical keyboard and touch as available; inspect system decorations and insets. Emulated desktop viewport is not DeX proof.
5. Two-pane: selected row matches the detail, empty selection prompt is understandable, actions affect the selected list, filtering or removal cannot leave stale content. Test accessible reading order, keyboard focus, large font, contrast, reduced-motion settings and RTL.
6. Check invitation, offline/retry, sharing and destructive dialogs after each major adaptation; do not lose consequences or recovery affordances. Include narrow IME and long localized labels. Compare screenshots visually, then exercise real interactions: a screenshot alone does not establish continuity or usability.

## Research notes

[Android window-size classes](https://developer.android.com/develop/adaptive-apps/guides/use-window-size-classes) currently define compact width below 600 dp, medium 600–839 dp and expanded from 840 dp, with compact height below 480 dp. They are useful planning and test boundaries, not unconditional requirements to place two panes in every 840 dp window. [Android desktop windowing](https://developer.android.com/develop/adaptive-apps/guides/support-desktop-windowing) describes independently resizable windows and system headers. [Samsung app continuity](https://developer.samsung.com/galaxy-z/app-continuity.html) ties resizability to foldable, DeX and freeform use; [Samsung testing](https://developer.samsung.com/galaxy-z/testing.html) distinguishes device tests and emulator approximations.

Baseline 1.1.0 continuity risk: editor selection and most list/item draft fields used `remember`, so activity recreation could discard an open draft. A layout that survives live resizing alone does not establish fold/rotation continuity. Password fields need a deliberate security-sensitive approach rather than indiscriminate persistence. See [adaptive verification](ANDROID-ADAPTIVE-VERIFICATION.md) for the implementation and tested outcomes.

## Authentication concept review

The three generated studies in `docs/design-studies/android-1.1.1/` explore visual direction, not final assets or evidence of functional behavior. **C, Composed card**, best balances a purposeful artwork region with a compact, legible form in both examples; it uses no new slogan. Preserve Gather's actual native brand, type, selector, persistent field labels and accessible controls rather than copying the generated serif controls literally. A's nested frame and large art create more visual weight; B is open and airy but its unbounded illustration requires more careful cropping and competes with the form. C must adapt to small heights and the keyboard: cap or reduce art before constraining input, keep scrolling, and only split when both regions fit. Test registration rules, invitation context, notices, errors, dark themes and large text, none of which the concept proves. A Fold7's physical resolution does not establish its runtime dp; use measured fit rather than requiring 840 dp on every unfolded device.

## Final adaptive design direction

The initial two-pane prototype duplicated library content in an overview and could show recent lists beside “No results.” The selected direction uses a quiet “Select a list” prompt until a list is selected. The paired library and detail provide useful wide-screen content without an extra dashboard.

Authentication follows concept C's composed artwork and form, using Gather's existing brand and native controls. The compact paired-pane detail header retains the title, sharing status, progress and actions while leaving more room for items. In the final Fold7-shaped emulator screenshot, two item rows are visible; this visual observation is not a physical-device compatibility claim. See [adaptive verification](ANDROID-ADAPTIVE-VERIFICATION.md) for the tested configurations and limits.
