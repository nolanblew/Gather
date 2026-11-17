# Organization implementation verification

Date: 2026-11-16. Phase 1 implemented and locally verified. No production deployment, release signing, commit or push performed.

## Automated checks

- Before implementation: 73 Node tests passed.
- Final server/web suite: 84 tests passed, including transactional v3 migration near the 5 MiB limit, permissions, guarded shared ordering, assignment conflicts, atomic draft definitions, clone/import remapping, backup preflight, hosted-search exclusion and filter/ordinal fixtures. Regression coverage includes 100 lists with places assigned on only two.
- TypeScript check and Vite production build passed.
- Android assembleDebug, testDebugUnitTest, lintDebug and assembleDebugAndroidTest passed. 18 unit tests (14 model, 4 window); lint has zero errors and eight existing warnings.
- Four connected Pixel 7 Pro test journeys passed: organization; existing lists/sharing/search/templates/archive/appearance; rotation with an unsaved draft; simulated hinge layouts with LTR/RTL navigation. The last two simulate adaptive conditions on Pixel, not physical Fold hardware.
- Whitespace diff check passed.

The first integrated Node run exposed unsupported TypeScript constructor syntax in the new test import; ApiError now uses explicit fields and the complete suite passes. An early native screenshot captured a sheet transition; evidence was retaken after the animation settled.

## Observed web behavior

Verified with the actual production bundle at desktop and 390 x 844 viewport sizes against a disposable local account:

- New list asks for its name; optional settings stay closed. Plain capture adds one item; its editor has no empty place/person/quantity fields.
- Multiline description survives place and person picker navigation, Save and reopening.
- Add detail omits populated fields. Creating a named place adds it directly to the draft; Done returns to the item. Cancel leaves no orphan definition.
- Places-only filters contain no people or completion controls. Cancel does not apply staged choices.
- A named city includes alternative-place and Anywhere items, excludes unassigned items, and intersects with a person filter.
- Numbered checked rows stay inline; filtering retains original ordinal gaps. Clear remains available with zero results.
- Offline Save shows reconnect guidance and preserves the description draft. Cancel followed by reload retains the previously saved description. Network emulation was restored afterward.
- Final narrow layout aligns checkboxes and numbers with item titles; modal focus starts in the item input.
- Independent web validation also covered duplicate-for-someone preserving prose and clearing completion, and deleting a filtered place while retaining the item's alternative place and showing the filter-reset explanation.

## Observed Android behavior

The native device tests exercised name-only creation, ordinary capture, canceled place creation, atomic description/place/person save, place-only disclosure and filter Cancel, combined filters, numbered inline checks and full-order movement. Existing app workflows and draft recreation also passed. Actual Add detail and places-only screens were visually inspected.

## Evidence and environment

Screenshots are under `output/organization-qa/`:

- `web-add-detail.jpg`, `web-places-filter.jpg`, `web-mobile-add-detail.jpg`, `web-mobile-numbered.jpg`.
- `android-organization-add-detail.png`, `android-organization-places-only-filter.png`, `android-organization-filters.png`, `android-organization-filtered-list.png`.

Node log: `output/organization-qa/node-tests.log`. Android unit and lint reports: `android/app/build/reports/`.

Node 24.22.1, JDK 17, configured Android SDK, disposable server bound to 127.0.0.1:48733, data under `output/organization-qa/data`. Android used the isolated `ninja.takeya.gather.debug` app with the local server override; the production app was not replaced. The phone was authorized by the user for USB debugging.

## Limits and release follow-up

GPS suggestions and assisted capture remain Phase 2. Editing requires connectivity; restart-offline and queued writes are not promised. Automated migration/backup fixtures are not a production migration: a later release must back up live SQLite data and update the server before clients. No fresh usability study or physical Fold test was performed. Prior image-review scores describe mocks, not measured scores for this implementation.
