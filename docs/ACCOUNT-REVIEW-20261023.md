# Account access review — 2026-10-23

Single owner account protects the existing shared household workspace. No 2FA, email service, public signup, new database server, or heavy authentication service was added.

## Implemented

- A server-held random setup key gates first account creation.
- Passwords use salted scrypt, N=16384, r=8, p=5, with one in-flight hash operation. This is a memory-constrained configuration listed in the [OWASP password-storage guidance](https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html#scrypt).
- Sessions use random 256-bit tokens, hashed at rest, with a 90-day absolute expiry. Production cookies use the `__Host-` prefix, Secure, HttpOnly, SameSite=Lax, and Path=/.
- Logout revokes the current session. Password change revokes all old sessions and rotates the current token. The account recovery CLI clears only account/session state and generates a new setup key; restart afterward.
- API authentication covers workspace reads, export, restore, mutations, and both search routes. Health remains public.
- UI gates workspace loading until authentication, handles session expiry, and propagates logout across browser tabs. No token goes into localStorage.
- The reverse proxy preserves Host. JSON-only writes plus origin/fetch-site checks reject browser cross-site requests; no CORS permissions are granted.

## Evidence

- 39 Node tests passed, including token hashing, cookie flags, expiry, throttling, durable sessions, setup races, password revocation, recovery preservation, and existing list/search behavior.
- TypeScript and Vite production build passed; Docker image built successfully.
- Real Edge browser with a disposable isolated account: sign-in, reload persistence, settings account controls, and logout clearing a second tab passed.
- Desktop sign-in captured in `output/publish/gather-sign-in.jpg`. Responsive viewport verified at 390 CSS pixels with no horizontal overflow. No physical phone keyboard test was performed.
- Local Docker and LAN Docker both upgraded. Workspace exports were byte-identical before/after each upgrade.
- LAN deployment helper verified 30 containers; the 29 unrelated containers were unchanged.
- Live LAN health returns 200; workspace, export, and search status return 401 without a session.
- Release image `gather:20261023-auth`; transfer SHA256 `8a9708523795bb36c81c243a54e495fbc0df303a70f11020bc32f93a9f13cb49`.

## Remaining activation

Published https://gather.takeya.ninja/ after user confirmation. DNS CNAME targets takeya.hopto.org. Nginx routes to 192.168.68.68:48732 with a validated Let's Encrypt certificate expiring January 21, 2027. HTTP returns 301 to HTTPS. All six private API routes return 401 without a session. Existing proxy rows were compared before/after and remain unchanged.

The owner must choose and submit their password in the browser; no production account was created by the agent. Production cookie settings require HTTPS. Account/session data lives in `account.sqlite` within the persistent volume and is intentionally excluded from portable list exports.

Password policy follow-up: minimum 6 characters with any two of uppercase, lowercase, numbers, or symbols. Enforced on setup/password change, with existing login credentials preserved. All six category pairings and single-category/short rejections tested; 10 authentication tests, TypeScript check and Docker build passed. Release gather:20261023-password deployed locally and on the LAN server; workspace preservation and unrelated-container checks passed. Public HTTPS bundle verified with updated guidance.
