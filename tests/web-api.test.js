import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import ts from 'typescript';

const source = await readFile(new URL('../src/api.ts', import.meta.url), 'utf8');
const javascript = ts.transpileModule(source, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext } }).outputText;
const api = await import('data:text/javascript;base64,' + Buffer.from(javascript).toString('base64'));
const json = (value, status = 200) => new Response(JSON.stringify(value), { status, headers: { 'Content-Type': 'application/json' } });

test('web login rejects HTML and malformed successful sessions instead of assuming authentication', async t => {
  const original = globalThis.fetch;
  t.after(() => { globalThis.fetch = original; });
  for (const response of [new Response('<html>proxy fallback</html>', { headers: { 'Content-Type': 'text/html' } }), json({}), json({ authenticated: true }), json({ authenticated: false })]) {
    globalThis.fetch = async () => response;
    await assert.rejects(api.login('Alice', 'password'), error => error.status === 502);
  }
  globalThis.fetch = async () => json({ authenticated: true, userId: 'alice-id', username: 'Alice', setupRequired: false });
  assert.equal((await api.login('Alice', 'password')).userId, 'alice-id');
  globalThis.fetch = async () => json({ authenticated: false, setupRequired: false });
  assert.equal((await api.getSession()).authenticated, false);
  assert.equal((await api.logout()).authenticated, false);
});

test('web workspace and search share cookies, cache policy, cancellation and expiration handling', async t => {
  const original = globalThis.fetch, originalWindow = globalThis.window;
  t.after(() => { globalThis.fetch = original; if (originalWindow === undefined) delete globalThis.window; else globalThis.window = originalWindow; });
  const events = new EventTarget();
  globalThis.window = events;
  let expired = 0;
  events.addEventListener(api.AUTH_EXPIRED_EVENT, () => { expired++; });
  const controller = new AbortController();
  const calls = [];
  globalThis.fetch = async (path, options) => { calls.push({ path, options }); return json({ results: [], lists: [], provider: 'fuzzy' }); };
  await api.getWorkspace();
  await api.runAction('list.create', { title: 'Groceries' });
  await api.searchLists('peaches', 'library', null, controller.signal);
  await api.getSearchStatus();
  for (const { options } of calls) {
    assert.equal(options.credentials, 'same-origin');
    assert.equal(options.cache, 'no-store');
    assert.equal(options.headers?.Authorization, undefined);
  }
  assert.equal(calls[2].options.signal, controller.signal);
  assert.equal(JSON.parse(calls[2].options.body).limit, 125);
  globalThis.fetch = async () => json({ error: 'Authentication required.' }, 401);
  await assert.rejects(api.getWorkspace(), error => error.status === 401);
  await assert.rejects(api.searchLists('peaches', 'library', null, controller.signal), error => error.status === 401);
  assert.equal(expired, 2);
  await assert.rejects(api.login('Alice', 'wrong'), error => error.status === 401);
  assert.equal(expired, 2, 'failed sign-in must not emit a workspace session expiration');
});
