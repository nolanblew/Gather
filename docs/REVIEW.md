# Gather independent review

Reviewed the product, UX and API specifications against the server, integration tests, React components, responsive styles, and Docker configuration on September 29, 2026. All concrete findings below are resolved in the reviewed source. No outstanding source-level blocker was found for the private household MVP.

## Findings resolved

| Priority | Finding | Resolution |
| --- | --- | --- |
| P2 | Valid mutations could grow the workspace beyond the request/import size limit, making its export impossible to restore. | `server/store.mjs` bounds serialized UTF-8 workspace size to 5 MiB before commit; failed growth rolls back. Tests cover rejection, recovery, restart and export restore. |
| P2 | Appending after restoring sparse positions could insert entries in the middle of the visible order. | Server append operations use maximum position plus one, including item section transfers and section deletion. Sparse-position tests pass. |
| P2 | The installed Vite proxy rewrote Host, causing valid development writes to fail Origin checks. | `vite.config.ts` explicitly preserves Host with `changeOrigin: false`; the integrator verified writes through the development proxy. |
| P2 | Typing another item during a pending add could lose the next draft when the first save cleared the input. | `src/App.tsx` disables quick add during a pending mutation. Dialog controls are disabled while saving; failed saves retain their state. |
| P2 | Dialogs lacked accessible names. | Native modal dialogs now reference their heading through `aria-labelledby`; native focus containment and Escape dismissal are retained. |
| P3 | List actions did not close with Escape. | The details menu closes and focuses its summary on Escape. |
| P2 | Concurrent list creation could open another client's newly created list. | Create/clone responses return exact `createdListId` metadata; the UI uses it. The metadata is excluded from persisted/exported workspace documents. |
| P2 | A phone safe-area inset could make bottom navigation overlap quick add. | `src/styles.css` shares a computed navigation-height variable between navigation and quick-add offset. |
| P2 | Many categories or a short desktop window could place Settings below an unscrollable sidebar. | Sidebar scrolls vertically and its child navigation blocks do not shrink. |

## Review conclusions and evidence

- SQLite WAL storage, full synchronous writes and `BEGIN IMMEDIATE` transactions make server data authoritative and preserve unrelated granular writes. Restart, seed-once behavior, atomic invalid-import rejection and concurrent item additions are covered by integration tests.
- Template clones allocate independent list, section and item IDs, preserve content and presentation, and reset completion. Export/import preserves exported workspace content and ordering. Input limits, parent references and unique IDs are validated before replacement.
- Loopback is the default native and Compose binding. Parameterized database writes, plain React text rendering, JSON write requirements, origin checks and bounded requests are appropriate for the documented private deployment. README explicitly requires authenticated HTTPS reverse proxy protection before public exposure.
- React source provides the specified library destinations, contextual item search, favorites, editable categories, optional sections, item details and ordering controls, confirmation for archive/delete/reset/restore, samples, and backup controls. Sequence guards prevent stale refresh responses from replacing newer mutation responses. Draft state survives polling and failed requests.
- Responsive source wraps long list/item text, provides a focused phone editor, visible keyboard focus, labelled controls, native modal dialogs, checklist bottom clearance and practical touch targets. Generated design references are distinct from actual running-app captures.
- The independent reviewer ran the final Node server suite: **8 tests passed, 0 failed**. The integrator owns actual browser and Docker acceptance checks and records them in `QA.md`.

## Verification limits

Source review and automated Chromium viewport checks do not establish physical phone soft-keyboard or Safari behavior. Actual device keyboard positioning, screen-reader behavior and complete assistive-technology coverage remain manual verification limits. This review does not claim a production deployment; deployment is outside the requested scope.

## V2 independent review

Reviewed `PRD-v2.md`, `UX-v2.md`, `SEARCH-RESEARCH.md`, the version-2 store and migration, search service/API, React theme/notes/reuse/search flows, and resource-limited Docker configuration. No outstanding source blocker remains for the current household workspace.

| Priority | Finding | Resolution |
| --- | --- | --- |
| P2 | Hosted ranking could omit or demote exact title/item results. | The exact lexical tier remains ahead of provider-ranked semantic candidates, including when the provider omits those IDs. |
| P2 | Hosted freshness checked timestamps, allowing stale category evidence or same-timestamp imports to survive. | Results require current list/category source hashes to match the original snapshot. Tests cover an in-flight category rename and replacement content with unchanged timestamps. |
| P2 | Local indexing continued across every supported chunk and retained every vector. | Demand indexing defaults to at most 64 chunks per request, batches at most eight, uses serialized one-thread inference, caps retained vectors at 4096, and disposes the model after idle time. The reviewer tested an eight-chunk request budget and a 128-vector cap. |
| P2 | Search accepted unnecessary 5 MiB request bodies. | Search has a separate 8 KiB body limit while backup limits remain unchanged. |
| P2 | Hosted candidates could truncate all item/note content behind a long title/description. | Representative fields receive individual snippet limits within each candidate's total budget. Full-note and large-workspace semantic recall remains deliberately bounded and is documented. |
| P2 | Invalid provider configuration could silently trigger local model work; hosted failure status remained ready. | Invalid modes use degraded fuzzy fallback without loading a model or calling the hosted provider. Hosted failure updates capability status. |
| P2 | Hosted concurrency alone did not bound distinct paid requests. | The server admits at most one hosted request at a time, at least one second apart, and at most ten per minute. Identical cached requests avoid provider work. |
| P2 | Candidate character limits did not bound UTF-8 payload size. | Snippets use byte limits: 70 bytes per representative field and 600 bytes per candidate, at most 40 candidates. Upstream responses are streamed with a 64 KiB cap. |
| P3 | The hosted endpoint was fixed despite the configured-provider contract. | An operator-selected HTTP(S) base URL is validated, excludes embedded credentials, and is not disclosed through status/errors. |

The reviewer independently ran the final complete suite: **25 tests passed, 0 failed**. Three additional acceptance tests in `tests/search-review.test.js` cover same-timestamp replacement during hosted inference, index/vector bounds, and reuse of persisted chunk vectors after closing and reopening the search service. Hosted tests use mocked responses; they establish adapter behavior, not live GPT-6 Luna entitlement or retrieval quality.

Migration preserves original version-1 IDs, whitespace, content, checked flags, timestamps, ordering, flags and category membership while adding checklist/manual defaults. Invalid migration rolls back without reseeding. Notes conversion preserves historical completion/cadence, rejects completion edits, and retains all items in the UI. Template clones inherit kind/cadence and remain independent. Restart appears only for eligible active checklists, requires confirmation and resets completion in place.

The external head theme bootstrap applies the effective appearance before React renders, updates native color-scheme, follows live System changes, and handles storage denial. Search preserves scopes/category filters in immediate browser fallback and server results, guards/aborts stale responses, refreshes after workspace changes, and uses actual stored evidence. Provider credentials remain server-side; local/fuzzy modes do not automatically switch to hosted requests.

The real integrated local-model fixture results and measured Windows/Linux container resource behavior belong in `SEARCH-RESEARCH.md` and the integrator's QA record. Fixture quality is useful evidence rather than a general-recall guarantee. Large collections may require repeated demand indexing and report partial/warming status. Hosted candidate coverage is limited to 40 lists and short snippets. Physical phone keyboard and comprehensive assistive-technology coverage remain the verification limits stated above.

A version-1 workspace already extremely close to the 5 MiB cap can fail migration if added version-2 metadata exceeds that same cap. Failure preserves the original database rather than removing content; the current seven-list household workspace is far below this boundary.
