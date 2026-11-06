/* Only this public document is cached. Lists, sessions, API responses and app
   HTML are always left to the network and are never written to Cache Storage. */
const CACHE_NAME = 'gather-public-offline-v1';
const OFFLINE_URL = '/offline.html';

self.addEventListener('install', event => {
  event.waitUntil((async () => {
    const response = await fetch(new Request(OFFLINE_URL, { cache: 'reload', credentials: 'omit', redirect: 'error' }));
    if (!response.ok || !response.headers.get('Content-Type')?.includes('text/html')) throw new Error('Offline page unavailable');
    const cache = await caches.open(CACHE_NAME);
    await cache.put(OFFLINE_URL, response);
  })());
});

self.addEventListener('activate', event => {
  event.waitUntil((async () => {
    const names = await caches.keys();
    await Promise.all(names.filter(name => name.startsWith('gather-public-offline-') && name !== CACHE_NAME).map(name => caches.delete(name)));
  })());
});

self.addEventListener('fetch', event => {
  const request = event.request;
  const url = new URL(request.url);
  if (request.method !== 'GET' || request.mode !== 'navigate' || url.origin !== self.location.origin || url.pathname === '/api' || url.pathname.startsWith('/api/')) return;
  event.respondWith(fetch(request, { cache: 'no-store' }).catch(async () => {
    const cache = await caches.open(CACHE_NAME);
    return (await cache.match(OFFLINE_URL)) || Response.error();
  }));
});
