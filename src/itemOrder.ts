import type { GatherList, Item } from './types';

export const byPosition = <T extends { position: number }>(a: T, b: T) => a.position - b.position;

export function canonicalItemGroups(list: GatherList) {
  const groups = [{ id: null as string | null, title: list.sections.length ? 'Unsectioned' : '' }, ...list.sections.slice().sort(byPosition)]
    .map(section => ({ ...section, items: [] as Item[] }));
  const bySection = new Map(groups.map(group => [group.id, group.items]));
  for (const item of list.items) (bySection.get(item.sectionId) || groups[0].items).push(item);
  for (const group of groups) group.items.sort(byPosition);
  return groups;
}

export function itemOrdinals(list: GatherList) {
  return new Map(canonicalItemGroups(list).flatMap(group => group.items).map((item, index) => [item.id, index + 1]));
}

export function visibleItemOrder(list: GatherList, item: Item) {
  const current = list.items.find(entry => entry.id === item.id) || item;
  const siblings = list.items.filter(entry => entry.sectionId === current.sectionId).sort(byPosition);
  const visible = list.numbered || list.kind === 'notes' || list.isTemplate ? siblings : siblings.filter(entry => entry.checked === current.checked);
  const position = visible.findIndex(entry => entry.id === current.id);
  const targetIndex = (direction: -1 | 1) => {
    const neighbor = visible[position + direction];
    return neighbor ? siblings.findIndex(entry => entry.id === neighbor.id) : null;
  };
  return { current, position, total: visible.length, targetIndex };
}
