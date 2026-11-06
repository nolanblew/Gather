import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { DatabaseSync } from 'node:sqlite';
import { createSearchService } from '../server/search.mjs';

const makeList = (id, title, extra = {}) => ({ id, title, description: '', categoryId: 'category', favorite: false, archived: false, isTemplate: false, position: 0, updatedAt: '2026-09-29T00:00:00Z', sections: [], items: [], ...extra });
const initial = () => ({ version: 2, categories: [{ id: 'category', name: 'Household' }], lists: [makeList('direct', 'Weekly groceries'), makeList('related', 'Shopping ideas')] });
const output = ids => new Response(JSON.stringify({ status: 'completed', output: [{ content: [{ type: 'output_text', text: JSON.stringify({ ids }) }] }] }));
async function fixture(t, options = {}) {
  const dir = await mkdtemp(join(tmpdir(), 'gather-review-'));
  let workspace = initial();
  const service = createSearchService({ dataDir: dir, getWorkspace: () => structuredClone(workspace), env: { SEARCH_PROVIDER: 'fuzzy' }, ...options });
  t.after(async () => { service.close(); await rm(dir, { recursive: true, force: true }); });
  return { service, dir, get: () => workspace, set: value => { workspace = value; } };
}

test('hosted in-flight same-timestamp replacement drops stale evidence', async t => {
  let release, started;
  const pending = new Promise(resolve => { release = resolve; });
  const requested = new Promise(resolve => { started = resolve; });
  const f = await fixture(t, { env: { SEARCH_PROVIDER: 'openai', OPENAI_API_KEY: 'fake' }, fetchImpl: async () => { started(); return pending; } });
  const result = f.service.search({ query: 'Weekly groceries' });
  await requested;
  const imported = structuredClone(f.get());
  imported.lists[0].title = 'Replacement content';
  imported.lists[0].description = 'Import keeps old timestamps deliberately';
  f.set(imported);
  release(output(['direct']));
  const response = await result;
  assert.ok(response.results.every(r => r.listId !== 'direct'), 'Replacement must invalidate the old source');
});

test('local indexing bounds each request and its retained vector cache', async t => {
  const batches = [];
  const vector = () => [1, ...Array(383).fill(0)];
  const embed = async texts => { batches.push(texts.length); return { tolist: () => texts.map(vector) }; };
  const f = await fixture(t, { env: { SEARCH_PROVIDER: 'local', SEARCH_INDEX_BATCH_LIMIT: '8', SEARCH_MAX_EMBEDDINGS: '128' }, loadEmbedder: async () => embed });
  f.get().lists[0].items = Array.from({ length: 100 }, (_, i) => ({ id: `item-${i}`, text: `Essential ${i}`, note: `Remember detail ${i}`, quantity: '', sectionId: null }));
  await f.service.search({ query: 'novel concept' });
  assert.ok(batches.reduce((n, size) => n + size, 0) <= 9, 'at most eight index chunks plus one query');
  for (let i = 0; i < 20; i++) await f.service.search({ query: `novel concept ${i}` });
  const db = new DatabaseSync(join(f.dir, 'search.sqlite'));
  try { assert.ok(db.prepare('SELECT COUNT(*) AS count FROM embeddings').get().count <= 128); }
  finally { db.close(); }
});

test('restarted service reuses persisted vectors without encoding documents again', async t => {
  let loads = 0, encoded = 0;
  const loader = async () => {
    loads++;
    const embed = async texts => { encoded += texts.length; return { tolist: () => texts.map(() => [1, ...Array(383).fill(0)]) }; };
    return embed;
  };
  const f = await fixture(t, { env: { SEARCH_PROVIDER: 'local' }, loadEmbedder: loader });
  await f.service.search({ query: 'household concept' });
  f.service.close();
  const reopened = createSearchService({ dataDir: f.dir, getWorkspace: () => structuredClone(f.get()), env: { SEARCH_PROVIDER: 'local' }, loadEmbedder: loader });
  const before = encoded;
  try { await reopened.search({ query: 'after restart' }); assert.equal(encoded - before, 1, 'restart reads persisted vectors'); assert.equal(loads, 2); }
  finally { reopened.close(); }
});

test('semantic coverage progresses beyond ten bounded requests for an active search', async t => {
  let encoded = 0;
  const embed = async texts => { encoded += texts.length; return { tolist: () => texts.map(() => [1, ...Array(383).fill(0)]) }; };
  const f = await fixture(t, { env: { SEARCH_PROVIDER: 'local', SEARCH_INDEX_BATCH_LIMIT: '8', SEARCH_MAX_EMBEDDINGS: '128' }, loadEmbedder: async () => embed });
  f.get().lists[0].items = Array.from({ length: 110 }, (_, i) => ({ id: `item-${i}`, text: `Supply ${i}`, note: '', quantity: '', sectionId: null }));
  const states = [];
  for (let i = 0; i < 15; i++) states.push((await f.service.search({ query: 'unrelated concept' })).status);
  assert.equal(states[10], 'warming', 'the old UI retry ceiling would stop before indexing finishes');
  assert.equal(states.at(-1), 'ready', 'continued bounded requests finish the scoped index');
  assert.ok(encoded >= 110, 'later item chunks were actually encoded');
});

test('capped semantic cache stops retry churn and refreshes a newly selected scope', async t => {
  let encoded = 0;
  const embed = async texts => { encoded += texts.length; return { tolist: () => texts.map(() => [1, ...Array(383).fill(0)]) }; };
  const f = await fixture(t, { env: { SEARCH_PROVIDER: 'local', SEARCH_INDEX_BATCH_LIMIT: '8', SEARCH_MAX_EMBEDDINGS: '128' }, loadEmbedder: async () => embed });
  f.get().lists[0].items = Array.from({ length: 150 }, (_, i) => ({ id: `item-${i}`, text: `Supply ${i}`, note: '', quantity: '', sectionId: null }));
  f.get().lists[1].favorite = true;
  let response;
  for (let i = 0; i < 20; i++) { response = await f.service.search({ query: 'unrelated concept' }); if (response.status === 'ready') break; }
  assert.equal(response.status, 'ready'); assert.equal(response.partial, true, 'coverage beyond the cap is reported as partial');
  const plateau = encoded;
  for (let i = 0; i < 3; i++) await f.service.search({ query: 'unrelated concept' });
  assert.equal(encoded - plateau, 3, 'a settled query only embeds its query, not evicted documents again');
  const beforeScope = encoded;
  const scoped = await f.service.search({ query: 'novel concept', scope: 'favorites' });
  assert.equal(scoped.status, 'ready');
  assert.ok(encoded - beforeScope >= 2, 'scope change loads the relevant document even with a full cache');
  f.get().lists[1].title = 'Edited shopping ideas';
  const edited = await f.service.search({ query: 'Edited shopping ideas', scope: 'favorites' });
  assert.equal(edited.results[0]?.listId, 'related');
  assert.equal(edited.results[0]?.match.text, 'Edited shopping ideas');
});

test('capped coverage stays warming until the query vector is actually available', async t => {
  let releaseQuery, holdQuery = true;
  const heldQuery = new Promise(resolve => { releaseQuery = resolve; });
  const embed = async texts => {
    if (texts[0] === 'slow concept' && holdQuery) await heldQuery;
    return { tolist: () => texts.map(() => [1, ...Array(383).fill(0)]) };
  };
  const f = await fixture(t, { env: { SEARCH_PROVIDER: 'local', SEARCH_INDEX_BATCH_LIMIT: '8', SEARCH_MAX_EMBEDDINGS: '128' }, loadEmbedder: async () => embed });
  f.get().lists[0].items = Array.from({ length: 150 }, (_, i) => ({ id: `item-${i}`, text: `Supply ${i}`, note: '', quantity: '', sectionId: null }));
  let result;
  for (let i = 0; i < 20; i++) { result = await f.service.search({ query: 'ordinary concept' }); if (result.status === 'ready') break; }
  assert.equal(result.status, 'ready'); assert.equal(result.partial, true);
  const slow = await f.service.search({ query: 'slow concept' });
  assert.equal(slow.status, 'warming', 'the capped index alone cannot claim semantic readiness');
  holdQuery = false; releaseQuery();
  await new Promise(resolve => setImmediate(resolve));
  const resumed = await f.service.search({ query: 'slow concept' });
  assert.equal(resumed.status, 'ready'); assert.equal(resumed.partial, true);
});
