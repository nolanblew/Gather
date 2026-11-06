import test from 'node:test';
import assert from 'node:assert/strict';
import { visibleItemOrder } from '../src/itemOrder.ts';
import { applyAction } from '../server/store.mjs';
import { seedWorkspace } from '../server/seed.mjs';

function mixedList() {
  const workspace = seedWorkspace();
  const list = workspace.lists[0];
  list.sections = [];
  list.items = [
    { id: 'open-a', text: 'Open A', checked: false },
    { id: 'done-a', text: 'Done A', checked: true },
    { id: 'open-b', text: 'Open B', checked: false },
    { id: 'done-b', text: 'Done B', checked: true },
  ].map((item, position) => ({ ...item, quantity: '', note: '', sectionId: null, position }));
  return { workspace, list };
}

test('one reorder click moves to the neighboring visible checklist item', () => {
  const { workspace, list } = mixedList();
  const openA = list.items[0];
  let order = visibleItemOrder(list, openA);
  assert.equal(order.total, 2);
  assert.equal(order.targetIndex(1), 2);
  applyAction(workspace, { type: 'item.move', payload: { listId: list.id, itemId: openA.id, sectionId: null, index: order.targetIndex(1) } });
  assert.deepEqual(list.items.filter(item => !item.checked).sort((a, b) => a.position - b.position).map(item => item.id), ['open-b', 'open-a']);
  assert.deepEqual(list.items.filter(item => item.checked).sort((a, b) => a.position - b.position).map(item => item.id), ['done-a', 'done-b']);

  const doneB = list.items.find(item => item.id === 'done-b');
  order = visibleItemOrder(list, doneB);
  assert.equal(order.targetIndex(-1), 0);
  applyAction(workspace, { type: 'item.move', payload: { listId: list.id, itemId: doneB.id, sectionId: null, index: order.targetIndex(-1) } });
  assert.deepEqual(list.items.filter(item => item.checked).sort((a, b) => a.position - b.position).map(item => item.id), ['done-b', 'done-a']);
});

test('notes and template masters reorder all visible items, including historical checks', () => {
  const { list } = mixedList();
  list.kind = 'notes';
  assert.equal(visibleItemOrder(list, list.items[1]).total, 4);
  assert.equal(visibleItemOrder(list, list.items[1]).targetIndex(1), 2);
  list.kind = 'checklist';
  list.isTemplate = true;
  assert.equal(visibleItemOrder(list, list.items[1]).total, 4);
  assert.equal(visibleItemOrder(list, list.items[1]).targetIndex(1), 2);
});
