// Explicit real-model evaluation; kept separate from fast/offline unit tests.
import assert from 'node:assert/strict';
import { mkdirSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { performance } from 'node:perf_hooks';
import { createSearchService } from '../server/search.mjs';

const documents = [
  ['travel', 'Japan travel checklist', 'Passport renewal; power adapter for Tokyo; book airport train.'],
  ['camp', 'Camping weekend', 'Tent, sleeping bag, headlamp; waterproof jacket; stove fuel.'],
  ['groceries', 'Weekly groceries', 'Oat milk; eggs; spinach; tomatoes; bread; coffee beans.'],
  ['gifts', 'Birthday gifts', 'Mum likes gardening; ceramic plant pot; gift for dad: noise cancelling headphones.'],
  ['repairs', 'House repairs', 'Fix dripping kitchen faucet; replace leaking tap washer; paint hall wall.'],
  ['exercise', 'Fitness routine', 'Strength training at gym; running shoes; outdoor bicycle rides.'],
  ['work', 'Work deadlines', 'Submit quarterly expense report; send project proposal to manager.'],
  ['movie', 'Film watchlist', 'Spirited Away; atmospheric animation for a relaxed evening.'],
  ['dinner', 'Dinner ideas', 'Vegetarian curry with chickpeas and coconut milk; quick meatless supper.'],
  ['medicine', 'Health reminders', 'Dental appointment; pick up prescription at pharmacy.'],
];
const queries = [
  ['Japan', 'travel', 'exact'], ['headlamp', 'camp', 'exact'], ['oat milk', 'groceries', 'exact'], ['expense report', 'work', 'exact'], ['chickpeas', 'dinner', 'exact'],
  ['pasport renewal', 'travel', 'fuzzy'], ['campng weekend', 'camp', 'fuzzy'], ['gardning present', 'gifts', 'fuzzy'], ['leeking faucet', 'repairs', 'fuzzy'], ['prescrption pharmacy', 'medicine', 'fuzzy'],
  ['things for sleeping outdoors', 'camp', 'semantic'], ['something to block sound for father', 'gifts', 'semantic'], ['food without meat for tonight', 'dinner', 'semantic'], ['repair a dripping sink', 'repairs', 'semantic'], ['documents needed for international trip', 'travel', 'semantic'], ['buy breakfast staples', 'groceries', 'semantic'], ['reimburse business costs', 'work', 'semantic'], ['Japanese cartoon movie', 'movie', 'semantic'], ['stay fit and get stronger', 'exercise', 'semantic'], ['remember to collect medication', 'medicine', 'semantic'],
];
const negatives = ['quantum chromodynamics tensor calculus', 'volcano eruption monitoring station', 'medieval heraldry coat of arms', 'zzqxvplr', 'hydraulic submarine torpedo repair'];
const workspace = { version: 2, categories: [{ id: 'personal', name: 'Personal' }], lists: documents.map(([id, title, description], position) => ({ id, title, description, categoryId: 'personal', archived: false, favorite: false, isTemplate: false, updatedAt: '2026-09-29', position, sections: [], items: [] })) };
workspace.lists[0].sections = [{ id: 'travel-section', title: 'Overseas paperwork' }];
workspace.lists[0].items = [{ id: 'tail-item', sectionId: 'travel-section', text: 'Mountaineering kit', quantity: 'One pair', note: 'Ordinary supplies. '.repeat(150) + 'Buy protective footwear for icy alpine climbing' }];
const output = resolve(process.env.SEARCH_EVAL_DIR || 'output/search-eval/integrated');
mkdirSync(output, { recursive: true });
const search = createSearchService({ dataDir: output, getWorkspace: () => structuredClone(workspace), env: { ...process.env, SEARCH_PROVIDER: 'local', SEARCH_MODEL_CACHE_DIR: process.env.SEARCH_MODEL_CACHE_DIR || resolve('output/search-eval/model-cache') } });
const started = performance.now();
try {
  let warm;
  for (let attempt = 0; attempt < 20; attempt++) { warm = await search.search({ query: 'headlamp', scope: 'all' }); if (warm.status === 'ready') break; await new Promise(r => setTimeout(r, 100)); }
  assert.equal(warm.status, 'ready', 'real model must initialize and complete this small fixture');
  const rows = [];
  for (const [query, expected, kind] of queries) {
    const begin = performance.now(), value = await search.search({ query, scope: 'all' });
    rows.push({ query, expected, kind, rank: value.results.findIndex(r => r.listId === expected) + 1 || null, ms: Math.round(performance.now() - begin), results: value.results.slice(0, 3) });
  }
  const negativeResults = [];
  for (const query of negatives) { const value = await search.search({ query, scope: 'all' }); negativeResults.push({ query, results: value.results }); }
  const tail = await search.search({ query: 'ice climbing boots', scope: 'all' });
  const section = await search.search({ query: 'Overseas paperwork', scope: 'all' });
  const category = await search.search({ query: 'Personal', scope: 'all' });
  const metrics = { node: process.version, elapsedMs: Math.round(performance.now() - started), rssMiB: Math.round(process.memoryUsage().rss / 1048576), exactTop1: rows.filter(r => r.kind === 'exact' && r.rank === 1).length, fuzzyTop3: rows.filter(r => r.kind === 'fuzzy' && r.rank !== null && r.rank <= 3).length, semanticTop1: rows.filter(r => r.kind === 'semantic' && r.rank === 1).length, semanticTop3: rows.filter(r => r.kind === 'semantic' && r.rank !== null && r.rank <= 3).length, negativesEmpty: negativeResults.filter(r => !r.results.length).length, tail: tail.results.slice(0, 3), section: section.results[0], categoryCount: category.results.length };
  writeFileSync(resolve(output, 'report.json'), JSON.stringify({ metrics, rows, negativeResults }, null, 2));
  console.log(JSON.stringify(metrics, null, 2));
  assert.equal(metrics.exactTop1, 5); assert.equal(metrics.fuzzyTop3, 5); assert.ok(metrics.semanticTop1 >= 8); assert.ok(metrics.semanticTop3 >= 9); assert.equal(metrics.negativesEmpty, 5); assert.equal(tail.results[0]?.match.itemId, 'tail-item'); assert.equal(section.results[0]?.listId, 'travel'); assert.equal(category.results.length, 10);
} finally { search.close(); }
