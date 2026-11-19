import test from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { DatabaseSync } from 'node:sqlite';
import { createUserStore, WORKSPACE_BYTE_LIMIT } from '../server/store.mjs';

const owner = 'owner';
const editor = 'editor';
const unassigned = { mode: 'unassigned', placeIds: [] };
const selected = (...placeIds) => ({ mode: 'selected', placeIds });
const sha256 = value => createHash('sha256').update(JSON.stringify(value)).digest('hex');
const byLexicalId = (a, b) => a[0] < b[0] ? -1 : a[0] > b[0] ? 1 : 0;

function deletionToken(list, type, entry) {
  const pairs = type === 'place' ? list.items.filter(item => item.availability?.placeIds.includes(entry.id))
    .map(item => [item.id, selected(...item.availability.placeIds.toSorted())]) :
    list.items.filter(item => item.recipientId === entry.id || item.recipientIds?.includes(entry.id)).map(item => [item.id, entry.id]);
  return sha256([type, entry.id, entry, pairs.sort(byLexicalId)]);
}

async function fixture(t) {
  const dir = await mkdtemp(join(tmpdir(), 'gather-organization-'));
  let store = createUserStore(dir);
  const close = () => { store?.close(); store = null; };
  t.after(async () => { close(); await rm(dir, { recursive: true, force: true }); });
  const act = (type, payload, user = owner) => store.action({ type, payload }, user);
  const listId = act('list.create', { title: 'Packing list' }).createdListId;
  return { dir, get store() { return store; }, close, reopen: () => { close(); store = createUserStore(dir); }, act, listId,
    list: (user = owner) => store.get(user).lists.find(list => list.id === listId) };
}

test('numbered structural commands guard the full canonical order and preserve legacy scalar edits', async t => {
  const { act, listId, list } = await fixture(t);
  const actOnList = (type, fields) => act(type, { listId, ...fields });
  const first = actOnList('item.create', { text: 'Camera', checked: true }).createdItemId;
  const second = actOnList('item.create', { text: 'Charger' }).createdItemId;
  actOnList('section.create', { title: 'Carry on' });
  actOnList('section.create', { title: 'Empty section' });
  const [carryOn, empty] = list().sections.map(section => section.id);
  const third = actOnList('item.create', { text: 'Passport', sectionId: carryOn }).createdItemId;
  const plain = list().orderToken;
  assert.equal(plain, sha256([listId, false, [carryOn, empty], [[first, second], [third], []]]));
  assert.throws(() => actOnList('list.update', { numbered: true }), { status: 409, code: 'ORDER_TOKEN_REQUIRED' });
  assert.throws(() => actOnList('list.update', { numbered: true, expectedOrderToken: 'old' }), { status: 409, code: 'ORDER_CHANGED' });
  actOnList('list.update', { numbered: true, expectedOrderToken: plain });
  const numbered = list().orderToken;
  assert.equal(numbered, sha256([listId, true, [carryOn, empty], [[first, second], [third], []]]));

  for (const [type, fields] of [
    ['item.create', { text: 'Phone' }], ['item.delete', { itemId: first }],
    ['item.move', { itemId: second, sectionId: null, index: 0 }],
    ['item.update', { itemId: first, sectionId: carryOn }],
    ['section.create', { title: 'Checked luggage' }], ['section.delete', { sectionId: carryOn }],
    ['section.move', { sectionId: carryOn, index: 1 }],
  ]) {
    assert.throws(() => actOnList(type, fields), { status: 409, code: 'ORDER_TOKEN_REQUIRED' });
    assert.equal(list().orderToken, numbered);
  }
  actOnList('item.update', { itemId: first, text: 'Digital camera', checked: false, sectionId: null });
  actOnList('section.update', { sectionId: carryOn, title: 'Carry-on' });
  actOnList('list.reset');
  assert.equal(list().orderToken, numbered);
  actOnList('item.move', { itemId: second, sectionId: null, index: 0, expectedOrderToken: numbered });
  const moved = list().orderToken;
  assert.equal(moved, sha256([listId, true, [carryOn, empty], [[second, first], [third], []]]));
  assert.throws(() => actOnList('item.delete', { itemId: third, expectedOrderToken: numbered }), { status: 409, code: 'ORDER_CHANGED' });
  actOnList('section.delete', { sectionId: carryOn, expectedOrderToken: moved });
  assert.equal(list().orderToken, sha256([listId, true, [empty], [[second, first, third], []]]));
  actOnList('list.update', { numbered: false, expectedOrderToken: list().orderToken });
  assert.equal(list().numbered, undefined);
  actOnList('item.create', { text: 'Phone' });
});

test('inline definitions, independent assignment expectations and guarded dictionary deletion are atomic', async t => {
  const { store, act, listId, list } = await fixture(t);
  const actOnList = (type, fields, actor) => act(type, { listId, ...fields }, actor);
  const before = list();
  assert.throws(() => actOnList('item.create', {
    text: 'Gift', availability: selected(), newPlaces: [{ name: 'Kyoto' }], newRecipient: { name: '   ' },
  }), /Enter a name/);
  assert.deepEqual(list(), before);

  const invitation = store.invite(owner, listId);
  store.join(editor, invitation.token);
  assert.throws(() => actOnList('list.update', { numbered: true, expectedOrderToken: list().orderToken }, editor), { status: 403 });
  assert.throws(() => actOnList('place.create', { name: 12 }, 'outsider'), { status: 404 });
  const created = actOnList('item.create', {
    text: 'Gift', checked: true, note: 'First line.\n\nSecond line.', availability: selected(),
    newPlaces: [{ name: '  Pike\tPlace  ', kind: 'store', region: 'Seattle', countryCode: 'US' }],
    newRecipient: { name: '  Maya  ' },
  }, editor);
  const { createdItemId: itemId } = created;
  const [pike] = list().places;
  const [maya] = list().recipients;
  assert.deepEqual(pike, { id: pike.id, name: 'Pike Place', kind: 'store', region: 'Seattle', countryCode: 'US' });
  assert.deepEqual(list().items[0].availability, selected(pike.id));
  assert.equal(list().items[0].recipientId, maya.id);
  const beforeAssignment = list().orderToken;

  const { createdPlaceId: osaka } = actOnList('place.create', { name: 'Osaka', kind: 'city' });
  actOnList('item.update', { itemId, availability: selected(osaka, pike.id), expectedAvailability: selected(pike.id) });
  assert.throws(() => actOnList('item.update', {
    itemId, text: 'Would overwrite gift', availability: selected(osaka), newPlaces: [{ name: 'Toronto' }], expectedAvailability: selected(pike.id),
  }, editor), { status: 409, code: 'ASSIGNMENT_CHANGED' });
  assert.equal(list().places.length, 2);
  assert.equal(list().items[0].text, 'Gift');
  assert.throws(() => actOnList('item.update', { itemId, recipientId: null }), { status: 409, code: 'ASSIGNMENT_CHANGED' });
  actOnList('item.update', { itemId, text: 'Market gift' });

  const expectedAssignmentToken = deletionToken(list(), 'place', pike);
  const afterChange = actOnList('item.update', {
    itemId, availability: selected(), newPlaces: [{ name: 'pike place' }], expectedAvailability: selected(...[osaka, pike.id].sort().reverse()),
  });
  assert.equal(afterChange.lists[0].places.length, 2, 'reuse the one existing name even with qualifiers omitted');
  assert.throws(() => actOnList('place.delete', { placeId: pike.id, expectedAssignmentToken }, editor), { status: 409, code: 'ASSIGNMENT_CHANGED' });
  actOnList('place.delete', { placeId: pike.id, expectedAssignmentToken: deletionToken(list(), 'place', pike) }, editor);
  actOnList('recipient.delete', { recipientId: maya.id, expectedAssignmentToken: deletionToken(list(), 'recipient', maya) }, editor);
  assert.equal(list().orderToken, beforeAssignment);
  assert.deepEqual(list().items[0], {
    id: itemId, position: 0, sectionId: null, text: 'Market gift', quantity: '', note: 'First line.\n\nSecond line.', checked: true,
  });

  actOnList('place.create', { name: 'Osaka', kind: 'store', region: 'Chicago' });
  assert.throws(() => actOnList('item.update', { itemId, availability: selected(), newPlaces: [{ name: 'Osaka' }], expectedAvailability: unassigned }), /More than one place matches/);
  assert.throws(() => actOnList('place.create', { name: '  OSAKA ', kind: 'city' }), /This place already exists/);
  const foreign = act('list.create', { title: 'Another list' }).createdListId;
  const { createdPlaceId: foreignPlace } = act('place.create', { listId: foreign, name: 'Foreign place' });
  assert.throws(() => actOnList('item.create', { text: 'Bad reference', availability: selected(foreignPlace) }), /Place is no longer available/);
  assert.equal(list().items.length, 1);
});

test('multiple recipients retain legacy singleton compatibility and guarded member deletion', async t => {
  const { act, listId, list, store } = await fixture(t);
  const onList = (type, fields) => act(type, { listId, ...fields });
  const maya = onList('recipient.create', { name: 'Maya' }).createdRecipientId;
  const alex = onList('recipient.create', { name: 'Alex' }).createdRecipientId;
  const itemId = onList('item.create', { text: 'Gift', recipientIds: [maya] }).createdItemId;
  assert.equal(list().items[0].recipientId, maya);
  assert.equal(list().items[0].recipientIds, undefined);
  onList('item.update', { itemId, recipientIds: [maya, alex], expectedRecipientIds: [maya] });
  assert.deepEqual(list().items[0].recipientIds, [maya, alex].sort());
  assert.equal(list().items[0].recipientId, undefined);
  const before = list();
  assert.throws(() => onList('item.update', { itemId, recipientId: null, expectedRecipientId: null }), { status: 409 });
  assert.deepEqual(list(), before);
  const original = store.export(owner);
  const copy = act('list.clone', { listId }).lists.find(value => value.id !== listId);
  assert.deepEqual(copy.items[0].recipientIds, copy.recipients.map(person => person.id).sort());
  assert.deepEqual(original.lists[0].items[0].recipientIds, [maya, alex].sort());
  const mayaEntry = list().recipients.find(person => person.id === maya);
  const token = deletionToken(list(), 'recipient', mayaEntry);
  onList('recipient.delete', { recipientId: maya, expectedAssignmentToken: token });
  assert.equal(list().items[0].recipientId, alex);
  assert.equal(list().items[0].recipientIds, undefined);

  const backup = store.export(owner);
  assert.equal(backup.version, 4);
  const v3 = structuredClone(backup);
  v3.version = 3;
  v3.lists = v3.lists.filter(value => value.id === listId);
  const imported = store.import(v3, owner).lists.find(value => value.id !== listId && value.id !== copy.id);
  assert.equal(imported.items[0].recipientId, imported.recipients.find(person => person.name === 'Alex').id);
});

test('bulk additions union assignments and reject invalid selections atomically', async t => {
  const { act, listId, list } = await fixture(t);
  const onList = (type, fields) => act(type, { listId, ...fields });
  const maya = onList('recipient.create', { name: 'Maya' }).createdRecipientId;
  const alex = onList('recipient.create', { name: 'Alex' }).createdRecipientId;
  const kyoto = onList('place.create', { name: 'Kyoto', kind: 'city' }).createdPlaceId;
  const first = onList('item.create', { text: 'Gift', recipientId: alex, availability: { mode: 'anywhere', placeIds: [] } }).createdItemId;
  const second = onList('item.create', { text: 'Tea', recipientId: maya }).createdItemId;
  onList('item.bulkAdd', { itemIds: [first, second], recipientIds: [maya], placeIds: [kyoto] });
  const [gift, tea] = list().items;
  assert.deepEqual(gift.recipientIds, [alex, maya].sort());
  assert.equal(tea.recipientId, maya);
  assert.deepEqual(gift.availability, selected(kyoto));
  assert.deepEqual(tea.availability, selected(kyoto));
  const before = list();
  assert.throws(() => onList('item.bulkAdd', { itemIds: [first, 'missing'], newRecipients: [{ name: 'New person' }] }));
  assert.deepEqual(list(), before);
  onList('item.update', { itemId: second, availability: { mode: 'anywhere', placeIds: [] }, expectedAvailability: selected(kyoto) });
  const concurrent = list();
  assert.throws(() => onList('item.bulkAdd', { itemIds: [first, second], placeIds: [kyoto], expectedAnywhereItemIds: [] }), { status: 409 });
  assert.deepEqual(list(), concurrent);
  const payload = { itemIds: [first, second], placeIds: [kyoto], expectedAnywhereItemIds: [second] };
  onList('item.bulkAdd', payload);
  assert.equal(list().items.find(item => item.id === second).availability.mode, 'selected');
  assert.doesNotThrow(() => onList('item.bulkAdd', payload));
});

test('visible reordering replaces only selected slots and guards the full order', async t => {
  const { act, listId, list } = await fixture(t);
  const onList = (type, fields) => act(type, { listId, ...fields });
  const ids = ['A', 'Hidden', 'B', 'C'].map(text => onList('item.create', { text }).createdItemId);
  const token = list().orderToken;
  onList('item.reorderVisible', { itemIds: [ids[3], ids[0], ids[2]], expectedOrderToken: token });
  const order = () => list().items.filter(item => item.sectionId === null).toSorted((a, b) => a.position - b.position).map(item => item.id);
  assert.deepEqual(order(), [ids[3], ids[1], ids[0], ids[2]]);
  assert.throws(() => onList('item.reorderVisible', { itemIds: [ids[0], ids[2]], expectedOrderToken: token }), { status: 409 });
  for (const invalid of [[ids[0], ids[0]], [ids[0], 'missing']]) {
    assert.throws(() => onList('item.reorderVisible', { itemIds: invalid, expectedOrderToken: list().orderToken }));
  }
  onList('section.create', { title: 'Other' });
  const sectionId = list().sections[0].id;
  const otherId = onList('item.create', { text: 'Other', sectionId }).createdItemId;
  assert.throws(() => onList('item.reorderVisible', { itemIds: [ids[0], otherId], expectedOrderToken: list().orderToken }));
  assert.deepEqual(order(), [ids[3], ids[1], ids[0], ids[2]]);
});

test('clone and backup copies preserve organization through remapped definitions and references', async t => {
  const { store, act, listId, list } = await fixture(t);
  let current = list();
  act('list.update', { listId, numbered: true, expectedOrderToken: current.orderToken });
  act('section.create', { listId, title: 'Selection', expectedOrderToken: list().orderToken });
  act('item.create', { listId, text: 'Packing cubes', checked: true, sectionId: list().sections[0].id, expectedOrderToken: list().orderToken,
    availability: selected(), newPlaces: [{ name: 'Online', kind: 'online' }, { name: 'Home', kind: 'home' }], newRecipient: { name: 'Maya' } });
  current = list();
  const invitation = store.invite(owner, listId);
  store.join(editor, invitation.token);
  const cloned = act('list.clone', { listId }, editor);
  const clone = cloned.lists.find(l => l.id === cloned.createdListId);
  const backup = store.export(owner);
  assert.equal(backup.version, 4);
  assert.equal(backup.lists[0].orderToken, undefined);
  assert.equal(backup.lists[0].role, undefined);
  const imported = store.import(backup, editor).lists.find(l => l.id !== listId && l.id !== clone.id);
  for (const copy of [clone, imported]) {
    assert.equal(copy.numbered, true);
    assert.equal(copy.role, 'owner');
    assert.equal(copy.shared, false);
    assert.equal(copy.items[0].checked, copy === imported);
    assert.notEqual(copy.sections[0].id, current.sections[0].id);
    assert.equal(copy.items[0].sectionId, copy.sections[0].id);
    assert.notEqual(copy.items[0].id, current.items[0].id);
    assert.deepEqual(copy.items[0].availability, selected(...copy.places.map(p => p.id).sort()));
    assert.ok(copy.places.every(p => current.places.some(original => original.name === p.name && original.id !== p.id)));
    assert.notEqual(copy.recipients[0].id, current.recipients[0].id);
    assert.equal(copy.items[0].recipientId, copy.recipients[0].id);
  }
  const validBackup = store.export(owner);
  for (const edit of [
    l => { l.numbered = null; }, l => { l.places = null; },
    l => { l.places[0].countryCode = 'us'; }, l => { l.places[0].kind = 'warehouse'; },
    l => { l.places[0].id = l.recipients[0].id; },
    l => { l.items[0].availability = null; }, l => { l.items[0].availability = selected(); },
    l => { l.items[0].availability = selected(l.places[0].id, l.places[0].id); },
    l => { l.items[0].availability = { mode: 'anywhere', placeIds: [l.places[0].id] }; },
    l => { l.items[0].recipientId = 'missing-person'; },
  ]) {
    const invalid = structuredClone(validBackup);
    edit(invalid.lists[0]);
    assert.throws(() => store.import(invalid, owner), { status: 400 });
    assert.deepEqual(store.export(owner), validBackup);
  }
});

test('v2 account migration is transactional and preserves sparse documents near the byte limit', async t => {
  const f = await fixture(t);
  const { listId, act } = f;
  const categoryId = f.store.get(owner).categories[0].id;
  act('list.update', { listId, categoryId, favorite: true, position: 5 });
  const invitation = f.store.invite(owner, listId);
  f.store.join(editor, invitation.token);
  act('list.update', { listId, archived: true, position: 10 }, editor);
  f.close();

  const db = new DatabaseSync(join(f.dir, 'gather.sqlite'));
  const read = db.prepare('SELECT document FROM user_workspaces WHERE user_id=?');
  const update = db.prepare('UPDATE user_workspaces SET document=? WHERE user_id=?');
  const legacy = JSON.parse(read.get(owner).document);
  legacy.version = 2;
  legacy.lists[0].items = Array.from({ length: 1350 }, (_, index) => ({ id: `legacy-${index}`, position: 3 * index, sectionId: null, text: 'Archived detail', quantity: '', note: '', checked: index === 0 }));
  let remaining = WORKSPACE_BYTE_LIMIT - Buffer.byteLength(JSON.stringify(legacy)) - 200;
  for (const item of legacy.lists[0].items) {
    const size = Math.min(4000, remaining);
    item.note = 'x'.repeat(size);
    remaining -= size;
  }
  assert.equal(remaining, 0);
  const saved = JSON.stringify(legacy);
  const emptyAccount = { ...JSON.parse(read.get(editor).document), version: 2 };
  update.run(saved, owner);
  update.run(JSON.stringify({ ...emptyAccount, lists: [{}] }), editor);
  db.prepare('DELETE FROM user_migrations WHERE name=?').run('workspace-v4');
  assert.throws(() => createUserStore(f.dir), { status: 400 });
  assert.equal(read.get(owner).document, saved);
  assert.equal(db.prepare("SELECT 1 FROM user_migrations WHERE name='workspace-v4'").get(), undefined);
  update.run(JSON.stringify(emptyAccount), editor);
  db.close();

  f.reopen();
  const backup = f.store.export(owner);
  assert.equal(backup.version, 4);
  assert.ok(Buffer.byteLength(JSON.stringify(backup)) <= WORKSPACE_BYTE_LIMIT);
  assert.deepEqual(backup.lists[0].items, legacy.lists[0].items);
  assert.equal(backup.lists[0].updatedAt, legacy.lists[0].updatedAt);
  for (const key of ['places', 'recipients', 'numbered']) assert.equal(Object.hasOwn(backup.lists[0], key), false);
  assert.equal(backup.lists[0].favorite, true);
  assert.equal(backup.lists[0].categoryId, categoryId);
  assert.equal(backup.lists[0].position, 5);
  const shared = f.list(editor);
  assert.equal(shared.archived, true);
  assert.equal(shared.role, 'editor');
  assert.equal(shared.position, 10);
  assert.deepEqual(f.store.export(editor).lists, []);
  assert.throws(() => act('item.update', {
    listId, itemId: legacy.lists[0].items[0].id, availability: selected(), expectedAvailability: unassigned,
    newPlaces: [{ name: 'A'.repeat(100) }], newRecipient: { name: 'B'.repeat(100) }, expectedRecipientId: null,
  }), { status: 413 });
  assert.deepEqual(f.store.export(owner), backup);
  f.reopen();
  assert.deepEqual(f.store.export(owner), backup);
});
