# Palette release and LAN deployment — September 30, 2026

The real server is `nolan@192.168.68.68`, container `gather-lan-gather-1`, URL **http://192.168.68.68:48732/**. Docker health is healthy, with 0.5 CPU and 512 MiB limits and the existing single-thread/lazy local search configuration.

Before installation, inspected configured `HostConfig.PortBindings` for all 23 running and stopped containers, including stopped VPN/media containers. Port 48732 had no bindings. An actual socket bind to `0.0.0.0:48732` succeeded; live listeners were also inspected. The final publication binds only `192.168.68.68:48732`. No proxy, firewall, router, or other service settings changed.

Built and checked the image locally to avoid a CPU-intensive server build. Release tag `gather:20260930-palettes`; image transfer SHA-256 matched `ECD2F39234B255AFCDA6576EFBBD7F3A8359EAA688F316F646A7CB84CB5BE401`. Release directory `/home/nolan/dockers/gather/releases/20260930-palettes`, current symlink `/home/nolan/dockers/gather/current`, external volume `gather_lan_data`.

The complete initial v2 export (seven lists) was imported into the new persistent volume. Compared complete remote export to the local initial snapshot: identical values and ordering. Gather became reachable from the Windows computer over LAN and in the in-app browser. Deployment compared all unrelated container IDs, start times and restart counts before/after: unchanged. The local Docker installation was also updated; its data remains independent of the server's workspace.

Palette UI: Paper and Cloud light palettes; Midnight blue and Forest dark palettes. Dedicated Appearance dialog is accessible from the desktop sidebar and phone header, with mode buttons and preview cards. All four palette changes were exercised in the packaged browser app; Midnight persisted across reload with native dark color-scheme. Verified 360px header bounds: appearance icon right 218.7px, New list left 226.3px, so controls do not overlap. Picker fits without horizontal overflow. Screenshots: `output/playwright/lan-midnight-picker.jpg`, `lan-midnight-library.jpg`, `palettes-mobile.jpg`. Mode and palette preferences are browser-specific because this household app has no accounts.

TypeScript and production Docker build passed, all 25 automated backend/search tests passed, and standalone theme bootstrap assertions covered legacy preference, palette preview, System/media changes and cross-tab storage. The delivered server performed actual local semantic search: `present for mom` returned Birthday gift ideas first, with provider local/status ready. Observed process/container use around 119 MiB after search, within 512 MiB; CPU cap is enforced. Cold loading returned warming/fuzzy fallback first. No hosted AI call or new API key was used.

After a server-side container restart, health remained good and the complete export again matched the transferred initial snapshot exactly. Physical phone keyboard and Safari remain unverified; browser mobile tests use viewport emulation. This deployment is LAN access, with no public reverse-proxy route configured.

