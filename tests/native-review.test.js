import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { DatabaseSync } from 'node:sqlite';
import { startServer } from '../server/index.js';

// Independent HTTP acceptance: a browser owner and a native editor use one store.
async function fixture(t) {
  const dir = await mkdtemp(join(tmpdir(), 'gather-native-review-'));
  let server, base;
  const start = async () => {
    server = await startServer({ dataDir: dir, port: 0, searchEnv: { SEARCH_PROVIDER: 'fuzzy' }, authEnv: {} });
    base = `http://127.0.0.1:${server.address().port}`;
  };
  const stop = () => new Promise(resolve => server.close(resolve));
  await start();
  t.after(async () => { await stop(); await rm(dir, { recursive: true, force: true }); });
  const request = async (actor, path, value, headers = {}) => {
    const res = await fetch(base + path, {
      method: value === undefined ? 'GET' : 'POST',
      headers: { ...(actor?.token ? { Authorization: `Bearer ${actor.token}` } : {}), ...(actor?.cookie ? { Cookie: actor.cookie } : {}), ...(value === undefined ? {} : { 'Content-Type': 'application/json' }), ...headers },
      ...(value === undefined ? {} : { body: JSON.stringify(value) }),
    });
    return { status: res.status, body: await res.json(), headers: res.headers };
  };
  const ok = async (...args) => { const result = await request(...args); assert.equal(result.status, 200, JSON.stringify(result.body)); return result; };
  const account = async (name, native = false) => {
    const result = await ok(null, native ? '/api/native/auth/register' : '/api/auth/register', { username: name, password: 'Review-password-123' });
    return native ? { token: result.body.accessToken, id: result.body.userId } : { cookie: result.headers.get('set-cookie').split(';')[0], id: result.body.userId };
  };
  const action = async (actor, type, payload) => (await ok(actor, '/api/actions', { type, payload })).body;
  return { dir, request, ok, account, action, restart: async () => { await stop(); await start(); } };
}

test('native credentials remain separate from browser cookies and never leak from session/export', async t => {
  const f = await fixture(t), browser = await f.account('Browser'), native = await f.account('Native', true);
  assert.match(native.token, /^[A-Za-z0-9_-]{43}$/);
  const nativeCookie = { cookie: `gather_session=${native.token}` };
  const cookieBearer = { token: browser.cookie.split('=')[1] };
  assert.equal((await f.request(nativeCookie, '/api/workspace')).status, 401);
  assert.equal((await f.request(cookieBearer, '/api/workspace')).status, 401);
  assert.equal((await f.ok(browser, '/api/native/auth/session')).body.authenticated, false);
  assert.equal((await f.ok(native, '/api/auth/session')).body.authenticated, false);
  assert.equal((await f.request({ ...browser, token: 'invalid' }, '/api/workspace')).status, 401);
  for (const path of ['/api/native/auth/session', '/api/workspace', '/api/export']) {
    const result = await f.ok(native, path);
    assert.equal(result.headers.get('cache-control'), 'no-store');
    assert.equal(result.headers.get('set-cookie'), null);
    assert.ok(!JSON.stringify(result.body).includes(native.token));
  }
  const db = new DatabaseSync(join(f.dir, 'account.sqlite'));
  try {
    const sessions = db.prepare('SELECT token_hash,transport FROM sessions').all();
    assert.deepEqual(sessions.map(s => s.transport).sort(), ['bearer', 'cookie']);
    for (const row of sessions) assert.match(row.token_hash, /^[a-f0-9]{64}$/);
  } finally { db.close(); }
});

test('browser and native shared edits survive concurrent granular writes, permission revocation and private backup', async t => {
  const f = await fixture(t), owner = await f.account('Owner'), editor = await f.account('Editor', true), stranger = await f.account('Stranger', true);
  const created = await f.action(owner, 'list.create', { title: 'Cross-platform household' });
  const listId = created.createdListId;
  const invite = (await f.ok(owner, '/api/sharing/invite', { listId })).body;
  await f.ok(editor, '/api/invitations/join', { token: invite.token });
  await Promise.all(Array.from({ length: 12 }, (_, i) => f.action(i % 2 ? owner : editor, 'item.create', { listId, text: `cross-platform-${i}` })));
  const final = (await f.ok(editor, '/api/workspace')).body.lists.find(l => l.id === listId);
  assert.equal(final.items.length, 12);
  assert.equal(new Set(final.items.map(i => i.id)).size, 12);
  assert.equal((await f.ok(owner, '/api/workspace')).body.lists.find(l => l.id === listId).items.length, 12);
  assert.equal((await f.request(editor, '/api/actions', { type: 'list.delete', payload: { listId } })).status, 403);
  assert.equal((await f.ok(stranger, '/api/search', { query: 'cross-platform', scope: 'all' })).body.results.length, 0);
  assert.equal((await f.ok(editor, '/api/export')).body.lists.length, 0);
  const backup = (await f.ok(owner, '/api/export')).body;
  assert.equal(backup.lists.length, 1);
  assert.equal(backup.lists[0].items.length, 12);
  assert.ok(!JSON.stringify(backup).includes(invite.token));
  assert.equal(backup.lists[0].role, undefined);
  await f.ok(owner, '/api/sharing/remove', { listId, userId: editor.id });
  assert.equal((await f.request(editor, '/api/actions', { type: 'item.create', payload: { listId, text: 'revoked write' } })).status, 404);
  assert.equal((await f.ok(editor, '/api/workspace')).body.lists.length, 0);
  await f.restart();
  assert.equal((await f.ok(owner, '/api/workspace')).body.lists[0].items.length, 12);
  assert.equal((await f.ok(editor, '/api/workspace')).body.lists.length, 0);
});

test('native password changes rotate credentials and revoke every old browser and native session', async t => {
  const f = await fixture(t), native = await f.account('Rotation', true);
  const browserLogin = await f.ok(null, '/api/auth/login', { username: 'Rotation', password: 'Review-password-123' });
  const browser = { cookie: browserLogin.headers.get('set-cookie').split(';')[0] };
  const anotherNativeLogin = await f.ok(null, '/api/native/auth/login', { username: 'Rotation', password: 'Review-password-123' });
  const anotherNative = { token: anotherNativeLogin.body.accessToken };
  const change = await f.ok(native, '/api/native/auth/password', { currentPassword: 'Review-password-123', newPassword: 'Changed-password-456' });
  assert.notEqual(change.body.accessToken, native.token);
  const updated = { token: change.body.accessToken };
  for (const old of [native, browser, anotherNative]) assert.equal((await f.request(old, '/api/workspace')).status, 401);
  await f.restart();
  assert.equal((await f.ok(updated, '/api/native/auth/session')).body.authenticated, true);
  await f.ok(updated, '/api/native/auth/logout', {});
  assert.equal((await f.request(updated, '/api/workspace')).status, 401);
  assert.equal((await f.request(null, '/api/native/auth/login', { username: 'Rotation', password: 'Review-password-123' })).status, 401);
});

test('native API retains same-origin checks and read/write body restrictions', async t => {
  const f = await fixture(t), native = await f.account('Origin', true);
  assert.equal((await f.request(native, '/api/workspace', undefined, { Origin: 'https://attacker.invalid' })).status, 403);
  assert.equal((await f.request(native, '/api/native/auth/session', undefined, { 'Sec-Fetch-Site': 'cross-site' })).status, 403);
  assert.equal((await f.request(native, '/api/actions', { type: 'list.create', payload: { title: 'bad' } }, { Origin: 'https://attacker.invalid' })).status, 403);
  assert.equal((await f.request(null, '/api/native/auth/login', { username: 'Origin', password: 'Review-password-123' }, { 'Content-Type': 'text/plain' })).status, 415);
  assert.equal((await f.request(null, '/api/native/auth/login', { username: 'x'.repeat(9000), password: 'Review-password-123' })).status, 413);
  assert.equal((await f.ok(native, '/api/workspace')).body.lists.length, 0);
});

