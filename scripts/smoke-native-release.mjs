// Independent live smoke against a running local or deployed Gather release.
// Creates an isolated QA account; never reads or changes an existing account.
import assert from 'node:assert/strict';
import { randomBytes } from 'node:crypto';
import { mkdir, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';

const base = process.argv[2] || 'http://127.0.0.1:48733';
const keep = process.argv.includes('--keep');
const url = new URL(base);
assert(['127.0.0.1', 'localhost', 'gather.takeya.ninja'].includes(url.hostname));
assert(url.hostname !== 'gather.takeya.ninja' || url.protocol === 'https:');
const username = 'android-qa-' + Date.now().toString(36);
const password = 'Gather-' + randomBytes(18).toString('base64url');
let token, cookie;
async function request(path, body, transport = 'native') {
  const response = await fetch(new URL(path, base), {
    method: body === undefined ? 'GET' : 'POST', redirect: 'error',
    headers: { Accept: 'application/json', ...(body === undefined ? {} : { 'Content-Type': 'application/json' }),
      ...(transport === 'native' && token ? { Authorization: 'Bearer ' + token } : {}),
      ...(transport === 'web' && cookie ? { Cookie: cookie } : {}) },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  assert.equal(response.headers.get('cache-control'), 'no-store');
  const value = await response.json();
  assert.equal(response.status, 200, `${path}: ${value.error || response.status}`);
  if (transport === 'web' && response.headers.has('set-cookie')) cookie = response.headers.get('set-cookie').split(';')[0];
  return value;
}
const capabilities = await request('/api/capabilities');
assert(capabilities.authentication.includes('bearer'));
const session = await request('/api/native/auth/register', { username, password });
token = session.accessToken;
assert.equal(session.tokenType, 'Bearer');
const web = await request('/api/auth/login', { username, password }, 'web');
assert(!('accessToken' in web));
const created = await request('/api/actions', { type: 'list.create', payload: { title: 'Weekend essentials', icon: 'luggage', color: 'sage', description: 'A little preparation. More room to enjoy the day.', favorite: true } });
const listId = created.createdListId;
assert(listId);
for (const text of ['Reusable water bottle', 'Headphones', 'A good book']) {
  await request('/api/actions', { type: 'item.create', payload: { listId, text } }, 'web');
}
let workspace = await request('/api/workspace');
const itemId = workspace.lists.find(list => list.id === listId).items[0].id;
await request('/api/actions', { type: 'item.update', payload: { listId, itemId, checked: true, quantity: '1', note: 'Fill before leaving.' } });
workspace = await request('/api/workspace', undefined, 'web');
assert.equal(workspace.lists.find(list => list.id === listId).items[0].checked, true);
const search = await request('/api/search', { query: 'water bottle', scope: 'library' });
assert(search.results.some(result => result.listId === listId));
const backup = await request('/api/export');
assert.equal(backup.lists.length, 1);
assert(!JSON.stringify(backup).includes(token));
if (keep) {
  await request('/api/actions', { type: 'list.create', payload: { title: 'Small ideas, someday', icon: 'lightbulb', color: 'peach', kind: 'notes', description: 'Good thoughts deserve a place to land.' } });
  const template = await request('/api/actions', { type: 'list.create', payload: { title: 'A weekend away', icon: 'luggage', color: 'lavender', isTemplate: true } });
  await request('/api/actions', { type: 'item.create', payload: { listId: template.createdListId, text: 'Travel documents' } });
  await mkdir(resolve('output'), { recursive: true });
  const path = resolve('output', url.hostname === 'gather.takeya.ninja' ? 'android-public-qa-account.json' : 'android-local-qa-account.json');
  await writeFile(path, JSON.stringify({ base, username, password }, null, 2), { mode: 0o600 });
  console.log(JSON.stringify({ status: 'passed', base, checks: ['native registration', 'browser login', 'cross-client CRUD', 'completion and details', 'search', 'private backup'], credentialFile: path }));
} else {
  await request('/api/actions', { type: 'list.delete', payload: { listId } });
  assert.equal((await request('/api/workspace')).lists.length, 0);
  console.log(JSON.stringify({ status: 'passed', base, checks: ['native registration', 'browser login', 'cross-client CRUD', 'completion and details', 'search', 'private backup', 'cleanup'] }));
}
await request('/api/auth/logout', {}, 'web');
await request('/api/native/auth/logout', {});
assert.equal((await request('/api/native/auth/session')).authenticated, false);
