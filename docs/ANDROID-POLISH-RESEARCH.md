# Gather Android: interface polish research

Research and copy audit, 6 November 2026. Scope: native Android screens in `GatherApp.kt`, `Editors.kt`, and user-facing state in `GatherViewModel.kt`; source-backed decisions mapped to implementation; visual and accessibility validation remains separate.

## Direction

Gather is a personal and small-group list tool. Use calm, plain, specific language. Name the screen or action, then let the content do the work. Remove ornamental phrases, duplicated explanations, and summaries that say nothing the user needs to decide. Keep the details that explain permissions, destructive effects, privacy, network state, and recovery. Use sentence case, familiar terms, and the same name for the same action throughout. Avoid inventing a product slogan.

A visually quiet screen should still make primary actions obvious: align content, use a small consistent spacing and type hierarchy, reserve stronger color for meaningful actions, and let list content dominate. Group related settings without turning every sentence into another card. Preserve comfortable targets and readable contrast; support text scaling and small screens. Motion should help communicate a state change, remain brief and unobtrusive, and respect device accessibility preferences. This is a design direction, not a prescribed animation duration.

## Evidence

- [Apple, Writing](https://developer.apple.com/design/human-interface-guidelines/writing): clear, concise action labels, consistent language, actionable empty states and errors, useful field labels, and restrained possessives. Apple guidance is cross-platform inspiration; Android conventions remain the implementation target.
- [Android, Content composition and structure](https://developer.android.com/design/ui/mobile/guides/layout-and-content/content-structure): establish hierarchy with alignment, space and typography; consistent spacing and adaptive grids support scanning.
- [Android, Color](https://developer.android.com/design/ui/mobile/guides/styles/color): semantic color-role pairs support contrast and hierarchy; primary accents should draw attention to important actions.
- [Android, Settings](https://developer.android.com/design/ui/mobile/guides/patterns/settings): clear, precise labels, manageable groups and contextual placement of frequently used actions.
- [Android, Accessibility](https://developer.android.com/design/ui/mobile/guides/foundations/accessibility): legibility and accessible labels, at least 48 dp interactive targets, and alternatives to gesture-only actions.
- [Android, Compose accessibility](https://developer.android.com/codelabs/jetpack-compose-accessibility): verify semantic labels and target sizes, including custom controls.
- [GOV.UK, Error messages](https://design-system.service.gov.uk/components/error-message/): explain the problem and how to fix it; place field errors near the relevant field.
- [Apple, Layout](https://developer.apple.com/design/human-interface-guidelines/layout): order and group by importance and use progressive disclosure where it improves comprehension.
- [Apple, Reduced Motion criteria](https://developer.apple.com/help/app-store-connect/manage-app-accessibility/reduced-motion-evaluation-criteria/): test for distracting motion and respect the system preference; platform-specific APIs and behavior should be verified on Android.

## Copy decisions and implementation map

The map records the original text and the implemented direction; entries explicitly marked as conditional or suggested remain follow-up considerations. Empty descriptions are omitted from the UI. Functional labels, dynamic values, consequences and accessibility semantics are preserved.

### Launch, authentication, and connection

| Existing | Recommendation |
| --- | --- |
| Opening your space… | Opening Gather… |
| A little order. A little more space. | Remove; retain the Gather brand and auth controls. |
| Everyday lists, bright ideas, and plans worth keeping. | Remove. |
| Create your space | Create account |
| Private to you. Shared when you choose. | Remove; explain actual access at the list and sharing decisions. |
| gather.takeya.ninja · Connection required | Removed from the healthy auth screen; connection failures retain a clear message and retry. |
| Sign in or create an account to review your invitation. | Keep. |
| Password rule and administrator reset instruction | Keep; the precise rule and recovery path matter. |
| Connection needed · Current view is read-only | No connection · Read-only; keep Retry. |
| That content is no longer available. Your view has been refreshed. | This content is no longer available. The view has been refreshed. |
| Back to library / Refresh lists / Share list / Dismiss message | Keep as descriptive accessibility labels even when controls are icons. |

### Library and search

| Existing | Recommendation |
| --- | --- |
| A place for everything | Lists |
| Close to hand | Favorites |
| Ready for next time | Templates |
| Tucked away | Archive |
| Your whole space | All lists |
| Your everyday essentials. / Keep the rhythm. Start a fresh copy. / Still here, whenever you need them. / [username]’s lists & little plans | Remove secondary tagline for each scope. |
| Entire workspace | All lists (also update any explanatory references to match). |
| Find a list, item, or idea… | Search lists and items; ensure the search field has a persistent accessible name, not only a placeholder. |
| No matches yet | No results |
| Try another word, or search the entire workspace. | Try another search or change your filters. |
| Keep your favorites close | No favorites yet |
| Tap the star on a list to find it here. | Keep. |
| Make next time easier | No templates yet |
| Create a reusable master for trips, routines, or projects. | New lists here are saved as templates. |
| Nothing tucked away | No archived lists |
| Archived lists stay here until you restore them. | Remove; the empty heading already explains the state. |
| A little space for your next list | No lists yet, but distinguish a category-filtered empty state (“No lists in this category”) from an empty library. |
| Groceries, gift ideas, weekend plans. Start with what’s on your mind. | Remove; the existing New list action is the next step. |
| Items & notes included | Kept along with warming and unavailable status. |
| Text matches · smart search is warming up / unavailable | Keep meaningful limitations; use “Text matches · Smart search is starting” / “Text matches · Smart search unavailable.” Do not imply all results are available during degraded search. |

### List details and editors

| Existing | Recommendation |
| --- | --- |
| Your list (top bar) | List; the list title remains in the detail content. |
| The little things | Items |
| Add what you want to remember, one little thing at a time. | Remove; No items yet and Add item already describe the state and next step. |
| Nothing here yet | No items in this section |
| A new little plan | New list |
| Give it a name. Make a little space. | Remove. |
| One little thing | Add item |
| A little more order | Add section |
| Group related things together. | Remove. |
| Find a home for it | New category |
| Categories organize your library. They’re personal to you. | Categories are personal to you. Keep because shared list categories are personal. |
| Bright idea (icon choice) | Idea |
| A reusable master for next time | Create new lists from this template. |
| Template master | Template |
| Start again | Reset checkmarks (action and dialog title: “Reset checkmarks?”). Preserve both shared and private consequences. |
| Keep that thought / Save shared text to a list. | Add shared text / Save to a list. |
| Create a list in your library first, then share this thought again. | Create a list first, then share the text again. |
| Repeat labels don’t reset lists automatically. | Keep the full explanation at the repeat-label choice. Hide the footer for Manual; for Daily or Weekly, show the label and “Does not reset automatically.” This wording also applies to notes lists, which have no checkmarks. |
| Owner manages type and template settings | Keep for shared non-owner; it explains disabled or unavailable controls. |
| Delete, remove, archive, leave, copy, template clone dialogs | Keep the explicit consequences: affected people, permanence, retained items, need for a new invitation, and private copy behavior. Do not shorten them to generic “Are you sure?” prompts. |

### Settings, account and backups

| Existing | Recommendation |
| --- | --- |
| Make yourself at home | Settings |
| A few things, just for you. | Remove. |
| Your account | Account |
| Private library on gather.takeya.ninja | Remove if the signed-in username and list sharing state are already clear; never imply shared content is private. |
| A space that feels like you | Remove under Appearance. |
| Keep a copy | Backups |
| A fresh start | Sample lists |
| A little order. A little more space. | Remove. |
| Version … · Android 14+ | Keep as a quiet version detail if useful for support. |
| A connection is required. Changes refresh when you return and every 30 seconds. | Remove this technical footer; connection loss and Retry are already presented when needed. |
| A new password | Change password |
| Changing it signs out your other devices. Your lists and sharing stay. | Keep. |
| The new passwords don’t match yet. | Passwords don’t match. |
| Import backup copies? and its explanation | Keep the title and explicit private-copy, existing-content, and excluded-account/access consequences. |
| Backups include owned lists, categories, notes, and checkmarks. Joined lists, accounts, and sharing access are excluded. | Keep; it determines whether export is suitable. |
| Remove sample lists? and its explanation | Keep the permanent removal and preservation of own lists. |
| Password success, sign-out, backup success/failure, unknown mutation outcome | Keep specific success and recovery notices. Do not remove the warning to refresh after an uncertain write or imply the operation failed. |

### Invitations and sharing

| Existing | Recommendation |
| --- | --- |
| A little better together | Share list |
| Item edits and checkmarks are shared. Favorites, categories, and archive stay personal. | Keep. |
| Your invitation is ready | Invitation ready |
| Share it with someone you trust to edit this list. | Keep; possession conveys editing access. |
| Each link works once and expires after seven days. Making a new link stops the previous pending one. | Keep; these are important link limits. |
| Opening this invitation hasn’t joined the list. | Keep the preview/join distinction if the invitation presentation could otherwise imply membership. |
| Joining as [username]… | Keep account identity and shared vs personal consequences. |
| Replace / revoke invitation, remove member, leave list | Keep their consequences, including existing members retaining access and edits remaining. |
| Try again | Keep on failed sharing load. |

## Verification before calling the polish complete

Review sign-in, register, filtered and unfiltered empty library, search loading/degraded/no-result states, shared and private list detail, every editor, invitation preview/share/replace/revoke/leave, password, import/export and destructive confirmations. Check long names, large font scale, light and dark themes, TalkBack names and state, Android back, 48 dp touch targets, offline retry and uncertain-write wording on a real device or representative emulator. Confirm the copy matches the actual server semantics rather than assuming it from a label. No visual or accessibility testing is claimed by this research document.

Implementation testing references: [Compose animation testing](https://developer.android.com/develop/ui/compose/animation/testing), [Compose test synchronization](https://developer.android.com/develop/ui/compose/testing/synchronization), and [MotionDurationScale](https://developer.android.com/reference/kotlin/androidx/compose/ui/MotionDurationScale). Test reduced and scaled motion using APIs supported by the pinned Compose version; zero motion scale can complete on the next frame, while a user-selected scale above one extends durations intentionally.

## Design tradeoffs and rationale

[Nielsen Norman Group’s aesthetic and minimalist design heuristic](https://www.nngroup.com/articles/aesthetic-minimalist-design/) distinguishes removing low-value noise from removing needed elements: a visually sparse screen can still fail if its purpose or controls are unclear. Visual appeal affects perception, but extra decoration competes with the information people need. Gather should keep distinct primary controls and helpful empty-state direction while removing slogans and repetitive description.

[Apple writing guidance](https://developer.apple.com/design/human-interface-guidelines/writing) emphasizes useful next steps in empty states and actionable field errors; [GOV.UK error guidance](https://design-system.service.gov.uk/components/error-message/) also separates user-correctable validation errors from service problems and recommends retaining entered information. Therefore, an uncertain write needs a refresh instruction even if it adds a sentence, and invitation and backup consequences belong at the decision. Place less frequent recovery details behind a clearly named action where they can be found when needed, following [Apple’s layout guidance](https://developer.apple.com/design/human-interface-guidelines/layout) on progressive disclosure; do not hide immediate risks.

[Android’s layout guidance](https://developer.android.com/design/ui/mobile/guides/layout-and-content/content-structure) describes both implicit groups formed by whitespace and explicit groups formed by cards or dividers. Use those deliberately rather than removing all boundaries; [Android color guidance](https://developer.android.com/design/ui/mobile/guides/styles/color) preserves recognizable primary actions and sufficient contrast. [Apple’s motion guidance](https://developer.apple.com/design/human-interface-guidelines/motion) recommends purposeful, brief feedback and avoiding excessive repeated animations or blocking interaction. Motion is supportive, not the sole carrier of state, and device animation preferences must be honored and tested on Android.




