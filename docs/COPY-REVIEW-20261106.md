# Copy review — November 6, 2026

GPT-6.1-Terra with High reasoning performed the initial copy pass. The orchestrator reviewed the changes against current sharing and backup behavior, made final edits, and approved the release.

The copy now uses direct task headings, collection-specific empty states, clearer examples in fields, and less repeated branding. Technical search wording was simplified. Final review shortened sharing explanations and notices while retaining who can join, single-use links, seven-day expiry, personal organization, and the consequences of removal, deletion, copying and importing.

Edited files: `src/auth.tsx`, `src/App.tsx`, `src/Sharing.tsx`, `src/AppearancePicker.tsx`, `src/InstallGather.tsx`. Authentication, sessions, permissions, backend behavior and 2FA scope are unchanged. The offline document is unchanged, so its cache version did not need to change.

Validation: TypeScript and production build passed; all 62 tests passed. Browser checks covered welcome, registration, new-list form, sharing at 360px, and desktop Settings/backup. No horizontal overflow was found. Screenshots: `output/playwright/copy-welcome-mobile.png`, `copy-sharing-mobile.png`, and `copy-settings-desktop.png`. The deployed HTTPS welcome page was checked after release.

Release: `gather:20261106-copy`; directory `/home/nolan/dockers/gather/releases/20261106-copy`; active volume `gather_lan_accounts_20261106_copy`. Full pre-release backup and previous volume `gather_lan_accounts_20261105_welcome` retained. Deployment checked 30 containers and left unrelated containers unchanged. Image archive SHA-256: `026186c8e8de657bafa19669290a3208b30d2fee8ae70daa702effd3975fb89c`.

Pre-edit source archive: `output/copy-before-20261106-000337.zip`.
