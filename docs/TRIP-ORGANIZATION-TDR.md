# Gather: simple organization — technical design

Final Phase 1 contract, 2026-11-16. Implemented and locally verified; production rollout remains separate.

[Product requirements](TRIP-ORGANIZATION-PRD.md), [canonical UI copy](ORGANIZATION-COPY.md), [progressive disclosure](ORGANIZATION-DISCLOSURE.md).

## Architecture and ownership

Extend existing React/TypeScript web, Kotlin/Compose Android and Node/SQLite API. Production persistence is createUserStore in server/store.mjs with canonical owner documents and separate membership/personal list preferences. Retain granular actions, full-workspace mutation responses, serialized client writes, polling/focus refresh and current authentication/origin enforcement. No new service, global directory, inferred list type or organization-enable flag.

## Workspace v3

Persist/export version 3; import/read versions 1–3. New list fields: numbered (default false), places (default []), recipients (default []). Place definition: id,name,kind (other default),region,countryCode; recipient definition: id,name. Item additions: availability {mode:unassigned|anywhere|selected, placeIds:[]}, recipientId nullable. Selected mode requires valid unique same-list IDs; other modes require an empty array. Existing item.note is displayed as Description; list.description is unchanged. Preserve newlines and existing 4,000-character limits.

Serialize defaults sparsely to avoid inflating near-5-MiB workspaces. IDs remain unique; reference checks and aggregate byte/cardinality validation occur transactionally. Enforced limits: 100 places/list, 200 recipients/list, 25 places/item, names/region 100 characters. No geography fields required for Phase 1. Legacy lists default to unnumbered/unassigned without altering checks, order, content, timestamps or membership.

Update every normalization/projection/export path; older scalar commands must preserve unmentioned metadata. Clone/template start remap child/dictionary IDs and references and reset checks; imports create private copies with remapped IDs and preserve checks. Backups include recipient labels but exclude sharing identities/tokens. Never emit extended v2 backups that old servers could silently strip.

## Commands

Retain /api/actions and API version 1; capabilities advertise workspaceVersion 3 and list-organization, numbered-lists, guarded-order. New clients must degrade safely against older servers.

- list.create/update accept numbered; only owner changes it.
- place.create/update/delete and recipient.create/update/delete operate on authorized same-list dictionaries. Creation returns createdPlaceId/createdRecipientId as ephemeral response metadata.
- item.create/update accept availability and recipientId. Atomic newPlaces definitions append to explicitly selected existing place IDs; newRecipient is exclusive with a non-null recipientId. Duplicate normalized names resolve only when unambiguous. New definitions are not written before item validation/CAS succeeds.
- item.create returns createdItemId for reliable focus when concurrent creation occurs.
- Changes to availability/recipientId require expectedAvailability/expectedRecipientId on updates. Normalize sets before comparison; mismatch returns 409 ASSIGNMENT_CHANGED, preserving the draft.
- Dictionary deletions confirm affected count and require expectedAssignmentToken; stale/missing token returns 409. Clear references atomically; final selected place removal becomes unassigned, never Anywhere.

Deletion token: lowercase hex SHA256 of UTF8 JSON.stringify([type,id,normalizedDefinition,affectedPairs]). Place definition key order id,name,kind,region,countryCode; recipient id,name. Sort pairs by lexical itemId, selected placeIds lexically; pairs contain [itemId,normalized availability] or [itemId,recipientId]. Generate on demand, not for every dictionary entry in every response. Share fixtures across clients/server.

## Shared order guard

Canonical traversal: unsectioned, then sections sorted by position, each group sorted by item position; stable source-array order breaks ties. Compute dense ordinal map before filtering.

Expose response-only orderToken, SHA256 of deterministic list ID/numbered/section IDs and item sequences. Exclude text, checks, metadata assignments and personal library order. Require expectedOrderToken inside the existing write transaction for numbering changes and numbered structural writes (item/section creation/deletion/moves or actual section membership changes). Missing returns ORDER_TOKEN_REQUIRED; mismatch ORDER_CHANGED, both 409. Scalar edits containing an unchanged sectionId remain nonstructural for old-client compatibility.

Guarded positional commands suffice; no CRDT or rebase. Return authoritative workspace; on conflict refresh while retaining editor drafts. Never automatically retry ambiguous mutations. Runtime tokens never enter backup files.

## Client interactions

Render only populated current-item details; empty description offers Add description. Add detail picker exposes additional choices, omitting already-present fields. List-local usage may promote a picker choice but never expand empty fields. Save all draft values/definitions atomically; picker Done returns to draft. Description is not reparsed or shortened automatically.

Filter availability derives from current-list assignments or active filters, not dictionary existence or account usage. Filter sheet holds local staged selections and applies them together only on Apply. Numbering is a separate shared action; list kind stays in List details, so shared writes and local filter application do not mix. Notes have no completion filtering. Place matching includes Anywhere, excludes unassigned unless selected, deduplicates IDs; dimensions combine with AND.

Maintain filters through refresh; reset deleted dictionary selections with explanation. Keep empty active filters recoverable. Use client/account-scoped local presentation state, clearing on logout/list removal; do not export it. Separate description preview from metadata when both populated; expansion must not truncate persisted text.

Numbered completed rows remain inline; filtered ordinals retain gaps. Full-order accessible movement includes checked siblings. Explicit Edit order removes narrowing filters and focuses the selected item. Original unnumbered behavior remains.

Android requires typed JSON arrays/objects (never generic stringification of placeIds), v3 model defaults, capability/error handling and draft-preserving 409 refresh. Both clients use only canonical user-facing copy. Existing list/item descriptions remain searchable; new metadata need not enter hosted semantic-search inputs.

## Verification and delivery

Backend tests: migration/sparse defaults, same-list references, authorization, CAS/order conflicts, inline atomic creation, delete cleanup, clone/import remapping, byte limits, old scalar clients and capability/version contracts.

Client tests: predicate/ordinal fixtures, draft payload types, description preservation, current-list disclosure, full-order movement, local Apply/Cancel and outdated responses. Run TypeScript check, Node suite, Vite build and Android compile/unit/lint. Inspect actual web/native UI when tooling is available; report any unperformed device/runtime checks explicitly.

No deployment is implied. A later release requires consistent SQLite backup and disposable migration verification, compatible server then clients, and live data-preservation checks. Do not roll a v2 server onto v3 data: its normalizer can discard metadata. Prefer forward-compatible fixes; restoring a backup requires reconciling post-backup edits.

## Exclusions

No GPS/catalog implementation, location permissions, assisted capture, model provider, arbitrary custom fields, offline queue, task ownership, global people/place directory or per-section numbering in Phase 1. Earlier expansive TDR sections and mixed View-settings behavior are superseded by this consolidated contract.
