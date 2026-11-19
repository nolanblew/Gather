import type { GatherList, Item } from './types';
import { itemRecipientIds } from './organization.ts';

export const byPosition = <T extends { position: number }>(a: T, b: T) => a.position - b.position;

export type ItemSort = 'added' | 'newest' | 'name' | 'manual';
export type ItemGrouping = 'none' | 'place' | 'person';

export function canonicalItemGroups(list: GatherList, sort: ItemSort = 'manual') {
  const groups = [{ id: null as string | null, title: list.sections.length ? 'Unsectioned' : '' }, ...list.sections.slice().sort(byPosition)]
    .map(section => ({ ...section, items: [] as Item[] }));
  const bySection = new Map(groups.map(group => [group.id, group.items]));
  for (const item of list.items) (bySection.get(item.sectionId) || groups[0].items).push(item);
  for (const group of groups) {
    if (sort === 'manual') group.items.sort(byPosition);
    if (sort === 'newest') group.items.reverse();
    if (sort === 'name') group.items.sort((left, right) => left.text.localeCompare(right.text, undefined, { numeric: true, sensitivity: 'base' }));
  }
  return groups;
}

export function itemOrdinals(list: GatherList, sort: ItemSort = 'manual') {
  return new Map(canonicalItemGroups(list, sort).flatMap(group => group.items).map((item, index) => [item.id, index + 1]));
}

export function itemGroupIds(item: Item, groupBy: ItemGrouping): string[] {
  if (groupBy === 'none') return [''];
  if (groupBy === 'person') {
    const ids = itemRecipientIds(item);
    return ids.length ? ids.map(id => `person:${id}`) : ['unassigned'];
  }
  if (item.availability?.mode === 'anywhere') return ['anywhere'];
  const ids = item.availability?.mode === 'selected' ? item.availability.placeIds : [];
  return ids.length ? [...new Set(ids)].map(id => `place:${id}`) : ['unassigned'];
}

export function splitItemGroups(items: Item[], groupBy: ItemGrouping) {
  const byGroup = new Map<string, Item[]>();
  for (const item of items) {
    for (const id of itemGroupIds(item, groupBy)) {
      const group = byGroup.get(id);
      if (group) group.push(item); else byGroup.set(id, [item]);
    }
  }
  return Array.from(byGroup, ([id, groupedItems]) => ({ id, items: groupedItems }));
}

export function sortItemGroupIds(groupIds: readonly string[], preferred: readonly string[], label: (id: string) => string) {
  const positions = new Map(preferred.map((id, index) => [id, index]));
  const specialRank = (id: string) => id === 'unassigned' ? 2 : id === 'anywhere' ? 1 : 0;
  return groupIds.slice().sort((left, right) =>
    (positions.get(left) ?? preferred.length) - (positions.get(right) ?? preferred.length)
    || specialRank(left) - specialRank(right)
    || label(left).localeCompare(label(right))
    || left.localeCompare(right)
  );
}

export function reorderItemIds(itemIds: readonly string[], sourceId: string, targetId: string, after: boolean): string[] | null {
  if (sourceId === targetId || !itemIds.includes(sourceId) || !itemIds.includes(targetId)) return null;
  const reordered = itemIds.filter(id => id !== sourceId);
  reordered.splice(reordered.indexOf(targetId) + Number(after), 0, sourceId);
  return reordered.every((id, index) => id === itemIds[index]) ? null : reordered;
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
