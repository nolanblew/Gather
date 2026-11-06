# Accounts and shared lists

This specification extends the existing Gather UX and v2 requirements. The PM and UX review agreed to keep the existing library and focused editor, with private lists by default and explicit invitations for individual lists. There is no separate household/workspace switcher, public reading link, email flow, or social network.

## Accounts and private space

The existing warm sign-in card provides labelled Sign in / Create account choices. Both use username and password; registration additionally confirms the password and displays the existing password requirements. Do not request an email address when no email verification or recovery service exists. Explain that password recovery is handled by the server administrator.

Registration creates an empty personal library with starter categories and no sample lists. Copy: “Keep your lists private. Invite people to the ones you share.” Existing account data remains with its original account during migration. Public registration never claims existing unowned data. Retired one-owner setup language and controls are removed.

Settings identifies the current username. The desktop sidebar also identifies the signed-in person. Signing out, session expiry, and a different account signing in in another tab immediately discard the prior account's rendered workspace and search results. Account changes clear list/query/category navigation from the prior account. Fetches from a discarded session cannot repopulate the new account's view.

## Private and shared lists

A new list says “Your new list is private. You can invite people after creating it.” The focused editor has a short access line and a visible labelled Share button, plus the existing list menu. The access line says “Private · Only you have access” before joining and “Shared · You own this list” or “Shared · Owned by {username}” once people share it. Library cards and editors carry a restrained Shared badge.

Sharing grants access to the same list. Item additions, edits, quantities, notes, sections, checking, and restarting affect everyone with access. Title, description, icon, and color also change for everyone. There is no promise of live typing, conflict-free simultaneous editing of one field, or an activity feed; existing granular server mutations and refreshed views remain the behavior.

Favorites, categories, archive state, and list ordering are personal, including for the owner. Edit details labels the field “Your category” and explains “Only you see this category.” Archive confirmation says “Archive this list for you?” and explains that others keep seeing it in their own library. Restore is labelled “Restore for me.”

The owner can manage access, delete the list, change Checklist/Notes, template status, and repeat rhythm. Editors can edit content and presentation and start again. They can make a private copy and leave. They do not see owner controls, and the server independently enforces these permissions. Shared restart confirmation explicitly says “This resets checks for everyone.” Shared deletion asks “Delete this list for everyone?” and states that everyone loses access.

## Invite one person

Share opens one accessible dialog containing people with access and a section headed “Invite one person.” Copy: “Send a link to someone you trust. They’ll sign in and choose to join. Each link can be used once and expires in 7 days.”

Create invite link produces a selectable read-only link and Copy link. Clipboard failure keeps the link selectable and says “Select and copy the invitation link above.” There is no automatic message sending. Only the creation response supplies the bearer token; closing and reopening the dialog never recovers a token from storage or the access endpoint.

One pending link exists per list. The owner can Create a new link or Stop invite link. Explain that a new link stops the previous pending one. Stopping a link says “People who already joined keep access.” Links expire after seven days and are single-use. This was chosen over reusable household links so a removed person cannot reuse an old accepted invitation.

Invitation URLs use `#invite=…`, never a query parameter. Capture and remove the fragment before navigating; retain the pending invitation in this tab's session storage while signing in, with an in-memory fallback when storage is unavailable. The authenticated preview and join send the token in POST JSON bodies so reverse-proxy URL logs do not contain it.

Signed-out invitation copy says “Sign in or create an account to review your list invitation.” Do not expose the title or owner before authentication. After sign-in, show the title, owner, “Joining as {username},” and a concise explanation of shared edits and personal organization. Join list is explicit; Not now leaves membership unchanged and clears the pending invitation. Existing members see “You already have access to this list” and Open list. Successful acceptance opens that same list.

Invalid, consumed, stopped, or expired invitations offer Try again and Back to your lists, with a request for a new owner link if needed. Network failures preserve a useful retry action. A failed preview or join clears any earlier successful preview rather than continuing to display a stale invitation title.

## People and leaving

People with access lists the owner and accepted editors, identifies the current person, and labels Owner/Can edit in text. Only the owner sees Remove controls and invitation creation. Editors use People & access and see Leave list.

Removal confirmation: “Remove {username}?” / “They’ll lose access to this list. Their changes will stay.” Leaving confirmation: “Leave this list?” / “You’ll lose access to this list. Your changes will stay. You can make a private copy first from List actions.” Both offer Cancel and a labelled destructive confirmation.

The dialog refreshes membership and pending-invite state on focus and every 15 seconds while visible, matching the library refresh model. Disregard stale reads during access mutations. Clear locally displayed invitation URLs when the pending invitation is consumed, stopped, expired, or replaced in another tab. Lost membership clears the open editor, stale ranked results, and any dialog holding a copy of that list. A failed action returning 404 immediately rechecks the account's accessible workspace.

## Templates, copies, and backups

Shared templates remain masters. Start list and Make a private copy create independent private lists owned by the current person; people on the source do not transfer. Copy confirmation states this explicitly. Existing fresh-copy behavior resets checks while preserving items, notes, sections, presentation, kind, and rhythm.

Export includes only lists the account owns, with owned shared lists represented as content snapshots. Joined lists are excluded; make a private copy to retain one. Accounts, memberships, and invitation links are excluded. Settings explains this next to Export JSON.

Import backup copies adds independent private copies with fresh list, section, item, and category identities; it keeps current content and access. Confirmation says “Import private copies?” and explains that shared access and accounts are not imported. Use Import private copies as the primary button and “Private backup copies imported” after success. No destructive replacement language remains in this flow. Invalid backup files change nothing.

## Responsive and accessible behavior

Keep the existing typography, semantic tokens, light/dark palettes, navigation, and quick-add spacing. Share is outside the tightly packed title actions and can wrap below the access explanation on a 360px phone. People rows allow long usernames to wrap without forcing the action offscreen. Dialogs scroll within the viewport; generated links use an input that can scroll internally.

Use labelled controls, 44px targets, keyboard focus, native dialog focus containment, Escape and return focus. Errors use alerts; successful copying/access changes use restrained status messages. Busy actions disable dialog controls and prevent repeat mutation. Preserve the established desktop/sidebar and phone focused-list hierarchy.

## Verification scope

Required runtime checks include independent account isolation; cross-tab account switching with an open editor and search; invitation capture through sign-in; explicit authenticated joining; single use, expiry, replacement and stopping; membership removal and leaving; personal favorite/category/archive state; owner-only controls; shared item edits and restart; independent template/private copies; owned-only export; additive import; inaccessible search results and stale editor/modal cleanup. Inspect desktop and 360px phone library, editor, sign-in and access dialogs in light and dark themes. Source and this specification do not substitute for browser evidence.
