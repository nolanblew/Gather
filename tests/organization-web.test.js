import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import test from 'node:test';
import { assignmentToken } from '../server/organization.mjs';
import { seedWorkspace } from '../server/seed.mjs';
import { validateBackup } from '../src/api.ts';
import { itemOrdinals, visibleItemOrder } from '../src/itemOrder.ts';
import { dictionaryDeletion, emptyFilters, filterDefaults, filterOptions, matchesListFilters, orderGuard, personFilter, placeFilter, placeSummary } from '../src/organization.ts';
import { sha256 } from '../src/sha256.ts';

const item = (id, position, fields = {}) => ({ id, position, checked: false, sectionId: null, text: id, note: '', quantity: '', ...fields });
const place = (id, name, fields = {}) => ({ id, name, kind: 'city', region: '', countryCode: '', ...fields });
const selected = (...placeIds) => ({ mode: 'selected', placeIds });

test('place options are scoped to assignments within one list, never to definitions or other lists', () => {
  const tokyo = place('tokyo', 'Tokyo');
  const lists = Array.from({ length: 100 }, (_, index) => ({ id: `list-${index}`, items: [item(`item-${index}`, 0)] }));
  for (const index of [16, 38]) {
    lists[index].places = [tokyo];
    lists[index].items[0].availability = selected(tokyo.id);
  }
  lists[51].places = [place('unused', 'Unused')];
  for (const [index, list] of lists.entries()) {
    const options = filterOptions(list);
    assert.deepEqual([...options.places], [16, 38].includes(index) ? ['tokyo'] : []);
    assert.equal(options.people.size, 0);
    assert.equal(options.hasChecked, false);
  }
});

test('named places include Anywhere, exclude unassigned, and intersect with person and completion', () => {
  const pen = item('pen', 0, { availability: selected('kyoto', 'tokyo'), recipientId: 'maya' });
  const anywhere = item('bag', 1, { availability: { mode: 'anywhere', placeIds: [] }, recipientId: 'alex' });
  const unknown = item('tea', 2, { note: 'Consider decaf', recipientId: 'maya' });
  const rows = [pen, anywhere, unknown];
  const kyoto = { ...emptyFilters, place: placeFilter('kyoto') };
  const tokyo = { ...emptyFilters, place: placeFilter('tokyo') };
  const maya = { ...kyoto, person: personFilter('maya') };
  assert.deepEqual(rows.filter(item => matchesListFilters(item, kyoto)), [pen, anywhere]);
  assert.deepEqual(rows.filter(item => matchesListFilters(item, tokyo)), [pen, anywhere]);
  assert.deepEqual(rows.filter(item => matchesListFilters(item, maya)), [pen]);
  assert.deepEqual(rows.filter(item => matchesListFilters(item, { ...emptyFilters, place: 'unassigned' }, 'decaf')), [unknown]);
  pen.checked = true;
  assert.deepEqual(rows.filter(item => matchesListFilters(item, { ...tokyo, notDone: true })), [anywhere]);
  assert.deepEqual(filterDefaults(maya), { availability: selected('kyoto'), recipientId: 'maya' });
  assert.deepEqual(filterDefaults(emptyFilters), { availability: { mode: 'unassigned', placeIds: [] }, recipientId: null });
  assert.equal(placeSummary(pen, new Map([['kyoto', place('kyoto', 'Kyoto')], ['tokyo', place('tokyo', 'Tokyo')]]), kyoto.place), 'Also Tokyo');
  assert.equal(placeSummary(anywhere, new Map(), kyoto.place), 'Anywhere');
});

test('numbering spans existing sections with stable ties, and moves include checked neighbors', () => {
  const list = {
    numbered: true, kind: 'checklist', orderToken: 'latest',
    sections: [{ id: 'last', position: 4 }, { id: 'first', position: 0 }, { id: 'empty', position: 0 }],
    items: [item('a', 0), item('held', 8, { checked: true }), item('b', 8), item('d', 4, { sectionId: 'last' }), item('c', 0, { sectionId: 'first' })],
  };
  const ordinals = itemOrdinals(list);
  assert.deepEqual([...ordinals], [['a', 1], ['held', 2], ['b', 3], ['c', 4], ['d', 5]]);
  assert.deepEqual(list.items.filter(item => matchesListFilters(item, { ...emptyFilters, notDone: true })).map(item => ordinals.get(item.id)).sort(), [1, 3, 4, 5]);
  assert.equal(visibleItemOrder(list, list.items[2]).targetIndex(-1), 1);
  assert.equal(visibleItemOrder(list, list.items[2]).targetIndex(1), null);
  assert.deepEqual(orderGuard(list), { expectedOrderToken: 'latest' });
  assert.deepEqual(orderGuard({ ...list, numbered: false }), {});
  assert.deepEqual(orderGuard({ ...list, numbered: false }, true), { expectedOrderToken: 'latest' });
});

test('LAN hashing and deletion tokens match the backend, including Unicode labels and reordered alternatives', () => {
  for (const value of ['', 'abc', 'Kyōto 🗾\u2028京都', 'x'.repeat(64), 'お茶'.repeat(4097)]) {
    assert.equal(sha256(value), createHash('sha256').update(value).digest('hex'));
  }
  const tokyo = place('tokyo', '東京 🗾\u2028', { countryCode: 'JP' });
  const maya = { id: 'maya', name: 'Maya Éva' };
  const list = {
    places: [tokyo, place('kyoto', 'Kyōto')], recipients: [maya],
    items: [item('z', 3, { availability: selected('tokyo', 'kyoto'), recipientId: maya.id }), item('a', 1, { availability: selected('tokyo') }), item('elsewhere', 4, { availability: selected('kyoto') })],
  };
  // Stored labels normalize whitespace before serialization.
  tokyo.name = tokyo.name.trim();
  assert.deepEqual(dictionaryDeletion(list, 'place', tokyo.id), { count: 2, token: assignmentToken(list, 'place', tokyo) });
  assert.deepEqual(dictionaryDeletion(list, 'recipient', maya.id), { count: 1, token: assignmentToken(list, 'recipient', maya) });
  list.items[0].availability.placeIds.reverse();
  assert.equal(dictionaryDeletion(list, 'place', tokyo.id).token, assignmentToken(list, 'place', tokyo));
});

test('v3 backup preflight accepts sparse metadata and imported duplicate labels, and rejects dangling or invalid assignments', () => {
  const backup = seedWorkspace();
  backup.version = 3;
  const list = backup.lists[0];
  list.numbered = true;
  list.places = [place('kyoto', 'Kyoto'), { id: 'kyoto-legacy', name: 'Kyoto' }];
  list.recipients = [{ id: 'a-person', name: 'Maya' }, { id: 'b-person', name: 'Maya' }];
  list.items[0].availability = selected('kyoto');
  list.items[0].recipientId = 'a-person';
  list.items[0].note = 'First paragraph.\n\nSecond paragraph.';
  assert.doesNotThrow(() => validateBackup(backup));
  for (const change of [doc => { doc.lists[0].numbered = null; }, doc => { doc.lists[0].items[0].availability = selected('other-list-place'); }, doc => { doc.lists[0].items[0].availability = { mode: 'anywhere', placeIds: ['kyoto'] }; }, doc => { doc.lists[0].items[0].recipientId = 'not-found'; }]) {
    const invalid = structuredClone(backup);
    change(invalid);
    assert.throws(() => validateBackup(invalid), /not a valid Gather backup/);
  }
});
