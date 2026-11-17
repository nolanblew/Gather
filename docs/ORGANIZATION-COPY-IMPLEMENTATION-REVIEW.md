# Organization copy implementation review

Reviewed 2026-11-16 against ORGANIZATION-COPY.md, ORGANIZATION-DISCLOSURE.md, and TRIP-ORGANIZATION-PRD.md while implementation was in progress.

The primary filter and picker labels use the intended plain language on web and Android: **Not done**, **All items**, **For someone**, **Anywhere**, **No places set**, **Apply**, **Cancel**, **Done**, and **Save**. Keep **Done** for returning to the unsaved item draft and **Save** for committing it.

Corrections sent to implementation owners:

- Web: use “{count} without a place” for the unplanned-place count.
- Android: make Section discoverable in Add detail even when there are no sections, with a useful next step; align the empty-section label across platforms where practical.
- Server: replace user-visible technical availability errors with plain recovery instructions, such as “Choose places, Anywhere, or No places set.” and “Choose up to 25 places.”

Final integration verified these corrections: web uses the shorter count, Android exposes Section with an empty-state next step, and server availability errors use the suggested recovery wording. Existing unrelated copy was outside this review.
