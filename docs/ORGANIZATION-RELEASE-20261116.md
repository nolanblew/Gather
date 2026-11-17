# Gather organization production release

Gather web and API were deployed on November 16, 2026, from feature commit `17ab012d5d59bfd33487847756addc40c54a25b1`. The [production site](https://gather.takeya.ninja/) served the new web build and the version 3 workspace API.

## Build provenance

| Component | Released value |
| --- | --- |
| Release | `20261116-organization` |
| Image | `gather:20261116-organization` |
| Image ID | `sha256:b3ae439409128568d36e67d429ba755ec212dd7819c4d9be98522f12aee10e22` |
| Base runtime | `gather:20261106-android`, Node `24.21.0` |
| Base runtime image ID | `sha256:b233417ef62e8695ec2910a5f7bb2232c6e66a91e943b7bf5c9e73cfebf23365` |
| Web entry bundle | `index-DNcaG-YC.js` |
| Web entry bundle SHA-256 | `e8d548233395bd7b069af79ed76a5469e5a61d9cfecdba6e77357bc0b9d06aa3` |

The host could not resolve the Docker registry. The release reused the deployed Linux runtime and its unchanged production dependencies. The package manifest hash matched in both builds. TypeScript and Vite passed on the final source. An image build with networking disabled replaced the web build and server files. An isolated smoke check verified the web entry bundle and the v3 API before the cutover.

## Data preservation and cutover

- Gather was stopped before creating the complete volume archive. The archive included every volume entry, including any SQLite WAL files present after shutdown. It is 23,951,360 bytes with mode `0600` and SHA-256 `57cfc5f3b4fc894366f187321295447c42be08b81c553908de743ebc24f1f232`.
- Gather now uses `gather_lan_accounts_20261116_organization`, restored from that archive and migrated in isolation. The previous volume `gather_lan_accounts_20261106_android_verified` remains available and was mounted read-only for backup and validation. The host stores the archive as `data-before-organization.tar` in the new release directory.
- Both SQLite integrity checks passed. The two workspace documents matched after canonicalizing object key order and excluding the workspace version. Arrays, IDs, text, descriptions, checks, positions, timestamps, and preferences all matched. Account and session records, nine owned lists, nine personal list preference records, membership and invitation tables, and the legacy singleton matched. The only expected database changes were the two version fields moving from 2 to 3 and the `workspace-v3` migration record.
- The 29 unrelated containers retained their container IDs, start times, and restart counts. Only the Gather service was recreated. The production release pointer moved after the verification passed.

## Production checks

- The Docker health check and LAN and HTTPS health endpoints passed.
- HTTPS served the home page and the exact new web bundle with `200` responses. The anonymous workspace request returned `401`.
- The public capabilities response advertised HTTP API version 1, workspace version 3, browser and native authentication, and `list-organization`, `numbered-lists`, and `guarded-order`.

Use the prior image and retained prior volume together if a rollback is necessary. After new production writes, reconcile them before switching back to the old volume. The v2 service cannot safely edit the v3 production volume.
