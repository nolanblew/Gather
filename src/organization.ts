import type { Availability, GatherList, Item, Place, PlaceFields, Recipient } from './types';
import { sha256 } from './sha256.ts';

type NamedPlace = `place:${string}`;
type NamedPerson = `person:${string}`;
export type PlaceFilter = 'all' | 'anywhere' | 'unassigned' | NamedPlace;
export type PersonFilter = 'all' | 'unassigned' | NamedPerson;
export interface ListFilters { place: PlaceFilter; person: PersonFilter; notDone: boolean }
export const emptyFilters: ListFilters = { place: 'all', person: 'all', notDone: false };
export const noAvailability: Availability = { mode: 'unassigned', placeIds: [] };

export const placeFilter = (id: string): NamedPlace => `place:${id}`;
export const personFilter = (id: string): NamedPerson => `person:${id}`;
export const namedPlaceId = (filter: PlaceFilter) => filter.startsWith('place:') ? filter.slice(6) : null;
export const namedPersonId = (filter: PersonFilter) => filter.startsWith('person:') ? filter.slice(7) : null;
export const hasFilters = ({ place, person, notDone }: ListFilters) => place !== 'all' || person !== 'all' || notDone;
export const orderGuard = (list: GatherList, force = false) => (force || list.numbered) && list.orderToken ? { expectedOrderToken: list.orderToken } : {};

export const normalizeName = (value: string) => value.trim().replace(/\s+/gu, ' ');
export const nameKey = (value: string) => normalizeName(value).normalize('NFC').toLowerCase();
export const placeLabel = ({ name, region, countryCode }: PlaceFields) => [name, region, countryCode].filter(Boolean).join(', ');

export function sameAvailability(left: Availability, right: Availability) {
  return left.mode === right.mode && left.placeIds.length === right.placeIds.length && left.placeIds.every(id => right.placeIds.includes(id));
}

export function filterOptions(list: GatherList) {
  const places = new Set<string>();
  const people = new Set<string>();
  let anywhere = 0;
  let unassigned = 0;
  let withoutPerson = 0;
  let hasChecked = false;
  for (const item of list.items) {
    const availability = item.availability || noAvailability;
    availability.placeIds.forEach(id => places.add(id));
    anywhere += Number(availability.mode === 'anywhere');
    unassigned += Number(availability.mode === 'unassigned');
    if (item.recipientId) people.add(item.recipientId);
    else withoutPerson++;
    hasChecked ||= item.checked;
  }
  return { places, people, anywhere, unassigned, withoutPerson, hasChecked };
}

export function matchesListFilters(item: Item, filters: ListFilters, query = '') {
  if (filters.notDone && item.checked) return false;
  const { mode, placeIds } = item.availability || noAvailability;
  const placeId = namedPlaceId(filters.place);
  if (placeId ? mode !== 'anywhere' && !placeIds.includes(placeId) : filters.place !== 'all' && mode !== filters.place) return false;
  const personId = namedPersonId(filters.person);
  if (personId ? item.recipientId !== personId : filters.person === 'unassigned' && item.recipientId) return false;
  return !query || [item.text, item.note, item.quantity].some(text => text.toLocaleLowerCase().includes(query));
}

export function filterLabels(list: GatherList, filters: ListFilters) {
  const place = list.places?.find(entry => entry.id === namedPlaceId(filters.place));
  const person = list.recipients?.find(entry => entry.id === namedPersonId(filters.person));
  return [place ? placeLabel(place) : filters.place === 'anywhere' ? 'Anywhere only' : filters.place === 'unassigned' ? 'No places set' : '',
    person?.name || (filters.person === 'unassigned' ? 'Not set' : ''), filters.notDone ? 'Not done' : ''].filter(Boolean);
}

export function filterDefaults({ place, person }: ListFilters) {
  const placeId = namedPlaceId(place);
  const availability: Availability = placeId ? { mode: 'selected', placeIds: [placeId] } : place === 'anywhere' ? { mode: 'anywhere', placeIds: [] } : noAvailability;
  return { availability, recipientId: namedPersonId(person) };
}

export function placeSummary(item: Item, places: ReadonlyMap<string, Place>, filter: PlaceFilter = 'all') {
  const availability = item.availability || noAvailability;
  if (availability.mode === 'anywhere') return 'Anywhere';
  const active = namedPlaceId(filter);
  const names = availability.placeIds.filter(id => id !== active).flatMap(id => {
    const place = places.get(id);
    return place ? [placeLabel(place)] : [];
  });
  return names.length ? `${active ? 'Also ' : ''}${names.join(' or ')}` : '';
}

function lexical(left: string, right: string) { return left < right ? -1 : left > right ? 1 : 0; }

export function dictionaryDeletion(list: GatherList, type: 'place' | 'recipient', id: string) {
  const place = type === 'place' ? list.places?.find(entry => entry.id === id) : undefined;
  const recipient = type === 'recipient' ? list.recipients?.find(entry => entry.id === id) : undefined;
  if (!place && !recipient) return null;
  const definition = place
    ? { id, name: place.name, kind: place.kind, region: place.region, countryCode: place.countryCode }
    : { id, name: recipient!.name };
  const affected: [string, Availability | string][] = [];
  for (const item of list.items) {
    if (place && item.availability?.placeIds.includes(id)) {
      affected.push([item.id, { mode: 'selected', placeIds: item.availability.placeIds.slice().sort(lexical) }]);
    }
    if (recipient && item.recipientId === id) affected.push([item.id, id]);
  }
  affected.sort(([left], [right]) => lexical(left, right));
  return { count: affected.length, token: sha256(JSON.stringify([type, id, definition, affected])) };
}

export function usedFirst<T extends Place | Recipient>(entries: T[], used: ReadonlySet<string>) {
  return entries.slice().sort((a, b) => Number(used.has(b.id)) - Number(used.has(a.id)) || a.name.localeCompare(b.name));
}
