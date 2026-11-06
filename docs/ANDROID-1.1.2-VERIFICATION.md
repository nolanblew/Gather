# Android 1.1.2 Fold refinement verification

2026-11-06. Android-only visual patch, version code 4, min SDK 34, public endpoint https://gather.takeya.ninja/. Navigation thresholds remain unchanged: retain the rail on the unfolded Fold where both content panes fit.

## Changes
- Narrow library cards retain title, count, favorite and status badges while omitting duplicate description and progress graphics.
- Item editor is bounded to 440 dp, with tighter spacing and multiline fields that start smaller and grow.
- Login artwork receives a restrained theme-surface overlay in dark palettes.

## Verification
- 15 JVM tests passed; lint: 0 errors, 22 warnings.
- Signed release APK and AAB built successfully; APK installed on Samsung SM-F966U1 (Fold7), Android 17, serial RFCY71W2SFF.
- Public isolated QA account sign-in, library selection, book and landscape layouts inspected.
- Unsaved item text retained across rotation and font scale 2.0; editor controls visible and Cancel worked. Font scale change issued immediately after an earlier Cancel interrupted its dismiss animation; re-tapping Cancel after configuration settled dismissed the draft. No item was saved.
- No Gather AndroidRuntime crash entries. QA account signed out. Font scale 1.0, automatic rotation 1 and user rotation 0 restored and verified.
- Fresh evidence: output/android-adaptive/fold-1.1.2-{login,book,editor,landscape-editor,large-text-editor,landscape}.png plus XML/window dumps.
- This bounded patch check does not repeat the earlier full instrumentation suite. DeX, physical fold/cover transitions and TalkBack were not tested.

## Artifacts
- output/android-release/Gather-1.1.2.apk SHA256 0399D385B1696A1B08317084BE0005C1897F4D0B20AC9563BE5207999C8DB78D
- output/android-release/Gather-1.1.2.aab SHA256 1B9680D70B9E3BCB4E5FA39069E0D0B1840EAB7C72B18365BB65F2D8E674D69D

No API/server change was required for this Android-only patch.
