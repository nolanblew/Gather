import test from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import { readFile, mkdtemp, mkdir, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { INSTALL_SNOOZE_MS, isIosSafari, isStandalone, promotionAllowed } from '../src/installPolicy.ts';
import { startServer } from '../server/index.js';

test('install suggestion snooze expires after 30 days; acceptance suppresses suggestions', () => {
  const now = 1000;
  const preference = JSON.stringify({ dismissedUntil: now + INSTALL_SNOOZE_MS });
  assert.equal(INSTALL_SNOOZE_MS, 2592000000);
  assert.equal(promotionAllowed(preference, now), false);
  assert.equal(promotionAllowed(preference, now + INSTALL_SNOOZE_MS), true);
  assert.equal(promotionAllowed(JSON.stringify({ accepted: true }), now + INSTALL_SNOOZE_MS), false);
  for (const value of [null, 'invalid', 'null', '3', '{"dismissedUntil":"forever"}']) assert.equal(promotionAllowed(value, now), true);
});

test('current standalone launch and iOS Safari are recognized without assuming OS install inventory', () => {
  assert.equal(isStandalone(true, false), true);
  assert.equal(isStandalone(false, true), true);
  assert.equal(isStandalone(false, undefined), false);
  const safari = 'Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 Version/18.0 Mobile/15E148 Safari/604.1';
  assert.equal(isIosSafari(safari, 'iPhone', 5), true);
  assert.equal(isIosSafari(safari.replace('Version/18.0', 'CriOS/140.0'), 'iPhone', 5), false);
  const desktopSafari = 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15) AppleWebKit/605.1.15 Version/18.0 Safari/605.1.15';
  assert.equal(isIosSafari(desktopSafari, 'MacIntel', 5), true);
  assert.equal(isIosSafari(desktopSafari, 'MacIntel', 0), false);
});

async function workerFixture() {
  const events = new Map();
  const buckets = new Map();
  const writes = [];
  const calls = [];
  const fetchOptions = [];
  const network = { error: null, response: new Response('<html>public offline page</html>', { headers: { 'Content-Type': 'text/html' } }) };
  const caches = {
    async open(name) {
      if (!buckets.has(name)) buckets.set(name, new Map());
      return {
        put: async (key, response) => { writes.push(key); buckets.get(name).set(key, response); },
        match: async key => buckets.get(name).get(key),
      };
    },
    keys: async () => [...buckets.keys()],
    delete: async name => buckets.delete(name),
  };
  vm.runInNewContext(await readFile(new URL('../public/sw.js', import.meta.url), 'utf8'), {
    self: { location: { origin: 'https://gather.example' }, addEventListener: (type, listener) => events.set(type, listener) },
    caches, URL, Response,
    Request: class { constructor(url, options) { this.url = url; Object.assign(this, options); } },
    fetch: async (request, options) => { calls.push(request); fetchOptions.push(options); if (network.error) throw network.error; return network.response; },
  });
  const run = async (type, request) => {
    let result;
    events.get(type)({ request, waitUntil: value => { result = value; }, respondWith: value => { result = value; } });
    return result === undefined ? { handled: false } : { handled: true, response: await result };
  };
  return { run, buckets, writes, calls, fetchOptions, network };
}

test('worker caches only a credential-free public offline document; API and private content never enter caches', async () => {
  const f = await workerFixture();
  await f.run('install');
  assert.deepEqual(f.writes, ['/offline.html']);
  assert.equal(f.calls[0].credentials, 'omit');
  assert.equal(f.calls[0].redirect, 'error');
  const privateResponse = new Response('authenticated HTML', { headers: { 'Content-Type': 'text/html' } });
  f.network.response = privateResponse;
  const navigation = { url: 'https://gather.example/?list=private', mode: 'navigate', method: 'GET' };
  assert.equal((await f.run('fetch', navigation)).response, privateResponse);
  assert.equal(f.fetchOptions[1].cache, 'no-store');
  const requests = [
    { ...navigation, url: 'https://gather.example/api/auth/session' },
    { ...navigation, url: 'https://gather.example/api/workspace' },
    { ...navigation, url: 'https://gather.example/api' },
    { ...navigation, url: 'https://gather.example/api/export', mode: 'cors' },
    { ...navigation, url: 'https://gather.example/api/actions', method: 'POST' },
    { ...navigation, url: 'https://another.example/' },
    { ...navigation, url: 'https://gather.example/assets/app.js', mode: 'cors' },
  ];
  for (const request of requests) assert.equal((await f.run('fetch', request)).handled, false, request.url);
  assert.deepEqual(f.writes, ['/offline.html']);
  assert.equal(f.calls.length, 2);
  f.network.error = new Error('offline');
  const fallback = (await f.run('fetch', navigation)).response;
  assert.equal(await fallback.text(), '<html>public offline page</html>');
  assert.deepEqual(f.writes, ['/offline.html']);
});

test('worker preserves server HTTP errors and removes only its obsolete public cache', async () => {
  const f = await workerFixture();
  f.buckets.set('gather-public-offline-old', new Map());
  f.buckets.set('unrelated-app', new Map());
  await f.run('install');
  await f.run('activate');
  assert.equal(f.buckets.has('gather-public-offline-old'), false);
  assert.equal(f.buckets.has('unrelated-app'), true);
  f.network.response = new Response('Please sign in', { status: 401 });
  assert.equal((await f.run('fetch', { url: 'https://gather.example/', mode: 'navigate', method: 'GET' })).response.status, 401);
  const bad = await workerFixture();
  bad.network.response = new Response('redirected login', { headers: { 'Content-Type': 'application/json' } });
  await assert.rejects(bad.run('install'), /Offline page unavailable/);
  assert.deepEqual(bad.writes, []);
});

test('PWA files are publicly served with correct types and revalidation; API stays no-store', async t => {
  const dir = await mkdtemp(join(tmpdir(), 'gather-pwa-'));
  const distDir = join(dir, 'dist');
  await mkdir(distDir);
  for (const name of ['sw.js', 'offline.html', 'manifest.webmanifest']) await writeFile(join(distDir, name), await readFile(new URL(`../public/${name}`, import.meta.url)));
  const server = await startServer({ dataDir: dir, distDir, port: 0, searchEnv: { SEARCH_PROVIDER: 'fuzzy' }, authEnv: { GATHER_SETUP_TOKEN: 'test-pwa-token' } });
  t.after(async () => { await new Promise(resolve => server.close(resolve)); await rm(dir, { recursive: true, force: true }); });
  const base = `http://127.0.0.1:${server.address().port}`;
  for (const [name, mime] of [['sw.js', 'text/javascript'], ['offline.html', 'text/html'], ['manifest.webmanifest', 'application/manifest+json']]) {
    const response = await fetch(`${base}/${name}`);
    assert.equal(response.status, 200);
    assert.ok(response.headers.get('Content-Type').startsWith(mime));
    assert.equal(response.headers.get('Cache-Control'), 'no-cache');
    assert.match(response.headers.get('Content-Security-Policy'), /script-src 'self'/);
  }
  const api = await fetch(`${base}/api/workspace`);
  assert.equal(api.status, 401);
  assert.equal(api.headers.get('Cache-Control'), 'no-store');
});

test('manifest PNG icons and Apple touch icon have their declared dimensions', async () => {
  const manifest = JSON.parse(await readFile(new URL('../public/manifest.webmanifest', import.meta.url), 'utf8'));
  assert.equal(manifest.id, '/');
  assert.equal(manifest.scope, '/');
  assert.equal(manifest.start_url, '/');
  assert.equal(manifest.display, 'standalone');
  assert.ok(manifest.icons.some(icon => icon.purpose === 'maskable'));
  for (const icon of [...manifest.icons, { src: '/icons/apple-touch-icon.png', sizes: '180x180' }]) {
    const png = await readFile(new URL(`../public${icon.src}`, import.meta.url));
    assert.equal(png.subarray(0, 8).toString('hex'), '89504e470d0a1a0a');
    assert.equal(`${png.readUInt32BE(16)}x${png.readUInt32BE(20)}`, icon.sizes);
  }
});
