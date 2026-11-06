import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { startServer } from '../server/index.js';

// Independent API acceptance: no store internals, UI filtering or shared cookie jar.
async function fixture(t, searchEnv = { SEARCH_PROVIDER: 'fuzzy' }) {
  const dir = await mkdtemp(join(tmpdir(), 'gather-accounts-acceptance-'));
  let server;
  let base;
  async function start() {
    server = await startServer({ dataDir: dir, distDir: join(dir, 'dist'), port: 0, searchEnv, authEnv: {} });
    base = `http://127.0.0.1:${server.address().port}`;
  }
  const stop = () => new Promise(resolve => server.close(resolve));
  await start();
  t.after(async () => { await stop(); await rm(dir, { recursive: true, force: true }); });
  async function request(actor, path, value) {
    const response = await fetch(base + path, {
      method: value === undefined ? 'GET' : 'POST',
      headers: { ...(actor?.cookie ? { Cookie: actor.cookie } : {}), ...(value === undefined ? {} : { 'Content-Type': 'application/json' }) },
      ...(value === undefined ? {} : { body: JSON.stringify(value) }),
    });
    const result = { status: response.status, value: await response.json(), response };
    return result;
  }
  async function ok(actor, path, value) {
    const result = await request(actor, path, value);
    assert.equal(result.status, 200, `${path}: ${JSON.stringify(result.value)}`);
    return result.value;
  }
  async function register(username) {
    const result = await request(null, '/api/auth/register', { username, password: 'Acceptance-password-1' });
    assert.equal(result.status, 200, JSON.stringify(result.value));
    return { username, userId: result.value.userId, cookie: result.response.headers.get('set-cookie')?.split(';', 1)[0] };
  }
  const [a, b, c] = [await register('Alice'), await register('Bob'), await register('Carol')];
  const action = (actor, type, payload = {}) => ok(actor, '/api/actions', { type, payload });
  const workspace = actor => ok(actor, '/api/workspace');
  async function create(actor, title, properties = {}) {
    const w = await action(actor, 'list.create', { title, ...properties });
    return w.lists.find(list => list.id === w.createdListId);
  }
  const invite = (actor, listId) => ok(actor, '/api/sharing/invite', { listId });
  const joinList = (actor, token) => ok(actor, '/api/invitations/join', { token });
  const sharing = (actor, listId) => ok(actor, `/api/sharing?listId=${encodeURIComponent(listId)}`);
  return { a, b, c, request, ok, action, workspace, create, invite, joinList, sharing, dir, get base() { return base; }, get server() { return server; }, restart: async () => { await stop(); await start(); } };
}

function denied(result, label) {
  assert.ok([400, 401, 403, 404, 409, 410].includes(result.status), `${label}: expected rejection, got ${result.status}`);
}

// Three unrelated users are deliberate: a successful two-user share must not grant a third user access.
test('accounts isolate private reads, exports, category IDs, guessed content IDs and every mutation family', async t => {
  const f = await fixture(t);
  const { a, b, c } = f;
  for (const actor of [a, b, c]) assert.equal((await f.workspace(actor)).lists.length, 0);
  const secret = await f.create(a, 'Alice-private-marker');
  await f.action(a, 'section.create', { listId: secret.id, title: 'Private section' });
  let aw = await f.action(a, 'item.create', { listId: secret.id, text: 'private-item-marker', note: 'private-note-marker' });
  const privateList = aw.lists.find(list => list.id === secret.id);
  const itemId = privateList.items[0].id;
  const sectionId = privateList.sections[0].id;
  const foreignCategory = aw.categories[0].id;
  const bList = await f.create(b, 'Bob-owned');
  for (const actor of [b, c]) {
    assert.ok(!(await f.workspace(actor)).lists.some(list => list.id === secret.id));
    assert.ok(!(await f.ok(actor, '/api/export')).lists.some(list => list.id === secret.id));
    const search = await f.ok(actor, '/api/search', { query: 'private', scope: 'all' });
    assert.equal(search.results.length, 0);
    denied(await f.request(actor, `/api/sharing?listId=${secret.id}`), 'sharing read');
    for (const [type, payload] of [
      ['list.update', { title: 'stolen' }], ['list.delete', {}], ['list.clone', {}], ['list.reset', {}],
      ['item.create', { text: 'intruder' }], ['item.update', { itemId, text: 'stolen' }], ['item.delete', { itemId }],
      ['item.move', { itemId, sectionId: null, index: 0 }], ['section.create', { title: 'intruder' }],
      ['section.update', { sectionId, title: 'stolen' }], ['section.delete', { sectionId }], ['section.move', { sectionId, index: 0 }],
    ]) denied(await f.request(actor, '/api/actions', { type, payload: { listId: secret.id, ...payload, userId: a.userId } }), type);
    for (const path of ['/api/sharing/invite', '/api/sharing/revoke', '/api/sharing/remove', '/api/sharing/leave']) {
      denied(await f.request(actor, path, { listId: secret.id, userId: a.userId }), path);
    }
    denied(await f.request(actor, '/api/actions', { type: 'category.update', payload: { categoryId: foreignCategory, name: 'stolen' } }), 'category update');
    denied(await f.request(actor, '/api/actions', { type: 'category.delete', payload: { categoryId: foreignCategory } }), 'category delete');
    denied(await f.request(actor, '/api/search', { query: 'private', categoryId: foreignCategory }), 'foreign search category');
  }
  denied(await f.request(b, '/api/actions', { type: 'list.update', payload: { listId: bList.id, categoryId: foreignCategory } }), 'foreign category association');
  assert.deepEqual((await f.workspace(a)).lists.find(list => list.id === secret.id), privateList);
  for (const [path, body] of [['/api/workspace'], ['/api/export'], ['/api/search/status'], ['/api/sharing?listId=' + secret.id], ['/api/invitations/preview', { token: 'anything' }], ['/api/actions', {}], ['/api/import', {}], ['/api/search', { query: 'private' }], ['/api/sharing/invite', { listId: secret.id }], ['/api/invitations/join', { token: 'anything' }]]) {
    assert.equal((await f.request(null, path, body)).status, 401, path);
  }
});

test('single-use invitations require explicit acceptance, revoke/replace/expire safely and survive concurrent redemption', async t => {
  const f = await fixture(t), { a, b, c } = f;
  const list = await f.create(a, 'Household list');
  await f.action(a, 'item.create', { listId: list.id, text: 'Not visible in invitation preview' });
  const first = await f.invite(a, list.id);
  const preview = await f.ok(b, '/api/invitations/preview', { token: first.token });
  assert.equal(preview.listId, list.id);
  assert.equal(JSON.stringify(preview).includes('Not visible'), false);
  assert.equal((await f.workspace(b)).lists.length, 0, 'preview must not join');
  assert.equal((await f.request(b, '/api/invitations/preview?token=' + encodeURIComponent(first.token))).status, 404, 'query-string preview endpoint must be unavailable');
  const replacement = await f.invite(a, list.id);
  denied(await f.request(b, '/api/invitations/join', { token: first.token }), 'replaced invitation');
  await f.ok(a, '/api/sharing/revoke', { listId: list.id });
  denied(await f.request(b, '/api/invitations/join', { token: replacement.token }), 'revoked invitation');
  const expiring = await f.invite(a, list.id);
  const now = Date.now;
  try {
    Date.now = () => now() + 8 * 24 * 60 * 60 * 1000;
    denied(await f.request(b, '/api/invitations/join', { token: expiring.token }), 'expired invitation');
  } finally { Date.now = now; }
  const race = await f.invite(a, list.id);
  const results = await Promise.all([b, c].map(actor => f.request(actor, '/api/invitations/join', { token: race.token })));
  assert.equal(results.filter(result => result.status === 200).length, 1);
  const winner = results[0].status === 200 ? b : c;
  const loser = winner === b ? c : b;
  const retry = await f.joinList(winner, race.token);
  assert.equal(retry.lists.filter(entry => entry.id === list.id).length, 1);
  denied(await f.request(loser, '/api/invitations/join', { token: race.token }), 'single use');
  assert.equal((await f.workspace(loser)).lists.length, 0);
  await f.restart();
  assert.equal((await f.workspace(winner)).lists.filter(entry => entry.id === list.id).length, 1);
  assert.equal((await f.sharing(a, list.id)).members.filter(member => member.userId === winner.userId).length, 1);
  const forNextPerson = await f.invite(a, list.id);
  await f.joinList(winner, forNextPerson.token);
  assert.equal((await f.sharing(a, list.id)).inviteActive, true, 'existing member must not consume another person\'s pending invitation');
  await f.joinList(loser, forNextPerson.token);
});

test('shared content converges while personal preferences and owner permissions stay separate', async t => {
  const f = await fixture(t), { a, b, c } = f;
  const list = await f.create(a, 'Shared groceries');
  await f.joinList(b, (await f.invite(a, list.id)).token);
  const bc = (await f.workspace(b)).categories[0].id;
  const ac = (await f.workspace(a)).categories[0].id;
  await f.action(a, 'list.update', { listId: list.id, categoryId: ac, favorite: false });
  await f.action(b, 'list.update', { listId: list.id, categoryId: bc, favorite: true, archived: true });
  let al = (await f.workspace(a)).lists.find(entry => entry.id === list.id);
  let bl = (await f.workspace(b)).lists.find(entry => entry.id === list.id);
  assert.equal(al.archived, false); assert.equal(al.favorite, false); assert.equal(al.categoryId, ac);
  assert.equal(bl.archived, true); assert.equal(bl.favorite, true); assert.equal(bl.categoryId, bc);
  denied(await f.request(a, '/api/sharing/remove', { listId: list.id, userId: a.userId }), 'owner self-removal');
  const afterSelfRemoval = (await f.workspace(a)).lists.find(entry => entry.id === list.id);
  assert.equal(afterSelfRemoval.categoryId, ac); assert.equal(afterSelfRemoval.favorite, false); assert.equal(afterSelfRemoval.archived, false);
  assert.equal((await f.sharing(b, list.id)).members.some(member => member.userId === b.userId), true, 'accepted people visible to members');
  assert.equal((await f.workspace(c)).lists.length, 0);
  const privateSource = await f.create(a, 'Unshared ID source');
  let privateWorkspace = await f.action(a, 'section.create', { listId: privateSource.id, title: 'Private source section' });
  privateWorkspace = await f.action(a, 'item.create', { listId: privateSource.id, text: 'Never cross this parent' });
  const privateRecord = privateWorkspace.lists.find(entry => entry.id === privateSource.id);
  denied(await f.request(b, '/api/actions', { type: 'item.update', payload: { listId: list.id, itemId: privateRecord.items[0].id, text: 'Cross-list theft' } }), 'foreign item in accessible list');
  denied(await f.request(b, '/api/actions', { type: 'item.create', payload: { listId: list.id, sectionId: privateRecord.sections[0].id, text: 'Cross-list section theft' } }), 'foreign section in accessible list');
  await f.action(b, 'list.update', { listId: list.id, archived: false });
  await Promise.all([f.action(a, 'item.create', { listId: list.id, text: 'Alice addition' }), f.action(b, 'item.create', { listId: list.id, text: 'Bob addition' })]);
  al = (await f.workspace(a)).lists.find(entry => entry.id === list.id);
  assert.deepEqual(al.items.map(item => item.text).sort(), ['Alice addition', 'Bob addition']);
  await f.action(b, 'item.update', { listId: list.id, itemId: al.items[0].id, checked: true });
  assert.equal((await f.workspace(a)).lists.find(entry => entry.id === list.id).items[0].checked, true);
  for (const patch of [{ kind: 'notes' }, { isTemplate: true }, { repeatCadence: 'daily' }]) {
    denied(await f.request(b, '/api/actions', { type: 'list.update', payload: { listId: list.id, title: 'must roll back', ...patch } }), 'owner-only metadata');
  }
  for (const [path, payload] of [['/api/sharing/invite', {}], ['/api/sharing/revoke', {}], ['/api/sharing/remove', { userId: a.userId }]]) denied(await f.request(b, path, { listId: list.id, ...payload }), path);
  denied(await f.request(b, '/api/actions', { type: 'list.delete', payload: { listId: list.id } }), 'editor delete');
  assert.equal((await f.workspace(a)).lists.find(entry => entry.id === list.id).title, 'Shared groceries');
  await f.restart();
  bl = (await f.workspace(b)).lists.find(entry => entry.id === list.id);
  assert.equal(bl.favorite, true); assert.equal(bl.categoryId, bc);
  assert.equal(bl.items.length, 2);
});

test('removal and leave revoke stale access, search results and pending edits without destroying private copies', async t => {
  const f = await fixture(t), { a, b } = f;
  const list = await f.create(a, 'Revocation search needle');
  let w = await f.action(a, 'item.create', { listId: list.id, text: 'Original shared item', checked: true });
  const itemId = w.lists.find(entry => entry.id === list.id).items[0].id;
  const consumed = await f.invite(a, list.id);
  await f.joinList(b, consumed.token);
  assert.equal((await f.ok(b, '/api/search', { query: 'Revocation search needle', scope: 'all' })).results[0].listId, list.id);
  w = await f.action(b, 'list.clone', { listId: list.id, title: 'My retained copy' });
  const copyId = w.createdListId;
  await f.ok(a, '/api/sharing/remove', { listId: list.id, userId: b.userId });
  assert.equal((await f.workspace(b)).lists.some(entry => entry.id === list.id), false);
  assert.equal((await f.ok(b, '/api/search', { query: 'Revocation search needle', scope: 'all' })).results.length, 0);
  denied(await f.request(b, '/api/actions', { type: 'item.update', payload: { listId: list.id, itemId, text: 'stale edit' } }), 'stale edit');
  denied(await f.request(b, '/api/actions', { type: 'list.clone', payload: { listId: list.id } }), 'stale clone');
  denied(await f.request(b, '/api/invitations/join', { token: consumed.token }), 'consumed invitation after removal');
  assert.ok((await f.workspace(b)).lists.some(entry => entry.id === copyId));
  await f.joinList(b, (await f.invite(a, list.id)).token);
  await f.ok(b, '/api/sharing/leave', { listId: list.id });
  assert.equal((await f.workspace(b)).lists.some(entry => entry.id === list.id), false);
  assert.ok((await f.workspace(a)).lists.some(entry => entry.id === list.id));
  denied(await f.request(a, '/api/sharing/leave', { listId: list.id }), 'owner leave');
  assert.equal((await f.workspace(a)).lists.find(entry => entry.id === list.id).items[0].text, 'Original shared item');
});

test('shared templates copy privately; owned-only export and repeated imports never restore sharing or overwrite live lists', async t => {
  const f = await fixture(t), { a, b, c } = f;
  const template = await f.create(a, 'Shared packing master', { isTemplate: true, repeatCadence: 'weekly' });
  await f.action(a, 'section.create', { listId: template.id, title: 'Essentials' });
  let aw = await f.action(a, 'item.create', { listId: template.id, text: 'Shared packed item', checked: true, note: 'Remember adapter' });
  const master = aw.lists.find(entry => entry.id === template.id);
  await f.joinList(b, (await f.invite(a, template.id)).token);
  const bw = await f.action(b, 'list.clone', { listId: template.id, title: 'My private trip' });
  const copy = bw.lists.find(entry => entry.id === bw.createdListId);
  assert.notEqual(copy.id, template.id); assert.equal(copy.isTemplate, false); assert.equal(copy.shared, false);
  assert.equal(copy.items[0].checked, false); assert.notEqual(copy.items[0].id, master.items[0].id);
  assert.notEqual(copy.sections[0].id, master.sections[0].id); assert.equal(copy.repeatCadence, 'weekly');
  assert.ok(!(await f.workspace(a)).lists.some(entry => entry.id === copy.id));
  const bExport = await f.ok(b, '/api/export');
  assert.deepEqual(bExport.lists.map(entry => entry.id), [copy.id]);
  const exported = await f.ok(a, '/api/export');
  assert.equal(exported.lists.length, 1); assert.equal(exported.lists[0].items[0].checked, true);
  assert.equal(JSON.stringify(exported).includes('Bob'), false, 'backup must exclude member identities');
  const originalIds = new Set([master.id, ...master.sections.map(section => section.id), ...master.items.map(item => item.id)]);
  await f.ok(c, '/api/import', exported);
  await f.ok(c, '/api/import', exported);
  const cw = await f.workspace(c);
  assert.equal(cw.lists.length, 2);
  const allIds = cw.lists.flatMap(list => [list.id, ...list.sections.map(section => section.id), ...list.items.map(item => item.id)]);
  assert.equal(new Set(allIds).size, allIds.length);
  assert.ok(allIds.every(id => !originalIds.has(id)));
  assert.ok(cw.lists.every(list => !list.shared && list.items[0].checked && list.isTemplate));
  assert.equal((await f.workspace(a)).lists.length, 1, 'import must not replace original owner content');
  const invalid = structuredClone(exported); invalid.lists[0].items[0].sectionId = 'missing-section';
  denied(await f.request(c, '/api/import', invalid), 'invalid import');
  assert.deepEqual(await f.workspace(c), cw, 'invalid import must leave personal workspace unchanged');
  await f.action(a, 'list.delete', { listId: template.id });
  assert.ok(!(await f.workspace(b)).lists.some(entry => entry.id === template.id));
  assert.ok((await f.workspace(b)).lists.some(entry => entry.id === copy.id));
  assert.equal((await f.workspace(c)).lists.length, 2);
});

test('legacy singleton owner credentials, sessions and exact saved content migrate without exposing lists to new accounts', async t => {
  const { DatabaseSync } = await import('node:sqlite');
  const { createHash, scryptSync } = await import('node:crypto');
  const { seedWorkspace } = await import('../server/seed.mjs');
  const dir = await mkdtemp(join(tmpdir(), 'gather-legacy-acceptance-'));
  const saved = seedWorkspace();
  saved.lists[0].title = 'Preserve legacy owner content';
  saved.lists[0].items[0].checked = true;
  saved.lists[0].items[0].note = 'Never reseed over this';
  const db = new DatabaseSync(join(dir, 'gather.sqlite'));
  db.exec('CREATE TABLE workspace (id INTEGER PRIMARY KEY CHECK(id=1), document TEXT NOT NULL)');
  db.prepare('INSERT INTO workspace VALUES(1,?)').run(JSON.stringify(saved));
  db.close();
  const salt = Buffer.alloc(16, 37);
  const hash = scryptSync('Legacy-password-1', salt, 64, { N: 16384, r: 8, p: 5, maxmem: 32 * 1024 * 1024 });
  const encoded = `${salt.toString('base64')}:${hash.toString('base64')}`;
  const token = 'legacy-session-token-for-migration-acceptance';
  const auth = new DatabaseSync(join(dir, 'account.sqlite'));
  auth.exec('CREATE TABLE account (id INTEGER PRIMARY KEY CHECK(id=1), username TEXT NOT NULL, password_hash TEXT NOT NULL, created_at INTEGER NOT NULL); CREATE TABLE sessions (token_hash TEXT PRIMARY KEY, username TEXT NOT NULL, created_at INTEGER NOT NULL, expires_at INTEGER NOT NULL)');
  auth.prepare('INSERT INTO account VALUES(1,?,?,?)').run('LegacyOwner', encoded, Date.now());
  auth.prepare('INSERT INTO sessions VALUES(?,?,?,?)').run(createHash('sha256').update(token).digest('hex'), 'LegacyOwner', Date.now(), Date.now() + 86400000);
  auth.close();
  let server = await startServer({ dataDir: dir, port: 0, searchEnv: { SEARCH_PROVIDER: 'fuzzy' }, authEnv: {} });
  t.after(async () => { await new Promise(resolve => server.close(resolve)); await rm(dir, { recursive: true, force: true }); });
  let base = `http://127.0.0.1:${server.address().port}`;
  const oldSession = await fetch(base + '/api/workspace', { headers: { Cookie: `gather_session=${token}` } });
  assert.equal(oldSession.status, 200, 'existing browser session should migrate');
  const migrated = await oldSession.json();
  assert.equal(migrated.lists.length, saved.lists.length);
  for (const oldList of saved.lists) {
    const current = migrated.lists.find(list => list.id === oldList.id);
    assert.ok(current);
    for (const [key, value] of Object.entries(oldList)) assert.deepEqual(current[key], value, `${oldList.id}.${key}`);
  }
  assert.deepEqual(migrated.categories, saved.categories);
  const login = await fetch(base + '/api/auth/login', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ username: 'legacyowner', password: 'Legacy-password-1' }) });
  assert.equal(login.status, 200, 'existing password survives migration');
  const registered = await fetch(base + '/api/auth/register', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ username: 'NewPerson', password: 'New-password-1' }) });
  assert.equal(registered.status, 200);
  const cookie = registered.headers.get('set-cookie').split(';', 1)[0];
  const newcomer = await fetch(base + '/api/workspace', { headers: { Cookie: cookie } });
  assert.equal((await newcomer.json()).lists.length, 0, 'new account cannot inherit legacy household data');
  const loginCookie = login.headers.get('set-cookie').split(';', 1)[0];
  const changed = await fetch(base + '/api/auth/password', { method: 'POST', headers: { 'Content-Type': 'application/json', Cookie: loginCookie }, body: JSON.stringify({ currentPassword: 'Legacy-password-1', newPassword: 'Migrated-new-password-2' }) });
  assert.equal(changed.status, 200);
  const rotatedCookie = changed.headers.get('set-cookie').split(';', 1)[0];
  await new Promise(resolve => server.close(resolve));
  server = await startServer({ dataDir: dir, port: 0, searchEnv: { SEARCH_PROVIDER: 'fuzzy' }, authEnv: {} });
  base = `http://127.0.0.1:${server.address().port}`;
  assert.equal((await fetch(base + '/api/workspace', { headers: { Cookie: `gather_session=${token}` } })).status, 401, 'migration restart cannot revive retired sessions');
  assert.equal((await fetch(base + '/api/workspace', { headers: { Cookie: rotatedCookie } })).status, 200, 'rotated session survives migration restart');
  for (const [password, expected] of [['Legacy-password-1', 401], ['Migrated-new-password-2', 200]]) {
    const result = await fetch(base + '/api/auth/login', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ username: 'legacyowner', password }) });
    assert.equal(result.status, expected, 'legacy migration must never overwrite an updated password hash');
  }
});

test('account identity is session-derived and password/logout changes never revoke another person', async t => {
  const f = await fixture(t), { a, b } = f;
  denied(await f.request(null, '/api/auth/register', { username: 'ALICE', password: 'Another-password-1' }), 'case-insensitive username uniqueness');
  const forged = await f.create(b, 'Bob creates despite forged owner', { ownerId: a.userId, userId: a.userId });
  assert.equal(forged.role, 'owner');
  assert.equal((await f.workspace(a)).lists.some(list => list.id === forged.id), false);
  const login = await f.request(null, '/api/auth/login', { username: 'alice', password: 'Acceptance-password-1' });
  assert.equal(login.status, 200);
  const oldOtherDevice = { cookie: login.response.headers.get('set-cookie').split(';', 1)[0] };
  const change = await f.request(a, '/api/auth/password', { currentPassword: 'Acceptance-password-1', newPassword: 'Replacement-password-2' });
  assert.equal(change.status, 200);
  const current = { cookie: change.response.headers.get('set-cookie').split(';', 1)[0] };
  assert.equal((await f.request(oldOtherDevice, '/api/workspace')).status, 401);
  assert.equal((await f.request(a, '/api/workspace')).status, 401);
  assert.equal((await f.request(current, '/api/workspace')).status, 200);
  assert.equal((await f.request(b, '/api/workspace')).status, 200);
  await f.ok(current, '/api/auth/logout', {});
  assert.equal((await f.request(current, '/api/workspace')).status, 401);
  assert.equal((await f.request(b, '/api/workspace')).status, 200);
});

test('an in-flight hosted search rechecks access after removal before returning any evidence', { timeout: 10000 }, async t => {
  const { createServer } = await import('node:http');
  const seen = Promise.withResolvers();
  const release = Promise.withResolvers();
  const upstream = createServer(async (req, res) => {
    const parts = [];
    for await (const part of req) parts.push(part);
    const input = JSON.parse(Buffer.concat(parts).toString('utf8'));
    const candidates = JSON.parse(input.input[1].content).candidates;
    seen.resolve(candidates);
    await release.promise;
    res.setHeader('Content-Type', 'application/json');
    res.end(JSON.stringify({ status: 'completed', output: [{ content: [{ type: 'output_text', text: JSON.stringify({ ids: candidates.map(candidate => candidate.id) }) }] }] }));
  });
  await new Promise(resolve => upstream.listen(0, '127.0.0.1', resolve));
  t.after(async () => { release.resolve(); await new Promise(resolve => upstream.close(resolve)); });
  const f = await fixture(t, { SEARCH_PROVIDER: 'openai', OPENAI_API_KEY: 'disposable-test-key', SEARCH_OPENAI_BASE_URL: `http://127.0.0.1:${upstream.address().port}/v1/`, SEARCH_OPENAI_MODEL: 'acceptance-mock' });
  const list = await f.create(f.a, 'Removed evidence needle');
  await f.action(f.a, 'item.create', { listId: list.id, text: 'Evidence must disappear after revocation' });
  await f.joinList(f.b, (await f.invite(f.a, list.id)).token);
  const pending = f.request(f.b, '/api/search', { query: 'Removed evidence needle', scope: 'all' });
  const candidates = await seen.promise;
  assert.equal(candidates.some(candidate => candidate.id === list.id), true, 'provider actually received authorized candidate before removal');
  await f.ok(f.a, '/api/sharing/remove', { listId: list.id, userId: f.b.userId });
  release.resolve();
  const completed = await pending;
  assert.equal(completed.status, 200);
  assert.deepEqual(completed.value.results, [], 'post-inference response must remove revoked candidate and all evidence');
});

test('joining cannot bypass the aggregate visible-item limit and rejection does not consume the invitation', async t => {
  const f = await fixture(t), { a, b, c } = f;
  const now = new Date().toISOString();
  const backup = { version: 2, categories: [], lists: Array.from({ length: 4 }, (_, n) => ({
    id: `bounded-list-${n}`, title: `Bounded list ${n}`, description: '', icon: 'list', color: 'sage', categoryId: null, favorite: false, archived: false, isTemplate: false, isSample: false, kind: 'checklist', repeatCadence: 'manual', createdAt: now, updatedAt: now, position: n, sections: [],
    items: Array.from({ length: 5000 }, (_, i) => ({ id: `bounded-item-${n}-${i}`, text: 'Stored item', quantity: '', note: '', checked: false, sectionId: null, position: i })),
  })) };
  await f.ok(b, '/api/import', backup);
  const list = await f.create(a, 'One more visible item');
  await f.action(a, 'item.create', { listId: list.id, text: 'Would exceed aggregate cap' });
  const invitation = await f.invite(a, list.id);
  denied(await f.request(b, '/api/invitations/join', { token: invitation.token }), 'projected visible item limit');
  assert.equal((await f.workspace(b)).lists.length, 4);
  assert.equal((await f.sharing(a, list.id)).inviteActive, true);
  await f.joinList(c, invitation.token);
  assert.equal((await f.workspace(c)).lists.length, 1);
});

test('logout during a slow request body revokes the captured session before a mutation executes', { timeout: 10000 }, async t => {
  const { request: httpRequest } = await import('node:http');
  const f = await fixture(t);
  const captured = Promise.withResolvers();
  const observe = req => { if (req.url === '/api/actions') captured.resolve(); };
  f.server.on('request', observe);
  const document = JSON.stringify({ type: 'list.create', payload: { title: 'Must never be created after logout' } });
  const split = document.indexOf('payload') + 9;
  let slowRequest;
  const completed = new Promise((resolve, reject) => {
    slowRequest = httpRequest(f.base + '/api/actions', { method: 'POST', headers: { Cookie: f.a.cookie, 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(document) } }, async response => {
      const chunks = [];
      for await (const chunk of response) chunks.push(chunk);
      resolve({ status: response.statusCode, value: JSON.parse(Buffer.concat(chunks).toString('utf8')) });
    });
    slowRequest.on('error', reject);
    slowRequest.write(document.slice(0, split));
  });
  t.after(() => { f.server.removeListener('request', observe); slowRequest.destroy(); });
  await captured.promise;
  await f.ok(f.a, '/api/auth/logout', {});
  slowRequest.end(document.slice(split));
  const result = await completed;
  assert.equal(result.status, 401, 'session captured before reading body must be revalidated afterward');
  const login = await f.request(null, '/api/auth/login', { username: 'Alice', password: 'Acceptance-password-1' });
  assert.equal(login.status, 200);
  const actor = { cookie: login.response.headers.get('set-cookie').split(';', 1)[0] };
  assert.equal((await f.workspace(actor)).lists.length, 0, 'revoked in-flight command must not create content');
  assert.equal((await f.request(f.b, '/api/workspace')).status, 200);
});

test('operator recovery CLI targets one account while preserving its identity, content and sharing across restart', async t => {
  const { execFile } = await import('node:child_process');
  const { promisify } = await import('node:util');
  const execute = promisify(execFile);
  const f = await fixture(t), { a, b } = f;
  const list = await f.create(a, 'Recovery keeps this shared list');
  await f.action(a, 'item.create', { listId: list.id, text: 'Recovery retains saved content', checked: true });
  await f.joinList(b, (await f.invite(a, list.id)).token);
  const before = await f.workspace(a);
  const command = await execute(process.execPath, ['server/reset-account.mjs', '--username', 'ALICE', '--confirm'], {
    cwd: process.cwd(), env: { ...process.env, DATA_DIR: f.dir, GATHER_RESET_PASSWORD: 'Recovered-password-3' },
  });
  assert.equal(command.stdout.includes('Recovered-password-3'), false, 'recovery must never echo credentials');
  assert.equal((await f.request(a, '/api/workspace')).status, 401);
  assert.equal((await f.request(b, '/api/workspace')).status, 200, 'other account session must remain valid');
  await f.restart();
  assert.equal((await f.request(a, '/api/workspace')).status, 401, 'restart cannot revive recovered account sessions');
  assert.equal((await f.request(b, '/api/workspace')).status, 200);
  const oldPassword = await f.request(null, '/api/auth/login', { username: 'Alice', password: 'Acceptance-password-1' });
  assert.equal(oldPassword.status, 401);
  const recovered = await f.request(null, '/api/auth/login', { username: 'alice', password: 'Recovered-password-3' });
  assert.equal(recovered.status, 200);
  assert.equal(recovered.value.userId, a.userId, 'recovery preserves stable ownership identity');
  const current = { cookie: recovered.response.headers.get('set-cookie').split(';', 1)[0] };
  assert.deepEqual(await f.workspace(current), before);
  assert.equal((await f.sharing(current, list.id)).members.some(member => member.userId === b.userId), true);
  assert.equal((await f.workspace(b)).lists.some(entry => entry.id === list.id), true);
});
