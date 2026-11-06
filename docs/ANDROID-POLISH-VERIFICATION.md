# Gather Android 1.1.0 — motion and copy polish

November 6, 2026. Native Android release; the shared API and deployed web service are unchanged.

## Changes

The authentication selector slides between Sign in and Create account. Registration guidance expands beneath the same persistent fields, so mode changes preserve text, password visibility, focus, and input composition. Button labels change without replacing the form. The illustration is smaller, slogans are removed, and password recovery guidance is behind Forgot password?.

Library, detail, settings, forms, sharing, and empty states use direct names and concise instructions. Destructive effects, sharing permissions, backup exclusions, password requirements, and actionable errors remain explicit. Ordinary lists no longer show a repeat-cadence disclaimer; selected daily/weekly labels still explain that reset is manual. Research, sources, tradeoffs, and the copy audit are in [ANDROID-POLISH-RESEARCH.md](ANDROID-POLISH-RESEARCH.md).

Navigation uses direction-aware slide/fade transitions. Outgoing pages retain their own display data and cannot receive pointer or accessibility actions. Session entrances animate; sign-out disposes private content immediately. Keyed list placement, progress, completed-item color, notices, action states, choice indicators, and appearance changes animate together. Editor Save, Cancel, and Done wait for sheet dismissal instead of cutting away. The New list button has an explicit native accessibility label.

## Motion policy

| Motion | Normal-scale duration |
| --- | --- |
| State/color fades, action labels, choice indicator | 160 ms |
| Auth selector, guidance expansion, page transitions, session entrance, placement, progress, theme colors | 220 ms |
| Standard Material bottom sheet entrance/dismissal and scrim | 300 ms |
| Standard Material menu entrance/exit | 120 / 75 ms |

App-defined durations live in `GatherMotion.kt`; effects run concurrently rather than as delayed sequences. Stock component timings were checked against the pinned Material3 1.3.0 sources. Android's motion scale remains authoritative: zero disables motion, and an intentionally slower system setting extends it. Keyboard, launch splash, and system file-picker motion belong to Android.

## Verification evidence

- Kotlin unit tests: 11 passed. Final build runs lint, debug/test APK assembly, minified signed release APK assembly, and signed App Bundle assembly. Lint has zero errors and 22 existing warnings, primarily dependency/target updates and intentional synchronous credential persistence.
- The acceptance suite now contains six real-app tests: the complete lists/sharing/search/template/archive/appearance journey; incoming-share replacement; export recreation and failed-sharing cleanup; intermediate auth movement and rapid mode reversal; configured zero-motion behavior; and interrupted detail navigation with externally deleted selection. Tests use only disposable local accounts and refuse production endpoints.
- The auth movement test inspects an intermediate position, verifies settling within 500 ms, preserves the entered username/password and visibility state, and then authenticates with those retained values.
- Compose's test runner replaces the production recomposer. The zero-motion instrumentation test therefore explicitly provides the duration scale read from the real Android setting. It is not, by itself, proof of the production system observer. A separate ordinary-app Pixel recording with the global animator scale set to zero verifies that path; the original setting was restored afterward.
- `output/android-polish/auth-motion.mp4` and `auth-motion-strip.png` show intermediate selector positions and coordinated content expansion. The 30 fps frame sample has six intermediate changed frames for a normal transition. `auth-zero-motion.mp4` and `zero-motion-strip.png` show a direct switch with no sampled intermediate position. These are visual timing observations, not a frame-rate benchmark.
- `navigation-motion.mp4` records physical-device page navigation, checklist progress, appearance changes, editor opening, and Back dismissal. The final two text/accessibility refinements do not change these effects.
- Large-text inspection at 2.0 font scale on Android 14 confirms wrapping auth labels and accessible fields. Labels are measured, centered, and padded without reducing the user's font size. The original font setting is restored.
- Independent implementation/copy/test review found no unresolved concrete defect after the final fixes. Screenshots and logs are retained under `output/android-polish` and `output/android-polish-*.log`.

## Artifacts

- APK: `output/android-release/Gather-1.1.0.apk`, SHA-256 `77f5c38c5344f91c79cdec70047cb2a7b121d9e650fc0772dc560fbb95594915`.
- AAB: `output/android-release/Gather-1.1.0.aab`, SHA-256 `20e41299151c80649ceeee6bb7749c2ac65092a9656839f7b83517263f278681`.
- Version code 2, minimum Android 14 / API 34, release application ID `ninja.takeya.gather`.
- Same retained release signing certificate and public endpoint `https://gather.takeya.ninja/`; existing app data survives the signed update.

## Final release checks

The final six-test suite passed on the physical Pixel 7 Pro / Android 16 in 35.237 seconds and the Android 14 emulator in 38.683 seconds. Logs: `output/android-polish-pixel.log` and `output/android-polish-api34.log`. The exact final build passed all 11 unit tests, lint, and both signed release packaging tasks (`output/android-polish-final-build.log`).

The signed 1.1.0 APK installed successfully over 1.0.0 on the Pixel and launched on Android 14. Package metadata reports version code 2, minSdk 34, targetSdk 35. Digital Asset Links remains verified with the existing certificate. A real sign-in to the public service loaded the isolated QA account; a checkbox edit from this APK was confirmed through a separate browser-cookie API session. Native UI inspection confirms the New list accessibility label, the removed default repeat footer, and the final concise settings copy. Password recovery opens its small instruction dialog and returns to the unchanged sign-in form.

Final large-font evidence is `output/android-polish/api34-final-large-text.png`; final signed phone entry screen is `pixel-final-sign-in.png`. Neither device's final AndroidRuntime log buffer contains an error. The physical device's three animation settings were restored exactly (animator unset; transition/window 1.0). The emulator font setting was restored. QA is signed out, debug/test packages and the local reverse mapping are removed from the Pixel, and the disposable local server/emulator are stopped. The signed app remains installed and ready for the user's account.
