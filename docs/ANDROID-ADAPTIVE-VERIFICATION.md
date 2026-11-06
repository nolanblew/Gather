# Gather Android 1.1.1 verification

Verification record for the 6 November 2026 Android patch. Device evidence and simulated screen shapes are distinguished below.

## Release scope

- Android 14 minimum (API 34); version 1.1.1, code 3.
- Concept C authentication composition, retaining Gather's existing artwork, brand, controls and short animations. Three generated studies and exact prompts are retained in `design-studies/android-1.1.1/`.
- Window-aware navigation and library/detail composition, unobstructed fold regions, readable settings and editors.
- Configuration-retained sign-in fields, editor drafts, selection and scroll positions. Sensitive input remains in memory, not saved to a Bundle or disk.
- Release builds use `https://gather.takeya.ninja/`; the existing production API remains unchanged.

## Automated and physical-device results

The build includes 15 passing JVM tests and Android lint reports **0 errors, 22 warnings**. Both signed, optimized APK and AAB packaging pass; the AAB signature verifies with the existing self-signed Android release certificate.

| Run | Result | Duration |
| --- | --- | --- |
| Android 14 emulator, normal phone window | 10/10 acceptance tests | 99.050 s |
| Pixel 7 Pro, Android 16 | 10/10 acceptance tests | 53.209 s |
| Android 14 emulator, 960 × 720 dp wide window | 10/10 acceptance tests | 76.967 s |
| Android 14 emulator, 1440 × 900 dp at 200% text, final navigation sizing | 3/3 targeted acceptance tests | 27.804 s |

The wide run includes the compact paired-pane header. The suite exercises real UI and the disposable local API: list/item CRUD, completion, scoped search, categories, sharing, template copies, archive/restore, credential-error recovery, appearance, incoming-share replacement, export lifecycle, interrupted navigation and remote deletion. New checks cover real rotation and Activity recreation with auth/editor input retained; a saved draft produces exactly one item; actual IME visibility leaves the field focused and the auth layout stable; and WindowManager-injected 16 dp vertical/horizontal separating hinges retain selection and avoid pane overlap in LTR and RTL. A tabletop confirmation is tapped and cancelled, with server state verified unchanged. Existing animation-clock and zero-duration checks pass.

The public release was installed over 1.1.0 on the Pixel. Through the signed app, the isolated QA account signed in, read its existing library, changed a checkmark, and restored it. Independent browser-cookie API reads confirmed both states. Sign-out completed, leaving the release app ready for the user's account. Android App Links reports `gather.takeya.ninja` verified.

## Window and visual checks

All dimensions below are **requested viewport dp at emulator density 320**, not claims about Samsung's actual default density. Captures include actual window/activity dumps and a UI hierarchy in `output/android-adaptive/`.

| Shape | Viewports inspected | Observed composition |
| --- | --- | --- |
| Phone | 392 × 850 and physical Pixel | Fuller stacked auth; focused single destination |
| Fold7 cover proportions | 360 × 840, 840 × 360 | Single pane; side navigation in short landscape |
| Fold7 inner proportions | 728 × 656, 656 × 728 | Paired panes where both fit; selected detail retained when narrowed |
| Fold8 cover proportions | 416 × 657, 657 × 416 | Bounded single pane and scrollable short layout |
| Fold8 book/main proportions | 616 × 816, 816 × 616 | Single pane in narrower book view; paired panes in landscape |
| Desktop and short window | 1440 × 900, 1280 × 360 | Drawer plus paired panes; rail and single pane when short |

The same selected list remained open through the entire resize matrix. Paper and Forest views, settings groups, editor surfaces and 200% text were inspected. The paired header was reduced after visual review so two complete item rows fit in the Fold7-shaped example. Search keeps the accessible name “Search lists and items” while displaying the shorter “Search.” Navigation widths grow with font scale and measured labels; the final 200% captures show complete navigation labels. After that adjustment, the real-keyboard, rotation/draft, and injected-hinge/working-dialog tests passed again at 200% text. The final signed APK was reinstalled and launched on the Pixel.

Actual Android **freeform** mode was enabled in the emulator and confirmed through `dumpsys`. Its task was resized among 1320 × 750, 720 × 550, 380 × 700 and 1220 × 350 dp bounds. The selected list and typed item draft survived; native caption controls stayed outside app content. The editor's Close action remains outside its scrolling body. Tab reached New list, Enter opened the editor, and Escape dismissed it. This verifies keyboard navigation and touch interaction in Android freeform, not a physical mouse/wheel or Samsung DeX session.

Screenshots: `pixel-final-1.1.1.png`, `final-fold7-inner.png`, `final-desktop.png`, `layout-review.png`, `freeform-editor-*.png`, and `freeform-keyboard-new-list.png`. Original full-resolution captures are retained alongside the review contact sheet.

## Review findings resolved

- Window geometry initially included IME insets, which could replace a focused form while the keyboard opened. Structural bounds now exclude IME; scroll content handles keyboard overlap separately.
- Upper tabletop panes must consume the space below them before applying keyboard insets. The shared pane wrapper measures that gap.
- Offsetting a small native dialog could move it outside its touch window. Editors and confirmations now use a full-window host with a safe-region surface.
- Retained capture drafts reset synchronously before a replacement share is published. Sensitive drafts remain memory-only and clear with the account.
- Tests were updated to reveal retained LazyColumn rows before interacting with offscreen headers or category chips. The original phone run passed 8/9 before this test correction; the corrected full runs passed.
- The large-text hinge test now compares restored bounds with its pre-fold baseline. Unfolding can legitimately narrow a detail pane when the side rail becomes a larger drawer; assuming the pane must grow was incorrect.
- Concurrent test runs briefly hit the local server's explicit authentication-busy response. The visible recovery state retained the fields; a sequential Pixel run passed all ten tests. No automatic replay of authentication or other writes was added.

## Release artifacts

`output/android-release/Gather-1.1.1.apk` and `Gather-1.1.1.aab` use version code 3, minimum SDK 34 and target SDK 35. Prior 1.1.0 artifacts remain available. Release DEX inspection confirms the public HTTPS endpoint and excludes the localhost test endpoint. The release certificate SHA-256 is `75:4B:1A:8C:7C:8E:78:AF:87:A9:A3:F0:60:B7:80:A1:FA:0F:FE:9E:C8:82:10:34:B4:84:2E:37:D1:AB:06:4D`.

| Artifact | SHA-256 |
| --- | --- |
| APK | `b1d4764cdc8f7e0ab480bcc7a084d59c96f08eb23b84b07b1c75ae4f5e42a4aa` |
| AAB | `e24f2668ac3e074d6c8c7aa59d42ccbaf3932fd62433da89d051a3a6f6adf876` |

The public API remains the deployed shared API; this Android-only patch changes no server contract or production data migration. Test changes used isolated QA accounts. All saved Android settings were restored and verified, the emulator's display size/density reset, task ADB port forwards removed, and this task's emulator and local server stopped. The Pixel remains on the signed 1.1.1 sign-in screen.

## Evidence boundaries

Physical-device evidence is from a Pixel 7 Pro. Emulator sizes represent viewport and display-shape simulations, not tests on Samsung hardware. Android freeform-window testing does not establish Samsung DeX-specific behavior. Fold-feature injection can verify Gather's handling of WindowManager features; it does not validate an OEM's feature reporting.

Native screen pixel specifications do not determine app-window dp. Tests must record the requested viewport, density, actual app bounds and observed layout. See `ANDROID-ADAPTIVE-DESIGN.md` for official sources and the design rationale.
