# Physical-device acceptance

The tests use the real Compose app and public HTTP API. They reject release builds and any server other than HTTP loopback on port 48733. Test fixture setup never reaches production.

1. Run a fresh local server on port 48733 with isolated `DATA_DIR` and `SEARCH_PROVIDER=fuzzy`.
2. Set `adb reverse tcp:48733 tcp:48733` for the authorized physical device.
3. Build/install debug plus debugAndroidTest with `-PgatherServerUrl=http://127.0.0.1:48733/`.
4. Invoke the instrumentation runner with arguments:
   - `gatherTestUsername`: a synthetic account beginning with `android-e2e-` (use a fresh name per acceptance run).
   - `gatherTestPassword`: its local test password, using the normal account policy.
   - `gatherTestBaseUrl`: optional; defaults to the debug app's server URL.

The fixture logs in through the public API, registers when the synthetic account is new, then UI tests sign in through the ordinary screen. Additional native and cookie clients verify that the UI's actions persist and cross-client edits converge. No production authentication shortcuts or test-only app hooks are used.

The class contains the main UI journey plus two focused lifecycle regressions: successive real `ACTION_SEND` intents must replace an open capture draft; pending export must survive Activity recreation and finish writing through its retained ViewModel; an authenticated failed sharing request must remove the previously displayed raw invitation token. The export regression uses an additional retained production ViewModel instance and an application-private file URI to avoid automating an external picker. The full Storage Access Framework picker and rotation flow is verified separately by the device operator.

Failed-test screenshots are saved in the app's external files `acceptance` directory for artifact collection. Preserve instrumentation output and check device logcat for fatal exceptions separately; JVM and API unit tests are separate evidence.

Three motion regressions use the real app: the authentication selection must pass through an intermediate position and settle within 500 ms; rapid mode changes preserve one username/password form and password visibility, then authenticate with the retained values; zero motion scale must leave authentication and navigation usable; interrupted detail navigation and remote deletion must settle on the correct surviving page. These checks control Compose's clock rather than comparing source timing constants. Motion tests temporarily set Android's system animation scales, wait until the settings reflect the change, and restore each original setting in `finally`, including deleting settings that were originally absent.

Compose 1.7.2's test rule replaces the production window recomposer and its system-setting observer. The test rule therefore supplies a `MotionDurationScale` that reads the real global animator setting explicitly. The zero-scale test proves that app animations honor the test-configured scale; production's automatic system-setting integration must be verified separately by running the app outside Compose instrumentation. Keep device animations available when manually judging visual smoothness; deterministic timing and functional checks do not measure frame jank.

Adaptive lifecycle checks rotate the actual Activity to landscape and portrait and recreate it while credentials or an item editor contain unsaved changes. They verify retained input and selection, primary-action reachability with the keyboard, a single persisted item after saving, Android Back, and password clearing after authentication/sign-out. The original requested orientation is restored in `finally`. Run the suite in real compact, unfolded, and desktop-sized app windows as a separate matrix: rotation alone does not prove arbitrary window resizing or fold-feature delivery. Pure window-region tests verify that book and tabletop regions exclude occluding hinges; live fold posture, pointer/keyboard input, large text, and visual fit remain device/emulator checks.

The injected-hinge test publishes nonzero vertical and horizontal separating features through `WindowLayoutInfoPublisherRule`, using the production `WindowInfoTracker` subscription. It checks window-relative pane bounds against the obstruction in both LTR and RTL, retains selection through fold/unfold, and verifies the same Activity remains alive. This exercises feature delivery and inset/coordinate handling without requiring a physical hinge; actual fold hardware remains separate evidence.
