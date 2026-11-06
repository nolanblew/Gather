# Gather for Android: UX copy

Copy proposal grounded in README, API.md, the current web authentication, and ACCOUNTS-SHARING-UX.md. The account and sharing specification supersedes the older replacement-restore wording in UX.md. Braces denote values supplied at runtime, not literal text. Show counts with correct singular/plural; retain full names for accessibility. Use sentence case. Do not expose internal errors, credentials, invitation tokens, or raw server responses.

## Voice and presentation

Gather is a calm, useful notebook. Use direct verbs and short explanations; avoid cheerleading and jargon. Keep labels visible rather than relying on placeholder text. Show an error next to the field or action it concerns, and preserve entered text when a recoverable request fails. Announce errors and significant successes accessibly. Do not claim an action succeeded until the server confirms it. Display only actions the current role can perform; server authorization remains authoritative.

## Welcome and account

| Moment | Title / label | Body or feedback | Actions |
| --- | --- | --- | --- |
| Welcome | Gather | A little order. A little more space. Private to you. Shared when you choose. | Sign in; Create account |
| Invitation while signed out | List invitation | Sign in or create an account to review your list invitation. | Sign in; Create account |
| Sign in | Welcome back | Sign in to see your lists. | Sign in; Create account |
| Create account | Create your account | Keep your lists private. Invite people to the ones you share. | Create account; Sign in |
| Fields | Username; Password; Confirm password | Password: At least 6 characters and any two of uppercase, lowercase, numbers, or symbols. | Show password; Hide password |
| Recovery help | Forgot your password? | Contact your server administrator to reset it. Your lists and sharing relationships will stay with your account. | Back to sign in |
| Missing credentials | Check your details | Enter your username and password. | — |
| Invalid password | Check your password | Use at least 6 characters and any two of uppercase, lowercase, numbers, or symbols. | — |
| Password mismatch | Passwords don't match | Check both entries and try again. | — |
| Duplicate username | Username unavailable | Choose another username. | — |
| Failed sign-in | Couldn't sign in | Check your username and password and try again. | Try again |
| Too many attempts | Please wait | Too many attempts. Wait a little, then try again. | — |
| Session expired | Sign in again | Your session has ended. Sign in to continue. | Sign in |
| Sign out | Sign out? | You'll need to sign in again to access your lists on this device. | Cancel; Sign out |
| Account settings | Account | Signed in as {username}. | Change password; Sign out |
| Change password | Change password | Changing your password signs out your other devices. | Change password; Cancel |
| Password fields | Current password; New password; Confirm new password | Use the same password requirements shown above. | — |
| Password changed | Password changed | Other devices have been signed out. | Done |
| Password change failed | Couldn't change password | Check your current password and try again. | Try again |

Loading labels: “Signing in…”, “Creating account…”, “Signing out…”, “Changing password…”. Passwords can be up to 128 characters. Never suggest email recovery or a setup key. Keep each account's displayed content isolated: clear the previous account's library, search, editor and navigation on sign-out, expiry or account change, and ignore stale responses.

## Library and finding lists

| Moment | Title / label | Body or feedback | Actions |
| --- | --- | --- | --- |
| Main navigation | Library; Templates; More | More contains Favorites, Archive, categories, and Settings & backup. | — |
| Library empty | A little space for your next list | Create a list for whatever is on your mind. | New list |
| Loading / failure | Loading your lists… / Couldn't load your lists | Check your connection and try again. | Retry |
| Search | Search lists and items | Search within this view, including list details and items. | Clear search |
| Search scope | Entire workspace | Includes templates and archived lists. | Search |
| No results | No lists found | Try a different search or clear your filters. | Clear search |
| Search unavailable | Search is taking a little longer | Text matches are still available. | — |
| Favorite empty | No favorites yet | Add a favorite to keep it close at hand. | Browse library |
| Category empty | No lists in {category} | Add a list here or choose another category. | New list; Clear filter |
| Cards | {done} of {total} done; No items yet; Shared; Sample | Use “{count} item” / “{count} items” for notes and templates rather than checklist progress. | Add to favorites; Remove from favorites; List actions |
| New list | New list | Your new list is private. You can invite people after creating it. | Create list; Cancel |
| New list fields | List name; Description; Your category; List type | Your category is visible only to you. | Checklist; Notes & ideas |
| Missing title | Enter a list name | — | — |
| Category management | Categories | Organize lists in a way that works for you. Categories are personal. | Add category; Edit; Delete |
| Delete category | Delete {category}? | Lists in this category will become uncategorized. The lists won't be deleted. | Cancel; Delete category |
| Archive empty | Nothing archived | Lists you archive will appear here. | Browse library |
| Archive | Archive this list for you? | You can restore it later. Others can still see it in their library. | Cancel; Archive list |
| Restore | Restore for me | This list will return to your library. | Restore for me |
| Delete owned list | Delete this list? | This permanently deletes the list and its items. This can't be undone. | Cancel; Delete list |
| Delete shared owned list | Delete this list for everyone? | Everyone will lose access. The list and its items will be permanently deleted. This can't be undone. | Cancel; Delete for everyone |

Favorites, categories, ordering and archive state are personal. Archive is recoverable; deletion by an owner removes the canonical list. Editors cannot delete it. Preserve the selected library view, search and category when returning from an editor where practical.

## List and item editor

| Moment | Title / label | Body or feedback | Actions |
| --- | --- | --- | --- |
| List access | Private · Only you have access / Shared · You own this list / Shared · Owned by {username} | — | Share / People & access |
| Empty list | No items yet | Add an item to get started. | Add item |
| Quick add | Add an item | Enter an item before adding it. | Add |
| List controls | All items; Remaining; Filter items; Add section | — | List actions |
| Progress | {done} of {total} done; {remaining} remaining; Done ({count}) | Progress includes all items, even when filtered. | Show done; Hide done |
| Item details | Item details | Text; Quantity; Note; Section | Save; Cancel; Move up; Move down; Delete item |
| Delete item | Delete this item? | This can't be undone. | Cancel; Delete item |
| Add section | Add section | Section name | Add section; Cancel |
| Rename section | Rename section | Section name | Save; Cancel |
| Delete section | Remove this section? | Its items will move to the unsectioned list. | Cancel; Remove section |
| Kind | Checklist / Notes & ideas | Checklist: Check things off as you go. Notes & ideas: Keep a simple list of ideas. | Save |
| Change kind | Change list type? | Your items stay. Checkmarks are kept if you switch back to Checklist. | Cancel; Change type |
| Repeat label | Repeat | Manual; Daily; Weekly. Repeat labels don't reset lists automatically. | Save |
| Restart | Start again? | This clears all checkmarks so you can use the list again. Items and notes stay. | Cancel; Start again |
| Restart shared | Start again? | This clears checkmarks for everyone. Items and notes stay. | Cancel; Start again |
| Duplicate | Make a private copy | Creates a separate private list with unchecked items. People on the original won't have access to the copy. | Cancel; Make copy |
| Save status | Saving… / Saved / Couldn't save | Your changes haven't been saved. Check your connection and try again. | Retry |
| Required field | Enter {field} | — | — |

Only offer Start again for active checklist working lists with checked items; it is unavailable on notes, masters and archived lists. Only owners can change list kind, repeat label or template status. Editors can edit shared content, reset checks, and make a private copy. Preserve drafts on failures and prevent duplicate submissions while saving. Avoid claiming unsaved changes were kept across app closure unless that behavior is implemented.

## Templates

| Moment | Title / label | Body or feedback | Actions |
| --- | --- | --- | --- |
| Empty templates | No templates yet | Save a list as a template to reuse it later. | New template |
| Template card / editor | Template master | Use this as a starting point for a new list. | Start list; Edit template |
| Start template | Start a list from {title} | Creates a separate private list with unchecked items. The template stays as it is, and people on the template won't have access to the copy. | Start list; Cancel |
| Name copy | List name | A dated name is suggested; you can change it. | Start list |
| Created | List started | Your new list is ready. | Open list |

The copy preserves content, notes, quantities, sections, order and presentation, plus kind and repeat label. It has fresh IDs and no members; later edits to either list do not update the other. Do not describe Daily or Weekly as scheduled resets or reminders.

## Sharing and invitations

| Moment | Title / label | Body or feedback | Actions |
| --- | --- | --- | --- |
| Share owner | Share this list | People you invite can edit this list with you. Favorites, categories and archive are personal. | Invite one person; People with access |
| Create invitation | Invite one person | Send a link to someone you trust. They'll sign in and choose to join. Each link works once and expires in 7 days. | Create invite link |
| Pending invite | Invitation pending | A new link stops the previous pending link. | Create a new link; Stop invite link |
| Link created | Invitation link | Link ready to share. | Copy link |
| Clipboard failure | Couldn't copy link | Select and copy the invitation link. | — |
| Link stopped | Invite link stopped | People who already joined keep access. | Done |
| Invitation preview | Join {title}? | Invited by {owner}. Joining as {username}. You'll share edits and checkmarks; your favorites, categories and archive are personal. | Join list; Not now |
| Already a member | You already have access to this list | — | Open list; Back to your lists |
| Invalid invitation | This invitation isn't available | It may have expired, been used, or been stopped. Ask the owner for a new link. | Try again; Back to your lists |
| People | People with access | Owner; Can edit; You | Remove; Leave list |
| Remove editor | Remove {username}? | They'll lose access to this list. Their changes will stay. | Cancel; Remove person |
| Leave shared list | Leave this list? | You'll lose access to this list. Your changes will stay. You can make a private copy first from List actions. | Cancel; Leave list |
| Access lost | This list is no longer available | You may no longer have access, or the list may have been deleted. | Back to your lists |

Opening an invitation is not acceptance. Require authenticated preview and explicit Join list. Don't show the list title or owner before authentication. Link creation does not send a message; the user chooses how to share. Only owners manage invites and remove people. Editors can leave. Clear inaccessible content and stale previews when membership changes.

## Settings, backup and privacy

| Moment | Title / label | Body or feedback | Actions |
| --- | --- | --- | --- |
| Settings | Settings & backup | Account; Appearance; Search; Export backup; Import backup copies; Categories | — |
| Appearance | Appearance | Choose how Gather looks on this device. | System; Light; Dark |
| Export | Export backup | Export lists you own, including their notes and checkmarks. Joined lists aren't included; make a private copy first to keep one. Accounts, people and invite links aren't included. | Export JSON |
| Import | Import backup copies | Add private copies from a Gather JSON backup. Your current lists and sharing stay as they are. | Choose JSON backup |
| Import confirmation | Import private copies? | This adds separate private copies. Accounts and shared access aren't imported. Your existing lists won't be replaced. | Cancel; Import private copies |
| Import success | Private backup copies imported | Your existing lists and sharing are unchanged. | Done |
| Invalid backup | Couldn't import this backup | Check that this is a valid Gather backup and try again. Your lists haven't changed. | Choose another file |
| Backup too large | Backup is too large | Choose a Gather backup no larger than 5 MiB. Your lists haven't changed. | Choose another file |
| Export failed | Couldn't export backup | Check your connection and try again. | Retry |
| Local search privacy | Search | Smart search processes content on this server. | — |
| Hosted search privacy | Search | Hosted smart search sends queries and relevant list content to the configured provider. | — |
| Text-only search | Search | Text search is available. Smart search is disabled on this server. | — |
| Search warming/unavailable | Search | Smart search is starting or unavailable. Text matches remain available. | — |

A portable export is a content snapshot of owned lists, not an account or sharing backup. An import validates the complete document before adding copies; report unchanged data on rejection. Explain search privacy according to actual server status, never assume a provider.

## Connection and action failures

| Moment | Title | Body | Actions |
| --- | --- | --- | --- |
| Offline or server unreachable | Can't connect to Gather | Check your connection and try again. Gather needs a network connection to load and save lists. | Retry |
| Action failed | Couldn't complete that action | Check your connection and try again. | Retry; Cancel |
| Permission denied | You can't make this change | Your access may have changed. Refresh the list and try again. | Refresh; Back to your lists |
| Content limit | Couldn't save | Your workspace has reached its size limit. Remove content or shorten notes, then try again. | Review list |
| Unknown result after interruption | Couldn't confirm that change | Reconnect and check the list before trying again. | Refresh |

Do not promise offline edits, queued changes or automatic synchronization. An interrupted request can have an unknown outcome; refresh authoritative state before repeating a non-idempotent operation. Show a precise server validation error when safe and actionable; otherwise use the relevant general message. Busy labels should identify the action, such as “Adding…”, “Saving…”, “Creating link…” and “Importing…”.

## Experience review and implementation notes

The hierarchy is intentionally simple: account entry leads to Library; Templates and More remain predictable destinations; a focused editor gives list content and quick add priority, with Share and list actions clearly named. Personal organization and shared content need separate labels wherever the distinction changes the outcome. Destructive confirmations name who loses access and what survives. No copy implies a scheduler, email recovery, automatic invitation sending, replacement import, or offline sync.

Use native accessible labelled controls, sensible touch targets and scrollable dialogs. Keep quick add visible above the keyboard without covering the final item. Provide explicit move controls without requiring drag. Preserve valid input during retries, serialize mutations, and render authoritative server results. Treat all strings here as proposed product copy: verify actual Android support and role-dependent flows before displaying a promise or declaring the experience complete.
