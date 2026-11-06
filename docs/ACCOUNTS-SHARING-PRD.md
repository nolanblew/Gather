# Gather accounts and shared lists

This specification supersedes the single-household account and workspace restrictions in earlier PRDs. Product and UX debated link reuse, archive ownership and backup behavior before implementation. The agreed scope is individual accounts with private lists and explicit collaboration on one canonical list.

## Product promise

Create your own private lists, invite a person to one list, and work together without accidentally exposing the rest of your library. A shared list has one identity: item edits and checkmarks appear for everyone with access. Linking never merges two lists or synchronizes independent copies.

## Accounts and ownership

- Sign in and Create account are available on the public application. Accounts use a unique, case-insensitive username and password; no email is requested or implied to be verified. New accounts start with an empty library and their own default categories, rather than receiving another person's sample content.
- Every list has exactly one owner. New lists and imported or duplicated copies belong to the creating account and are private until explicitly shared. User identity comes from the authenticated session, never from a client-supplied owner ID.
- A signed-in account sees only lists it owns or has explicitly joined. This boundary applies to workspace responses, individual records, mutations, search candidates and evidence, exports, caches and background refreshes. Knowing a list/item/category ID does not grant access.
- Password change revokes other sessions and keeps a rotated current session. Sign out clears the current account's data from the browser, including selected list, search results and drafts that could appear under the next account.
- With no email service, forgotten-password recovery is a documented operator action targeting one username. It changes credentials and revokes that account's sessions without deleting lists, memberships or other accounts. No self-service account deletion, ownership transfer, identity provider or email delivery is included.

## Shared state and personal organization

Shared content comprises title, description, icon, color, kind, cadence, template status, sections, item text/notes/quantities/order and checklist completion. An editor can change ordinary content; only the owner changes kind, cadence or template status.

Favorite, list ordering, category selection and archive state belong to each person. Categories themselves are account-private. Changing one of these properties never reorganizes a collaborator's library. A newly joined list starts active, unfavorited and uncategorized for that person. Archive means "Archive for me": the canonical list and collaborators' active views remain intact. Restoring affects only that person's view. A personal category is never added to another person's search evidence.

| Action | Owner | Editor |
| --- | --- | --- |
| Read, search and edit list content/items/sections | Yes | Yes |
| Check/uncheck or Start again on a working checklist | Yes | Yes |
| Favorite, categorize, order, archive or restore for self | Yes | Yes |
| Duplicate / Make a private copy / Start list from template | Yes | Yes |
| Change kind, cadence or template status | Yes | No |
| Invite, inspect invitation status, revoke invitation, remove people | Yes | No |
| See accepted people and owner | Yes | Yes |
| Delete canonical list for everyone | Yes, confirmed | No |
| Leave a joined list | Not applicable | Yes, confirmed |

Start again retains existing content and adds "This resets checks for everyone" to shared-list confirmation. Ordinary item deletion is a collaborative content edit. Only owner-level list deletion removes the whole list for everyone. Owner access cannot be removed and owners cannot leave their own list.

## Invitation contract

- The owner uses Share, then **Invite one person**. Generate a cryptographically random invitation link that expires after seven days and can be accepted by one account. Exactly one pending invitation is active per list; generating a new one revokes the previous pending link. Existing members keep access when a pending link is replaced or stopped.
- Explain before copying: "Each link can be used once. It expires in 7 days." Anyone receiving the link can redeem it after signing in; it is not bound to an email or username. No public searchable user directory is needed.
- Opening a link never adds membership. Signed-out visitors see a generic invitation entry and can sign in or create an account. Preserve the invitation through that flow without exposing list content.
- Once signed in, show list title, owner username and the current signed-in account, with **Join list** and Cancel. Do not disclose items, descriptions or member lists before acceptance. Acceptance adds the same canonical list to the recipient's library and opens it.
- Acceptance is atomic with single-use consumption. Concurrent attempts by two accounts yield exactly one new member. Retrying with the successful account is idempotent and opens the joined list; another account sees a generic used/unavailable state. An existing member or the owner opening an invitation opens the existing list without creating duplicates or consuming a still-pending invitation.
- Revoked, expired, used-by-someone-else and deleted-list invitations offer a calm unavailable message and a route back to the library; they expose no private list content. Expiry/revocation is enforced when accepting, including a page left open past expiry.
- Stop invitation invalidates pending access only. Remove person and Leave list revoke membership immediately; prior edits remain on the canonical list. A consumed invitation cannot be reused to regain access. A deliberately fresh invitation can grant access again.
- Invitation secrets are excluded from backups, ordinary workspace payloads, search, logs and account records sent to browsers. Put the link secret in the URL fragment, remove it from the address bar during entry, and submit preview/join through authenticated JSON POST bodies. Do not put invitation secrets in API query strings. Store only token digests server-side. Membership does not expire when its invitation expires.

## Templates and independent copies

Shared templates are canonical shared masters. Editing their content affects the master for all members. **Start list** always creates a private, independent working copy owned by the person starting it, with fresh list/section/item IDs and all checks reset. Copying an ordinary list follows the same privacy rule. Copying never imports the source's members or invitation. Preserve kind, cadence and content; use the person's own category only where valid.

The copy flow states "Creates a private copy. People on this template won't be added." A person can explicitly share that new copy afterward. Copies remain accessible after leaving or losing source access; source changes never flow into them.

## Backup and import

The old "restore the entire workspace" operation is replaced by **Import backup copy**.

- Export includes the current person's owned lists, including owned shared lists as content snapshots, personal categories, personal organization and all saved content/checkmarks. It excludes lists joined from another owner, accounts, passwords, sessions, membership identities and invitation secrets. Explain that export contains "Lists you own"; offer Make a private copy for a joined list the person wants to retain.
- Import validates the entire document first and then atomically adds private copies owned by the current account. It never replaces existing lists, edits another person's canonical data or reinstates sharing.
- Remap every list, section, item and category ID and all associated references, even on repeated import. Preserve titles, ordering, notes, template/kind/cadence metadata and completion history. Unlike ordinary duplication, backup import preserves checked flags.
- Existing supported v1/v2 backup content remains importable using established migration defaults. Unsupported, malformed or over-limit documents change nothing. Keep size/count bounds and truthful validation errors.
- Imported copies remain private even when the original exported list was shared. Appearance remains device-local. Infrastructure backup of the full durable volume is the method for recovering accounts and live sharing relationships.

## Recovery and concurrent behavior

Archiving is reversible for the current person. Permanent owner deletion confirms "Delete this list for everyone?" Removing a person confirms lost access and retained edits. Leaving states "You'll lose access to this list. Your changes will stay."

After removal, leaving or deletion, stale tabs cannot continue to mutate or retrieve the list. Refresh drops inaccessible content, closes the editor safely and explains that access is unavailable. Search requests issued before access changed must not reintroduce private results after refresh. Preserve an unsent draft only while it remains bound to its original account and avoid showing it in another account.

Granular transactional commands remain required: unrelated concurrent additions/checks survive. All role checks occur on the server at mutation time; a loaded editor or invitation page is not authorization. Cross-list section/item references, forged account IDs and category IDs owned by another account reject without partial writes.

## Acceptance evidence

1. Create accounts A and B in isolated browser sessions. Each starts empty with independent categories; A's private title, item note, categories and search results never appear to B or to an unauthenticated request.
2. A invites B. B signs in through the link, explicitly joins, and sees the same list ID. A and B independently add and check items; both changes persist after polling, reload and server restart. Neither person's unrelated lists become visible.
3. A shares a template. B starts it and receives an unchecked, private copy with fresh IDs. Editing/checking either copy or master does not alter the other. Leaving/removal from the source leaves the private copy intact.
4. A and B independently favorite, categorize and archive the shared list. Each sees only their own organization; archiving does not hide the list from the other. B cannot change kind/cadence/template status, delete the canonical list or manage invitations/members through either UI or a forged API call.
5. Generate, replace, stop, expire and concurrently redeem invitations. Only the intended active unexpired link accepts one account; successful retries are idempotent. Existing members open the same list. Anonymous and nonaccepted visitors receive no private list items.
6. Remove B, then attempt workspace reads, item mutations, section moves, cloning, search, export and stale invitation acceptance from B's existing session. None returns or changes source content. Leaving has the same boundary. A can later explicitly reinvite B.
7. Owner deletion removes source access from all members and invalidates invitations while preserving independent copies. All destructive confirmations are clear and Cancel changes nothing.
8. Export A excludes joined lists and all sharing/authentication secrets. Import twice creates two sets of private copies with remapped IDs and exact saved checkmarks, without replacing live shared content. Invalid import is atomic. Legacy backups import correctly.
9. Password rotation, logout, expiry and targeted operator recovery preserve account boundaries and durable lists; a subsequent account on the same browser never sees previous-account content. Server restart retains accounts, memberships, personal organization and accepted access.
10. Verify actual desktop and 360px phone flows in light and dark modes: account entry, invitation return path, Share/People, Join, personal archive, removal/leave, private copy and backup wording. Keep keyboard labels, focus behavior, visible errors and 44px practical touch targets. Save actual runtime screenshots; prototype images alone are not evidence.
11. Required automated checks cover cross-account authorization and invite races, not only happy paths. Run TypeScript/build/server tests and the existing list/template/search/PWA regressions. Public health/assets remain accessible, private APIs require sessions, and PWA caching never stores authenticated list content.

## Delivery boundaries

The user permits wiping prototype server data for this transition, but the implementation preserves the singleton owner's existing account, sessions and exact content through an explicit migration. Deploy against a cloned persistent volume, retaining the original and a recoverable infrastructure backup while preserving unrelated services. Apply any necessary fallback reset only to Gather's explicitly identified prototype data. Do not infer permission to erase unrelated databases or future user data.

No anonymous shared-list view, read-only role, shared household/workspace hierarchy, email invitations, notifications, offline collaboration, live cursors, automatic schedules, account deletion or owner transfer is included. Polling/focus refresh and durable server-authoritative collaboration remain the delivery model.
