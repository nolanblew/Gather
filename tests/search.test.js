import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createSearchService, searchChunks } from '../server/search.mjs';

const list = (id, title, extra = {}) => ({ id, title, description: '', categoryId: 'home', favorite: false, archived: false, isTemplate: false, position: 0, updatedAt: '2026-09-29', sections: [], items: [], ...extra });
const workspace = () => ({ version: 2, categories: [{ id: 'home', name: 'Household' }, { id: 'work', name: 'Business' }], lists: [list('shopping', 'Weekly groceries', { items: [{ id: 'oat', text: 'Oat milk', note: 'unsweetened almond alternative', quantity: '2', sectionId: 'breakfast' }], sections: [{ id: 'breakfast', title: 'Breakfast staples' }] }), list('gift', 'Birthday gifts', { favorite: true, items: [{ id: 'mum', text: 'Garden supplies', note: 'Gardening present for mother', quantity: '', sectionId: null }] }), list('master', 'Weekly groceries master', { favorite: true, isTemplate: true }), list('old', 'Archived groceries', { archived: true }), list('office', 'Office supplies', { categoryId: 'work' })] });
async function fixture(t, options = {}) {
  const dir = await mkdtemp(join(tmpdir(), 'gather-search-'));
  let value = workspace();
  const service = createSearchService({ dataDir: dir, getWorkspace: () => structuredClone(value), env: { SEARCH_PROVIDER: 'fuzzy' }, ...options });
  t.after(async () => { service.close(); await rm(dir, { recursive: true, force: true }); });
  return { service, set: next => { value = next; }, get: () => value, dir };
}

test('fuzzy transpositions, note, section and category fields; strict collection filtering', async t => {
  const { service } = await fixture(t);
  assert.equal((await service.search({ query: 'grocreis' })).results[0].listId, 'shopping');
  assert.equal((await service.search({ query: 'gardning present' })).results[0].listId, 'gift');
  assert.equal((await service.search({ query: 'almond alternative' })).results[0].match.field, 'note');
  assert.equal((await service.search({ query: 'Breakfast staples' })).results[0].listId, 'shopping');
  assert.ok((await service.search({ query: 'Household' })).results.every(r => r.listId !== 'office'));
  assert.deepEqual((await service.search({ query: 'groceries', scope: 'templates' })).results.map(r => r.listId), ['master']);
  assert.deepEqual((await service.search({ query: 'groceries', scope: 'archive' })).results.map(r => r.listId), ['old']);
  assert.deepEqual((await service.search({ query: 'groceries', scope: 'favorites' })).results, []);
  assert.deepEqual((await service.search({ query: 'groceries', categoryId: 'work', scope: 'all' })).results, []);
  assert.equal((await service.search({ query: 'Weekly groceries' })).results[0].matchType, 'exact');
});

test('long-note tail survives chunking and edited/deleted/imported content is fresh', async t => {
  const f = await fixture(t);
  f.get().lists[0].items[0].note = 'Ordinary supplies. '.repeat(200) + 'Buy protective footwear for icy alpine climbing';
  assert.ok(searchChunks(f.get()).filter(c => c.field === 'note').length > 2);
  assert.equal((await f.service.search({ query: 'protective footwear' })).results[0].match.itemId, 'oat');
  f.get().lists[0].items[0].note = 'replacement note';
  assert.deepEqual((await f.service.search({ query: 'protective footwear' })).results, []);
  f.set({ ...f.get(), lists: [] });
  assert.deepEqual((await f.service.search({ query: 'groceries' })).results, []);
});

test('local failure degrades safely and fuzzy mode never loads a model', async t => {
  let loads = 0;
  const local = await fixture(t, { env: { SEARCH_PROVIDER: 'local' }, loadEmbedder: async () => { loads++; throw new Error('secret upstream failure'); } });
  const result = await local.service.search({ query: 'grocreis' });
  assert.equal(loads, 1); assert.equal(result.status, 'degraded'); assert.equal(result.results[0].listId, 'shopping');
  assert.ok(!JSON.stringify(result).includes('secret'));
  const fuzzy = await fixture(t, { loadEmbedder: async () => { throw new Error('Must never load'); } });
  assert.equal((await fuzzy.service.search({ query: 'groceries' })).status, 'ready');
});

test('negative searches return no matches and malformed inputs are rejected', async t => {
  const { service } = await fixture(t);
  for (const query of ['quantum chromodynamics tensor calculus', 'volcano eruption monitoring station', 'medieval heraldry coat of arms', 'zzqxvplr', 'hydraulic submarine torpedo repair', '']) assert.deepEqual((await service.search({ query })).results, []);
  await assert.rejects(service.search({ query: 'x'.repeat(501) }), /500/);
  await assert.rejects(service.search({ query: 'test', scope: 'unknown' }), /scope/);
  await assert.rejects(service.search({ query: 'test', categoryId: 'unknown' }), /Category/);
});

test('hosted search uses bounded strict ID schema, correct effort, validated IDs, and no key means no request', async t => {
  let request;
  const f = await fixture(t, { env: { SEARCH_PROVIDER: 'openai', OPENAI_API_KEY: 'test-key', SEARCH_REASONING_EFFORT: 'medium' }, fetchImpl: async (url, options) => { request = { url, ...JSON.parse(options.body) }; return new Response(JSON.stringify({ status: 'completed', output: [{ content: [{ type: 'output_text', text: JSON.stringify({ ids: ['gift'] }) }] }] })); } });
  const response = await f.service.search({ query: 'present for mom', scope: 'favorites' });
  assert.deepEqual(response.results.map(r => r.listId), ['gift']); assert.equal(request.model, 'gpt-6-luna'); assert.equal(request.reasoning.effort, 'medium'); assert.equal(request.store, false); assert.equal(request.text.format.strict, true);
  assert.deepEqual(request.text.format.schema.properties.ids.items.enum, ['gift']);
  const invalid = await fixture(t, { env: { SEARCH_PROVIDER: 'openai', OPENAI_API_KEY: 'test-key' }, fetchImpl: async () => new Response(JSON.stringify({ output: [{ content: [{ type: 'output_text', text: '{"ids":["outside"]}' }] }] })) });
  assert.equal((await invalid.service.search({ query: 'groceries' })).status, 'degraded');
  const missing = await fixture(t, { env: { SEARCH_PROVIDER: 'openai' }, fetchImpl: async () => { throw new Error('Must not call without key'); } });
  assert.equal((await missing.service.search({ query: 'groceries' })).status, 'degraded');
});

test('local single-flight loading, persistent vector cache, and freshness after async index', async t => {
  let calls = 0;
  const vector = () => { const values = Array(384).fill(0); values[0] = 1; return values; };
  const embed = async texts => { calls += texts.length; await new Promise(r => setTimeout(r, 2)); return { tolist: () => texts.map(vector) }; };
  const f = await fixture(t, { env: { SEARCH_PROVIDER: 'local' }, loadEmbedder: async () => embed });
  await f.service.search({ query: 'novel concept' });
  const after = calls;
  await f.service.search({ query: 'another concept' });
  assert.equal(calls - after, 1, 'unchanged chunk embeddings are reused');
  f.get().lists = f.get().lists.filter(l => l.id !== 'shopping');
  assert.ok((await f.service.search({ query: 'novel concept' })).results.every(r => r.listId !== 'shopping'));
});

test('hosted omission cannot hide exact results, and sequential distinct calls are rate limited', async t => {
  let calls = 0;
  const f = await fixture(t, { env: { SEARCH_PROVIDER: 'openai', OPENAI_API_KEY: 'test-key' }, fetchImpl: async () => { calls++; return new Response(JSON.stringify({ output: [{ content: [{ type: 'output_text', text: '{"ids":[]}' }] }] })); } });
  const first = await f.service.search({ query: 'Weekly groceries' });
  assert.equal(first.results[0].listId, 'shopping'); assert.equal(first.results[0].matchType, 'exact');
  const second = await f.service.search({ query: 'Birthday gifts' });
  assert.equal(second.results[0].listId, 'gift'); assert.equal(second.status, 'degraded'); assert.equal(f.service.status().state, 'degraded'); assert.equal(calls, 1);
});

test('hosted source freshness detects category rename even when list timestamps are unchanged', async t => {
  let release, signal;
  const started = new Promise(resolve => { signal = resolve; });
  const f = await fixture(t, { env: { SEARCH_PROVIDER: 'openai', OPENAI_API_KEY: 'test-key' }, fetchImpl: async () => { signal(); await new Promise(resolve => { release = resolve; }); return new Response(JSON.stringify({ output: [{ content: [{ type: 'output_text', text: '{"ids":["shopping"]}' }] }] })); } });
  const request = f.service.search({ query: 'Household' });
  await started; f.get().categories[0].name = 'Renamed'; release();
  assert.deepEqual((await request).results, []);
});

test('invalid provider never loads/downloads and model is disposed after bounded idle time', async t => {
  const invalid = await fixture(t, { env: { SEARCH_PROVIDER: 'typo' }, loadEmbedder: async () => { throw new Error('Unexpected load'); } });
  assert.equal((await invalid.service.search({ query: 'groceries' })).provider, 'fuzzy'); assert.equal(invalid.service.status().state, 'degraded');
  let disposed = 0, loads = 0;
  const embed = async texts => ({ tolist: () => texts.map(() => { const v = Array(384).fill(0); v[0] = 1; return v; }) });
  embed.dispose = async () => { disposed++; };
  const f = await fixture(t, { env: { SEARCH_PROVIDER: 'local', SEARCH_IDLE_MS: '1000' }, loadEmbedder: async () => { loads++; return embed; } });
  assert.equal(loads, 0, 'construction does not load the model');
  await f.service.search({ query: 'groceries' });
  await new Promise(resolve => setTimeout(resolve, 1050));
  assert.equal(disposed, 1); assert.equal(f.service.status().state, 'warming');
  await f.service.search({ query: 'groceries' }); assert.equal(loads, 2);
});
