import { createHash, randomUUID } from 'node:crypto';
import { InputError, bool, fail, id, object, string } from './input.mjs';

export const WORKSPACE_VERSION = 3;
const MAX_SELECTED_PLACES = 25;
const PLACE_KINDS = new Set(['city', 'store', 'home', 'work', 'online', 'other']);
const UNASSIGNED = Object.freeze({ mode: 'unassigned', placeIds: Object.freeze([]) });
const byPosition = (a, b) => a.position - b.position;
const byId = (a, b) => a[0] < b[0] ? -1 : a[0] > b[0] ? 1 : 0;
const sha256 = value => createHash('sha256').update(JSON.stringify(value)).digest('hex');
const labels = {
  place: { field: 'places', idKey: 'placeId', createdKey: 'createdPlaceId', limit: 100, display: 'Place', duplicate: 'This place already exists. Choose it instead.' },
  recipient: { field: 'recipients', idKey: 'recipientId', createdKey: 'createdRecipientId', limit: 200, display: 'Person', duplicate: 'This name already exists. Choose it instead.' },
};

function label(value, field, missing) {
  if (typeof value !== 'string' || (missing && !value.trim())) fail(missing ?? `${field} must be text.`);
  return string(value.trim().replace(/\s+/gu, ' '), field, 100, Boolean(missing));
}

function definition(type, input, entryId) {
  object(input);
  if (type === 'recipient') return { id: entryId, name: label(input.name, 'Name', 'Enter a name.') };
  const name = label(input.name, 'Place name', 'Enter a place name.');
  const kind = Object.hasOwn(input, 'kind') ? input.kind : 'other';
  const region = Object.hasOwn(input, 'region') ? label(input.region, 'Region') : '';
  const countryCode = Object.hasOwn(input, 'countryCode') ? string(input.countryCode, 'Country code', 2) : '';
  if (!PLACE_KINDS.has(kind)) fail('Unknown place kind.');
  if (countryCode !== '' && !/^[A-Z]{2}$/.test(countryCode)) fail('Country code must be two uppercase letters.');
  return { id: entryId, name, kind, region, countryCode };
}

function availability(input, allowEmptySelection = false) {
  object(input);
  const { mode, placeIds } = input;
  if (!['unassigned', 'anywhere', 'selected'].includes(mode)) fail('Choose places, Anywhere, or No places set.');
  if (!Array.isArray(placeIds) || placeIds.length > MAX_SELECTED_PLACES) fail('Choose up to 25 places.');
  if (mode === 'selected' && !placeIds.length && !allowEmptySelection) fail('Choose a place or select Anywhere.');
  if (mode !== 'selected' && placeIds.length) fail('Choose places, Anywhere, or No places set.');
  const sorted = placeIds.map(id).sort();
  if (new Set(sorted).size !== sorted.length) fail('Choose each place only once.');
  return { mode, placeIds: sorted };
}

function setAvailability(item, value) {
  if (value.mode === 'unassigned') delete item.availability;
  else item.availability = value;
}

function setRecipient(item, value) {
  if (value === null) delete item.recipientId;
  else item.recipientId = value;
}

function requireReference(ids, value, name) {
  if (!ids.has(value)) fail(`${name} is no longer available. Choose another or remove it.`);
}

export function readOrganization(source, list, unique) {
  if (Object.hasOwn(source, 'numbered') && bool(source.numbered, 'Numbered')) list.numbered = true;
  const ids = {};
  for (const [type, { field, limit, display }] of Object.entries(labels)) {
    const entries = Object.hasOwn(source, field) ? source[field] : [];
    if (!Array.isArray(entries) || entries.length > limit) fail(`${display} limit exceeded.`);
    const normalized = entries.map(entry => {
      object(entry);
      return definition(type, entry, unique(entry.id));
    });
    if (normalized.length) list[field] = normalized;
    ids[field] = new Set(normalized.map(entry => entry.id));
  }
  return ids;
}

export function readItemOrganization(source, item, ids) {
  if (Object.hasOwn(source, 'availability')) {
    const value = availability(source.availability);
    for (const placeId of value.placeIds) requireReference(ids.places, placeId, 'Place');
    setAvailability(item, value);
  }
  if (Object.hasOwn(source, 'recipientId') && source.recipientId !== null) {
    requireReference(ids.recipients, id(source.recipientId), 'Person');
    setRecipient(item, source.recipientId);
  }
}

export function orderToken(list) {
  const sections = list.sections.toSorted(byPosition).map(section => section.id);
  const groups = new Map([null, ...sections].map(sectionId => [sectionId, []]));
  for (const item of list.items) groups.get(item.sectionId).push(item);
  const items = Array.from(groups.values(), group => group.sort(byPosition).map(item => item.id));
  return sha256([list.id, Boolean(list.numbered), sections, items]);
}

export function guardOrder(list, payload, toggling = false) {
  if (!Object.hasOwn(payload, 'expectedOrderToken')) {
    if (toggling || list.numbered) throw new InputError('Refresh the list or update the app before editing the order.', 409, 'ORDER_TOKEN_REQUIRED');
    return;
  }
  if (payload.expectedOrderToken !== orderToken(list)) {
    throw new InputError('The list order changed. Review the latest order and try again.', 409, 'ORDER_CHANGED');
  }
}

function assignmentConflict() {
  throw new InputError('Item details changed. Review the latest details and try again.', 409, 'ASSIGNMENT_CHANGED');
}

export function assignmentToken(list, type, entry) {
  const pairs = [];
  for (const item of list.items) {
    if (type === 'place' && item.availability?.placeIds.includes(entry.id)) pairs.push([item.id, availability(item.availability)]);
    if (type === 'recipient' && item.recipientId === entry.id) pairs.push([item.id, item.recipientId]);
  }
  return sha256([type, entry.id, definition(type, entry, entry.id), pairs.sort(byId)]);
}

const compareLabel = value => value.normalize('NFC').toLowerCase();
function matchingLabels(type, entries, value, qualifiers) {
  const name = compareLabel(value.name);
  return entries.filter(entry => compareLabel(entry.name) === name && (type === 'recipient' ||
    qualifiers.every(key => compareLabel(entry[key]) === compareLabel(value[key]))));
}

function createDefinition(list, type, input, inline = false) {
  const { field, limit, display, duplicate } = labels[type];
  const value = definition(type, input, randomUUID());
  const entries = list[field] ?? [];
  const qualifiers = ['kind', 'region', 'countryCode'].filter(key => !inline || Object.hasOwn(input, key));
  const matches = matchingLabels(type, entries, value, qualifiers);
  if (inline && matches.length === 1) return matches[0].id;
  if (inline && matches.length > 1) fail(`More than one ${display.toLowerCase()} matches. Choose an existing entry or use a more specific name.`);
  if (matches.length) fail(duplicate);
  if (entries.length >= limit) fail(`${display} limit reached.`);
  entries.push(value);
  list[field] = entries;
  return value.id;
}

export function editAssignments(list, item, payload, update = false) {
  const draftsPlaces = Object.hasOwn(payload, 'newPlaces');
  const draftsRecipient = Object.hasOwn(payload, 'newRecipient');
  const editsPlace = Object.hasOwn(payload, 'availability') || draftsPlaces;
  const editsRecipient = Object.hasOwn(payload, 'recipientId') || draftsRecipient;
  if (update && editsPlace) {
    if (!Object.hasOwn(payload, 'expectedAvailability')) assignmentConflict();
    const expected = availability(payload.expectedAvailability);
    const current = item.availability ?? UNASSIGNED;
    if (expected.mode !== current.mode || expected.placeIds.length !== current.placeIds.length ||
        expected.placeIds.some((placeId, index) => placeId !== current.placeIds[index])) assignmentConflict();
  }
  if (update && editsRecipient && payload.expectedRecipientId !== (item.recipientId ?? null)) assignmentConflict();

  if (editsPlace) {
    const value = availability(payload.availability, draftsPlaces);
    const placeIds = new Set((list.places ?? []).map(place => place.id));
    for (const placeId of value.placeIds) requireReference(placeIds, placeId, 'Place');
    if (draftsPlaces) {
      if (value.mode !== 'selected') fail('Choose places, Anywhere, or No places set.');
      if (!Array.isArray(payload.newPlaces) || payload.newPlaces.length > MAX_SELECTED_PLACES) fail('Choose up to 25 places.');
      const added = payload.newPlaces.map(place => createDefinition(list, 'place', place, true));
      value.placeIds = [...new Set([...value.placeIds, ...added])];
    }
    setAvailability(item, availability(value));
  }
  if (editsRecipient) {
    let recipientId = Object.hasOwn(payload, 'recipientId') ? payload.recipientId : null;
    if (draftsRecipient) {
      if (recipientId !== null) fail('Choose an existing person or create a name.');
      recipientId = createDefinition(list, 'recipient', payload.newRecipient, true);
    } else if (recipientId !== null) {
      requireReference(new Set((list.recipients ?? []).map(entry => entry.id)), id(recipientId), 'Person');
    }
    setRecipient(item, recipientId);
  }
}

export function editDictionary(list, type, command, payload) {
  const { field, idKey, createdKey, display, duplicate } = labels[type];
  if (command === 'create') return { [createdKey]: createDefinition(list, type, payload) };
  const entries = list[field] ?? [];
  const entryId = id(payload[idKey]);
  const entry = entries.find(value => value.id === entryId);
  if (command === 'delete') {
    if (!entry || payload.expectedAssignmentToken !== assignmentToken(list, type, entry)) assignmentConflict();
    const remaining = entries.filter(value => value !== entry);
    if (remaining.length) list[field] = remaining;
    else delete list[field];
    for (const item of list.items) {
      if (type === 'place' && item.availability?.placeIds.includes(entryId)) {
        const placeIds = item.availability.placeIds.filter(placeId => placeId !== entryId);
        setAvailability(item, placeIds.length ? { mode: 'selected', placeIds } : UNASSIGNED);
      }
      if (type === 'recipient' && item.recipientId === entryId) setRecipient(item, null);
    }
  } else if (command === 'update') {
    if (!entry) fail(`${display} not found.`);
    const value = definition(type, { ...entry, ...payload }, entryId);
    const qualifiers = ['kind', 'region', 'countryCode'];
    if (!matchingLabels(type, [entry], value, qualifiers).length && matchingLabels(type, entries, value, qualifiers).length) fail(duplicate);
    Object.assign(entry, value);
  } else fail('Unknown action.');
}

export function remapListContent(list, mapping, resetCompletion = false) {
  const remap = entries => entries?.map(entry => ({ ...entry, id: mapping.get(entry.id) }));
  const result = { ...list, sections: remap(list.sections), items: remap(list.items) };
  if (list.places) result.places = remap(list.places);
  if (list.recipients) result.recipients = remap(list.recipients);
  for (const item of result.items) {
    if (resetCompletion) item.checked = false;
    if (item.sectionId !== null) item.sectionId = mapping.get(item.sectionId);
    if (item.recipientId) item.recipientId = mapping.get(item.recipientId);
    if (item.availability) {
      item.availability = { mode: item.availability.mode, placeIds: item.availability.placeIds.map(placeId => mapping.get(placeId)).sort() };
    }
  }
  return result;
}

export function mapContentIds(list, mapping = new Map()) {
  for (const entries of [list.sections, list.items, list.places ?? [], list.recipients ?? []]) {
    for (const entry of entries) mapping.set(entry.id, randomUUID());
  }
  return mapping;
}
