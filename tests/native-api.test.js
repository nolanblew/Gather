import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { DatabaseSync } from 'node:sqlite';
import { createHash } from 'node:crypto';
import { startServer } from '../server/index.js';
import { resetUserPassword } from '../server/auth.mjs';

const password = 'Native-password-1';
const cookie = response => response.headers.get('set-cookie')?.split(';', 1)[0];
async function fixture(t) {
  const dir = await mkdtemp(join(tmpdir(), 'gather-native-'));
  let server, base;
  const start = async () => { server = await startServer({ dataDir: dir, port: 0, searchEnv: { SEARCH_PROVIDER: 'fuzzy' }, authEnv: {} }); base = `http://127.0.0.1:${server.address().port}`; };
  const stop = () => new Promise(resolve => server.close(resolve));
  await start();
  t.after(async () => { await stop(); await rm(dir, { recursive: true, force: true }); });
  const request = async (path, body, headers = {}) => {
    const response = await fetch(base + path, { method: body === undefined ? 'GET' : 'POST', headers: { ...(body === undefined ? {} : { 'Content-Type': 'application/json' }), ...headers }, ...(body === undefined ? {} : { body: JSON.stringify(body) }) });
    return { response, value: await response.json() };
  };
  const ok = async (path, body, headers) => { const r = await request(path, body, headers); assert.equal(r.response.status, 200, `${path}: ${JSON.stringify(r.value)}`); return r.value; };
  const register = async username => { const value = await ok('/api/native/auth/register', { username, password }); return { value, headers: { Authorization: `Bearer ${value.accessToken}` } }; };
  return { dir, request, ok, register, restart: async () => { await stop(); await start(); } };
}

test('native sessions are explicit, hashed, durable, and isolated from browser credentials', async t => {
  const f = await fixture(t), native = await f.request('/api/native/auth/register', { username: 'Alice', password });
  assert.equal(native.response.status, 200);
  assert.equal(native.response.headers.get('set-cookie'), null);
  assert.equal(native.response.headers.get('cache-control'), 'no-store');
  assert.equal(native.value.tokenType, 'Bearer');
  assert.match(native.value.accessToken, /^[A-Za-z0-9_-]{43}$/);
  assert.ok(Date.parse(native.value.expiresAt) > Date.now() + 89 * 86400000);
  const headers = { Authorization: `Bearer ${native.value.accessToken}` };
  const session = await f.ok('/api/native/auth/session', undefined, headers);
  assert.equal(session.userId, native.value.userId);
  assert.equal(session.accessToken, undefined);
  const browser = await f.request('/api/auth/login', { username: 'Alice', password });
  const browserCookie = cookie(browser.response);
  assert.equal(browser.value.accessToken, undefined);
  assert.ok(browserCookie);
  assert.equal((await f.ok('/api/auth/session', undefined, headers)).authenticated, false);
  assert.equal((await f.ok('/api/native/auth/session', undefined, { Cookie: browserCookie })).authenticated, false);
  assert.equal((await f.request('/api/workspace', undefined, { Cookie: `gather_session=${native.value.accessToken}` })).response.status, 401);
  assert.equal((await f.request('/api/workspace', undefined, { Authorization: `Bearer ${browserCookie.split('=')[1]}` })).response.status, 401);
  for (const Authorization of ['Basic abc', 'Bearer invalid', 'Bearer ' + 'x'.repeat(43), 'Bearer ' + native.value.accessToken + ',other']) {
    assert.equal((await f.request('/api/workspace', undefined, { Cookie: browserCookie, Authorization })).response.status, 401);
  }
  const db = new DatabaseSync(join(f.dir, 'account.sqlite'));
  const rows = db.prepare('SELECT token_hash,transport FROM sessions').all();
  db.close();
  assert.ok(rows.some(r => r.transport === 'bearer' && r.token_hash === createHash('sha256').update(native.value.accessToken).digest('hex')));
  assert.ok(rows.every(r => r.token_hash !== native.value.accessToken));
  await f.restart();
  assert.equal((await f.ok('/api/native/auth/session', undefined, headers)).authenticated, true);
  assert.equal((await f.ok('/api/auth/session', undefined, { Cookie: browserCookie })).authenticated, true);
  assert.equal((await f.ok('/api/capabilities')).apiVersion, 1);
});

test('native and web share authoritative lists, permissions, search, invitations, imports and exports', async t => {
  const f = await fixture(t), alice = await f.register('Alice'), bob = await f.register('Bob'), carol = await f.register('Carol');
  const web = await f.request('/api/auth/login', { username: 'Alice', password });
  const webHeaders = { Cookie: cookie(web.response) };
  const created = await f.ok('/api/actions', { type: 'list.create', payload: { title: 'Shared grocery list' } }, alice.headers);
  const listId = created.createdListId;
  assert.equal((await f.ok('/api/workspace', undefined, webHeaders)).lists[0].id, listId);
  await f.ok('/api/actions', { type: 'item.create', payload: { listId, text: 'Peaches' } }, webHeaders);
  assert.equal((await f.ok('/api/workspace', undefined, alice.headers)).lists[0].items[0].text, 'Peaches');
  assert.equal((await f.ok('/api/workspace', undefined, bob.headers)).lists.length, 0);
  assert.equal((await f.ok('/api/search', { query: 'Peaches', scope: 'all' }, bob.headers)).results.length, 0);
  assert.equal((await f.request('/api/actions', { type: 'item.create', payload: { listId, text: 'intruder', owner: alice.value.userId } }, bob.headers)).response.status, 404);
  const invitation = await f.ok('/api/sharing/invite', { listId }, webHeaders);
  const preview = await f.ok('/api/invitations/preview', { token: invitation.token }, bob.headers);
  assert.equal(preview.listId, listId);
  await f.ok('/api/invitations/join', { token: invitation.token }, bob.headers);
  const shared = await f.ok('/api/sharing?listId=' + listId, undefined, bob.headers);
  assert.equal(shared.role, 'editor');
  await f.ok('/api/actions', { type: 'item.create', payload: { listId, text: 'Grapes' } }, bob.headers);
  assert.equal((await f.ok('/api/search', { query: 'Grapes' }, alice.headers)).results[0].listId, listId);
  assert.equal((await f.ok('/api/search/status', undefined, bob.headers)).provider, 'fuzzy');
  assert.equal((await f.request('/api/sharing/revoke', { listId }, bob.headers)).response.status, 403);
  assert.equal((await f.request('/api/sharing?listId=' + listId, undefined, carol.headers)).response.status, 404);
  assert.equal((await f.ok('/api/export', undefined, bob.headers)).lists.length, 0);
  const backup = await f.ok('/api/export', undefined, alice.headers);
  assert.equal(backup.lists[0].role, undefined);
  const imported = await f.ok('/api/import', backup, bob.headers);
  assert.equal(imported.lists.length, 2);
  assert.ok(imported.lists.some(l => l.id !== listId && l.role === 'owner'));
  await f.ok('/api/sharing/remove', { listId, userId: bob.value.userId }, webHeaders);
  assert.ok(!(await f.ok('/api/workspace', undefined, bob.headers)).lists.some(l => l.id === listId));
  assert.equal((await f.request('/api/actions', { type: 'item.create', payload: { listId, text: 'removed' } }, bob.headers)).response.status, 404);
});

test('password rotation, logout, expiry and operator recovery revoke native and browser sessions correctly', async t => {
  const f = await fixture(t), alice = await f.register('Alice'), bob = await f.register('Bob');
  const second = await f.ok('/api/native/auth/login', { username: 'Alice', password });
  const browser = await f.request('/api/auth/login', { username: 'Alice', password });
  const changed = await f.ok('/api/native/auth/password', { currentPassword: password, newPassword: 'Changed-password-2' }, alice.headers);
  assert.notEqual(changed.accessToken, alice.value.accessToken);
  assert.equal((await f.request('/api/workspace', undefined, alice.headers)).response.status, 401);
  assert.equal((await f.request('/api/workspace', undefined, { Authorization: `Bearer ${second.accessToken}` })).response.status, 401);
  assert.equal((await f.request('/api/workspace', undefined, { Cookie: cookie(browser.response) })).response.status, 401);
  assert.equal((await f.request('/api/workspace', undefined, bob.headers)).response.status, 200);
  const rotatedHeaders = { Authorization: `Bearer ${changed.accessToken}` };
  assert.deepEqual(await f.ok('/api/native/auth/logout', {}, rotatedHeaders), { authenticated: false, setupRequired: false });
  assert.equal((await f.request('/api/workspace', undefined, rotatedHeaders)).response.status, 401);
  assert.equal((await f.ok('/api/native/auth/session', undefined, rotatedHeaders)).authenticated, false);
  const expiring = await f.ok('/api/native/auth/login', { username: 'Alice', password: 'Changed-password-2' });
  const db = new DatabaseSync(join(f.dir, 'account.sqlite'));
  db.prepare('UPDATE sessions SET expires_at=0 WHERE token_hash=?').run(createHash('sha256').update(expiring.accessToken).digest('hex')); db.close();
  assert.equal((await f.request('/api/workspace', undefined, { Authorization: `Bearer ${expiring.accessToken}` })).response.status, 401);
  const recovered = await f.ok('/api/native/auth/login', { username: 'Alice', password: 'Changed-password-2' });
  await resetUserPassword({ dataDir: f.dir, username: 'Alice', password: 'Recovery-password-3', env: {} });
  assert.equal((await f.request('/api/workspace', undefined, { Authorization: `Bearer ${recovered.accessToken}` })).response.status, 401);
  assert.equal((await f.request('/api/workspace', undefined, bob.headers)).response.status, 200);
});

test('native endpoints preserve origin, JSON, payload limits and shared authentication throttles', async t => {
  const f = await fixture(t);
  const credentials = { username: 'Alice', password };
  for (const path of ['/api/native/auth/register', '/api/native/auth/login']) {
    assert.equal((await f.request(path, credentials, { Origin: 'https://evil.example' })).response.status, 403);
    assert.equal((await f.request(path, credentials, { 'Sec-Fetch-Site': 'cross-site' })).response.status, 403);
    assert.equal((await f.request(path, credentials, { 'Content-Type': 'text/plain' })).response.status, 415);
    assert.equal((await f.request(path, { ...credentials, extra: 'x'.repeat(8192) })).response.status, 413);
  }
  const alice = await f.register('Alice');
  for (const path of ['/api/native/auth/session', '/api/workspace']) {
    const r = await f.request(path, undefined, { ...alice.headers, Origin: 'https://evil.example' });
    assert.equal(r.response.status, 403);
    assert.equal(r.response.headers.get('access-control-allow-origin'), null);
  }
  for (let i = 0; i < 8; i++) {
    assert.equal((await f.request(i % 2 ? '/api/auth/login' : '/api/native/auth/login', { ...credentials, password: 'Wrong-password' })).response.status, 401);
  }
  assert.equal((await f.request('/api/native/auth/login', credentials)).response.status, 429);
  assert.equal((await f.request('/api/auth/login', credentials)).response.status, 429);
  assert.equal((await f.request('/api/native/auth/password', { currentPassword: password, newPassword: 'New-password' }, { Cookie: 'gather_session=invalid' })).response.status, 401);
});

test('migration preserves existing cookie sessions while adding native transport', async t => {
  const f = await fixture(t);
  const registered = await f.request('/api/auth/register', { username: 'Legacy', password });
  const browserCookie = cookie(registered.response);
  // Simulate a pre-native account database without the new column.
  const db = new DatabaseSync(join(f.dir, 'account.sqlite'));
  db.exec('ALTER TABLE sessions DROP COLUMN transport'); db.close();
  await f.restart();
  assert.equal((await f.ok('/api/auth/session', undefined, { Cookie: browserCookie })).authenticated, true);
  const native = await f.ok('/api/native/auth/login', { username: 'Legacy', password });
  assert.equal(native.userId, registered.value.userId);
});
