import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, readFile, rm } from 'node:fs/promises';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { createUserStore } from '../server/store.mjs';
import { COVER_DELAY, createCoverWorker } from '../server/covers.mjs';
import { startServer } from '../server/index.js';

const photograph = await readFile(new URL('../public/list-hero.webp', import.meta.url));
const mockResponse = () => Response.json({ data: [{ b64_json: photograph.toString('base64') }] });

test('the durable cover queue waits, uses current list details, and gives a manual cover precedence', async t => {
  const dir = await mkdtemp(join(tmpdir(), 'gather-cover-queue-'));
  let store = createUserStore(dir, { generateCovers: true });
  let worker;
  t.after(async () => { worker?.close(); store.close(); await rm(dir, { recursive: true, force: true }); });
  const act = (type, payload) => store.action({ type, payload }, 'owner');
  const start = Date.now();
  const listId = act('list.create', { title: 'New list' }).createdListId;
  const deleted = act('list.create', { title: 'Accident' }).createdListId;
  const archived = act('list.create', { title: 'Archive' }).createdListId;
  act('list.delete', { listId: deleted });
  act('list.update', { listId: archived, archived: true });
  assert.equal(store.covers.claim(start + COVER_DELAY - 1, 50), undefined);
  store.close();
  store = createUserStore(dir, { generateCovers: true });
  act('list.update', { listId, title: 'Kyoto', description: 'Temple gardens' });
  act('item.create', { listId, text: 'private item' });
  const sent = [];
  let completeRequest;
  const env = { OPENAI_API_KEY: 'fixture' };
  worker = createCoverWorker(store.covers, env, async (url, init) => {
    sent.push(JSON.parse(init.body));
    assert.equal(url, 'https://api.openai.com/v1/images/generations');
    return new Promise(resolve => { completeRequest = resolve; });
  });
  const due = Date.now() + COVER_DELAY;
  const pending = worker.tick(due);
  assert.match(sent[0].prompt, /Kyoto/);
  assert.match(sent[0].prompt, /Temple gardens/);
  assert.doesNotMatch(sent[0].prompt, /private item/);
  store.covers.upload('owner', listId, photograph);
  const manualId = store.covers.id(listId);
  completeRequest(mockResponse());
  await pending;
  const clone = act('list.clone', { listId }).createdListId;
  assert.equal(store.covers.id(listId), manualId);
  assert.equal(store.covers.id(clone), manualId);
  assert.equal(store.covers.claim(due + 100_000, 50), undefined);
  worker.close();

  const newId = act('list.create', { title: 'Garden party' }).createdListId;
  worker = createCoverWorker(store.covers, env, mockResponse);
  await worker.tick(Date.now() + COVER_DELAY);
  assert.match(store.covers.id(newId), /^[a-f0-9]{64}$/);
  assert.equal(store.get('owner').lists.find(list => list.id === newId).coverId, store.covers.id(newId));
});

test('rate-limited jobs keep their place and respect the rolling request budget', async t => {
  const dir = await mkdtemp(join(tmpdir(), 'gather-cover-budget-'));
  const store = createUserStore(dir, { generateCovers: true });
  let calls = 0;
  const worker = createCoverWorker(store.covers, { OPENAI_API_KEY: 'fixture', GATHER_COVER_DAILY_LIMIT: 1 }, () => {
    calls++;
    return calls === 1 ? new Response(null, { status: 429 }) : mockResponse();
  });
  t.after(async () => { worker.close(); store.close(); await rm(dir, { recursive: true, force: true }); });
  const create = title => store.action({ type: 'list.create', payload: { title } }, 'owner').createdListId;
  const first = create('Tea');
  const due = Date.now() + COVER_DELAY;
  await worker.tick(due);
  const second = create('Coffee');
  await worker.tick(due + 60_000);
  assert.equal(calls, 1);
  await worker.tick(due + 86_400_000);
  await worker.tick(due + 2 * 86_400_000);
  assert.equal(calls, 3);
  assert.ok(store.covers.id(first));
  assert.ok(store.covers.id(second));
});

test('the upload and read endpoints enforce list membership, origin, type and size', async t => {
  const dir = await mkdtemp(join(tmpdir(), 'gather-cover-http-'));
  const server = await startServer({ dataDir: dir, port: 0, coverEnv: {}, authEnv: {}, searchEnv: { SEARCH_PROVIDER: 'fuzzy' } });
  t.after(async () => {
    await new Promise(resolve => server.close(resolve));
    await rm(dir, { recursive: true, force: true });
  });
  const base = 'http://127.0.0.1:' + server.address().port;
  async function post(path, value, cookie) {
    const response = await fetch(base + path, { method: 'POST', headers: { 'Content-Type': 'application/json', ...(cookie && { cookie }) }, body: JSON.stringify(value) });
    assert.equal(response.status, 200);
    return response;
  }
  const register = async username => (await post('/api/auth/register', { username, password: 'Test-image-12' })).headers.get('set-cookie').split(';')[0];
  const owner = await register('Photo-owner'), editor = await register('Photo-editor');
  const { createdListId: listId } = await (await post('/api/actions', { type: 'list.create', payload: { title: 'Packing' } }, owner)).json();
  const url = base + '/api/lists/' + listId + '/cover';
  const put = (cookie, bytes = photograph, extra = {}) => fetch(url, { method: 'PUT', headers: { cookie, 'content-type': 'image/webp', ...extra }, body: bytes });
  assert.equal((await fetch(url)).status, 401);
  assert.equal((await put(editor)).status, 404);
  assert.equal((await put(owner, photograph, { origin: 'https://other.invalid' })).status, 403);
  assert.equal((await put(owner, Buffer.from('invalid'))).status, 400);
  assert.equal((await put(owner, photograph, { 'content-type': 'image/svg+xml' })).status, 415);
  assert.equal((await put(owner, Buffer.alloc(2 * 1024 * 1024 + 1))).status, 413);
  const { token } = await (await post('/api/sharing/invite', { listId }, owner)).json();
  await post('/api/invitations/join', { token }, editor);
  const result = await put(editor);
  assert.equal(result.status, 200);
  const list = (await result.json()).lists.find(list => list.id === listId);
  assert.match(list.coverId, /^[a-f0-9]{64}$/);
  const cover = await fetch(url, { headers: { cookie: owner } });
  assert.equal(cover.status, 200);
  assert.equal(cover.headers.get('content-type'), 'image/webp');
  assert.match(cover.headers.get('cache-control'), /no-store/);
  assert.ok((await cover.arrayBuffer()).byteLength > 0);
  await post('/api/sharing/leave', { listId }, editor);
  assert.equal((await fetch(url, { headers: { cookie: editor } })).status, 404);
});
