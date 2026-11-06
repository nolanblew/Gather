# Installing Gather

Gather can open as its own app on supported browsers and devices. Use the HTTPS site; installation and the service worker do not work on the plain HTTP LAN address. Localhost remains available for development checks.

After sign-in, supported browsers can show a small suggestion below the collection heading after ten seconds. It never opens a browser install prompt automatically. **Install Gather** opens the browser prompt only when clicked. **Dismiss** postpones suggestions for 30 days; dismissing the browser prompt does the same. A successful install suppresses future suggestions in that browser profile. Suggestions are hidden while a list or dialog is open.

**Settings & backup → Install Gather** remains available after a suggestion is dismissed. When the browser offers a native install prompt, the Settings button can open it. Otherwise Settings explains where to find the browser's installation action. Support varies by browser and device.

On iPhone and iPad Safari, choose **How to install** for instructions: open the page menu, tap **Share → Add to Home Screen**, enable **Open as Web App** if shown, then tap **Add**. Other iOS browsers may also offer Add to Home Screen through their share menu. The automatic iOS suggestion is limited to Safari so its instructions match the current browser.

Gather hides installation UI when launched in standalone mode, including the older iOS `navigator.standalone` signal. A normal browser tab cannot reliably inspect whether Gather is already installed elsewhere. The `appinstalled` event and local preference improve suppression but are not an operating-system app inventory. Clearing browser storage also clears the preference. If storage is unavailable, dismissal still works for the current page session.

## Connection and privacy

Installation is a shortcut to the same server account and lists. An internet connection is required to open lists and save changes. There is no offline editing or queued sync.

The production service worker stores exactly one public file, `/offline.html`, in Cache Storage. Navigation goes to the network with HTTP caching disabled. If the network fails, a static reconnect page offers **Retry Gather**. It does not replace server HTTP errors. API requests, exports, sessions, list data, authenticated app HTML and application bundles are never put in the service-worker cache. Public offline-page installation fetches omit credentials and reject redirects.

Registration happens in production builds on secure contexts only. The development Vite server does not register a worker. The server serves `/sw.js` as JavaScript with `Cache-Control: no-cache`, and serves the manifest with its proper MIME type and revalidation. Existing CSP permits the same-origin worker and assets.

Updates use the browser's normal waiting-worker lifecycle. There is no forced activation, `skipWaiting`, automatic reload or draft interruption. The first page that registers the worker is controlled after a subsequent navigation or app reopen. On updates, an old worker can remain active until its open pages are closed. Bump the `gather-public-offline-v1` cache version in `public/sw.js` when changing cached offline-page content; activation deletes only older caches with that prefix.

## Assets and verification

`public/manifest.webmanifest` defines the stable root app ID, start URL, scope and standalone display. It includes 192px and 512px PNG icons and a separate opaque 512px maskable icon. `index.html` includes a 180px Apple touch icon. These are rasterizations of the existing `public/gather.svg`, with extra maskable safe-area padding.

Regenerate the committed icons with Sharp installed in a tooling environment:

```powershell
node scripts/generate-pwa-icons.mjs
# Or supply an existing absolute path to the Sharp module:
node scripts/generate-pwa-icons.mjs 'C:\path\to\node_modules\sharp'
```

Sharp is needed only for asset regeneration; it is not a build or runtime dependency.

`node --test tests/pwa.test.js` checks preference expiry/acceptance, current standalone and Safari detection, actual worker event handlers with network/cache fixtures, credential-free public caching, API bypass, network-failure fallback, HTTP-error preservation, scoped cache cleanup, server headers and PNG dimensions. The complete test suite, type check and production build also apply.

For browser checks, serve a production build over localhost or HTTPS. Check the manifest/installability panel, capture a real `beforeinstallprompt`, verify the suggestion after ten seconds and click Install deliberately. Verify dismissal across reloads, Settings access, the absence of the suggestion during editing, and iOS instructions. Reopen once so the service worker controls the page, then stop the test server and navigate to verify the reconnect page. Inspect Cache Storage: only the public offline page should be present. Restoring the server and selecting Retry should return to Gather. Browser network emulation can affect the page and worker differently; a stopped local fixture server proves the fallback path.

Sources: [MDN installability](https://developer.mozilla.org/en-US/docs/Web/Progressive_web_apps/Guides/Making_PWAs_installable), [MDN custom install prompts](https://developer.mozilla.org/en-US/docs/Web/Progressive_web_apps/How_to/Trigger_install_prompt), [Apple iPhone web apps](https://support.apple.com/guide/iphone/open-as-web-app-iphea86e5236/ios), [WebKit iOS 16.4](https://webkit.org/blog/13966/webkit-features-in-safari-16-4/), [MDN Cache API](https://developer.mozilla.org/en-US/docs/Web/API/Cache).
