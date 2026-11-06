import type { GatherList, Item } from './types';

export function visibleItemOrder(list: GatherList, item: Item) {
  const current = list.items.find(entry => entry.id === item.id) || item;
  const siblings = list.items.filter(entry => entry.sectionId === current.sectionId).sort((a, b) => a.position - b.position);
  const visible = list.kind === 'notes' || list.isTemplate ? siblings : siblings.filter(entry => entry.checked === current.checked);
  const position = visible.findIndex(entry => entry.id === current.id);
  const targetIndex = (direction: -1 | 1) => {
    const neighbor = visible[position + direction];
    return neighbor ? siblings.findIndex(entry => entry.id === neighbor.id) : null;
  };
  return { current, position, total: visible.length, targetIndex };
}
