import { useRef, useState } from 'react';
import { ArrowDown, ArrowUp, ChevronDown, Copy, Plus, Trash2, UserRound, X } from 'lucide-react';
import Dialog from './Dialog';
import { descriptionError, legacyDescription, sameDescription, sameSubitems } from './itemDetails';
import { detailChoices, emptyPlaceFields, PickerBack, PlaceInputs } from './OrganizationDetails';
import RichDescriptionEditor from './RichDescriptionEditor';
import { newSubitemId, SubchecklistEditor } from './Subchecklist';
import { byPosition, visibleItemOrder } from './itemOrder';
import { filterOptions, itemRecipientIds, nameKey, noAvailability, normalizeName, orderGuard, placeLabel, sameAvailability, usedFirst } from './organization';
import type { Availability, DescriptionBlock, GatherList, Item, Mutation, Payload, PlaceFields, Subitem, Workspace } from './types';

type Detail = keyof typeof detailChoices;
type Surface = 'item' | 'details' | 'place' | 'person' | 'delete';
type NewPlace = { name: string } & Partial<PlaceFields>;
interface PlaceDraft { availability: Availability; newPlaces: NewPlace[] }
interface PersonDraft { ids: string[]; newPeople: { name: string }[] }
const emptyPeople: PersonDraft = { ids: [], newPeople: [] };
const samePeople = (left: string[], right: string[]) => left.length === right.length && left.every(id => right.includes(id));

export function makeItemDraft(overrides: Partial<Item> = {}): Item {
  return { id: '', text: '', quantity: '', note: '', sectionId: null, position: 0, checked: false, ...overrides };
}

export default function ItemEditor({ list, initial, organization, richDescriptions, subchecklists, lists, filtered, busy, error, clearError, mutate, saved, close, showFullOrder, useManualOrder }: {
  list: GatherList;
  initial: Item;
  organization: boolean;
  richDescriptions: boolean;
  subchecklists: boolean;
  lists: Pick<GatherList, 'id' | 'title'>[];
  filtered: boolean;
  busy: boolean;
  error: string;
  clearError: () => void;
  mutate: Mutation;
  saved: (result: Workspace, id: string | undefined) => void;
  close: () => void;
  showFullOrder: () => void;
  useManualOrder: () => void;
}) {
  const [draft, setDraft] = useState(initial);
  const [source, setSource] = useState(initial);
  const [place, setPlace] = useState<PlaceDraft>({ availability: initial.availability || noAvailability, newPlaces: [] });
  const [person, setPerson] = useState<PersonDraft>({ ids: itemRecipientIds(initial), newPeople: [] });
  const [surface, setSurface] = useState<Surface>('item');
  const [hasDescription, setHasDescription] = useState(Boolean(initial.note || initial.description?.length));
  const [initialDescription] = useState(() => initial.description ?? legacyDescription(initial.note));
  const [description, setDescription] = useState(initialDescription);
  const [preparingImage, setPreparingImage] = useState(false);
  const [subitems, setSubitems] = useState(initial.subitems ?? []);
  const [hasSubchecklist, setHasSubchecklist] = useState(Boolean(initial.subitems?.length));
  const [hasQuantity, setHasQuantity] = useState(Boolean(initial.quantity));
  const [hasSection, setHasSection] = useState(Boolean(initial.sectionId));
  const [orderSnapshot, setOrderSnapshot] = useState(list.orderToken);
  const [validation, setValidation] = useState('');
  const [startedForSomeone, setStartedForSomeone] = useState(false);
  const form = useRef<HTMLFormElement>(null);
  const input = useRef<HTMLInputElement>(null);
  const returnFocus = useRef('add-detail');
  const [recommended] = useState(() => {
    const options = filterOptions(list);
    return [options.places.size || options.anywhere ? 'place' : null, options.people.size ? 'person' : null].filter(Boolean) as Detail[];
  });
  const used = filterOptions(list);
  const current = draft.id ? list.items.find(item => item.id === draft.id) : undefined;
  const currentDescription = current?.description ?? legacyDescription(current?.note ?? '');
  const currentPlace = current?.availability || noAvailability;
  const currentPersonIds = current ? itemRecipientIds(current) : [];
  const changingPlace = !sameAvailability(place.availability, initial.availability || noAvailability) || place.newPlaces.length > 0;
  const changingPerson = !samePeople(person.ids, itemRecipientIds(initial)) || person.newPeople.length > 0;
  const changingDescription = richDescriptions && !sameDescription(description, initialDescription);
  const changingSubitems = subchecklists && !sameSubitems(subitems, initial.subitems);
  const placeConflict = changingPlace && current && !sameAvailability(source.availability || noAvailability, currentPlace) && (place.newPlaces.length > 0 || !sameAvailability(place.availability, currentPlace));
  const personConflict = changingPerson && current && !samePeople(itemRecipientIds(source), currentPersonIds)
    && (person.newPeople.length > 0 || !samePeople(person.ids, currentPersonIds));
  const descriptionConflict = changingDescription && current && (source.note !== current.note || !sameDescription(source.description, current.description))
    && !sameDescription(description, currentDescription);
  const subitemsConflict = changingSubitems && current && !sameSubitems(source.subitems, current.subitems) && !sameSubitems(subitems, current.subitems);
  const originalPlaces = list.places || [];
  const originalPeople = list.recipients || [];
  const currentPlaces = new Map(originalPlaces.map(place => [place.id, place]));
  const currentPeople = new Map(originalPeople.map(person => [person.id, person]));
  const [knownPlaces] = useState(() => new Map(originalPlaces.map(place => [place.id, place])));
  const [knownPeople] = useState(() => new Map(originalPeople.map(person => [person.id, person])));
  const missingPlaces = place.availability.placeIds.filter(id => !currentPlaces.has(id));
  const missingPeople = person.ids.filter(id => !currentPeople.has(id));
  const missingSection = draft.sectionId && !list.sections.some(section => section.id === draft.sectionId);
  const move = current ? visibleItemOrder(list, current) : null;
  const sectionChanged = draft.sectionId !== initial.sectionId;
  const orderChanged = (!draft.id || sectionChanged) && Boolean(list.numbered && orderSnapshot !== list.orderToken);
  const hasPlace = place.availability.mode !== 'unassigned';
  const hasPerson = person.ids.length > 0 || person.newPeople.length > 0;
  const choiceOrder = [...new Set<Detail>([...recommended, 'description', 'place', 'person', 'quantity', 'section'])];
  const present: Record<Detail, boolean> = { description: hasDescription, place: hasPlace, person: hasPerson, quantity: hasQuantity, section: hasSection };
  const choices = choiceOrder.filter(key => !present[key] && (organization || (key !== 'place' && key !== 'person')));
  const blocked = Boolean((!draft.id || changingPlace) && missingPlaces.length || (!draft.id || changingPerson) && missingPeople.length || (!draft.id || sectionChanged) && missingSection || placeConflict || personConflict || descriptionConflict || subitemsConflict || orderChanged || preparingImage);

  const select = (key: Detail) => {
    setValidation('');
    if (key === 'place' || key === 'person') { returnFocus.current = key; setSurface(key); return; }
    if (key === 'description') setHasDescription(true);
    if (key === 'quantity') setHasQuantity(true);
    if (key === 'section') setHasSection(true);
    showItem(key);
  };
  function showItem(focus = returnFocus.current) {
    setSurface('item');
    requestAnimationFrame(() => (form.current?.querySelector<HTMLElement>(`[data-detail="${focus}"]`) || form.current?.querySelector<HTMLElement>('[data-detail="add-detail"]') || input.current)?.focus());
  }
  const closeSurface = () => { if (surface === 'item') close(); else showItem(); };
  const placeName = (id: string) => {
    const entry = currentPlaces.get(id) || knownPlaces.get(id);
    return entry ? placeLabel(entry) : 'This place';
  };
  const personName = (id: string) => (currentPeople.get(id) || knownPeople.get(id))?.name || 'This person';
  const describePlaces = (value: Availability) => value.mode === 'anywhere' ? 'Anywhere' : value.placeIds.map(placeName).join(' or ') || 'No places set';
  const changeDraft = (key: 'text' | 'note' | 'quantity' | 'sectionId', value: string | null) => {
    setDraft({ ...draft, [key]: value });
    setValidation('');
  };
  const changeDescription = (value: DescriptionBlock[]) => { setDescription(value); setValidation(''); };
  const changeSubitems = (value: Subitem[]) => { setSubitems(value); setValidation(''); };

  const submit = async () => {
    if (!draft.text.trim()) { setValidation('Enter an item.'); input.current?.focus(); return; }
    if (draft.id && !current) { setValidation('This item is no longer available.'); return; }
    if (blocked) return;
    if (startedForSomeone && !hasPerson) { setSurface('person'); return; }
    const invalidDescription = richDescriptions ? descriptionError(description) : '';
    if (invalidDescription) { setValidation(invalidDescription); return; }
    const emptyStep = subchecklists && subitems.find(step => !step.text.trim());
    if (emptyStep) {
      setValidation('Enter a name for each step or remove it.');
      form.current?.querySelector<HTMLInputElement>(`[data-step-id="${CSS.escape(emptyStep.id)}"]`)?.focus();
      return;
    }
    const payload: Payload = { listId: list.id };
    if (draft.id) {
      payload.itemId = draft.id;
      for (const key of ['text', 'note', 'quantity', 'sectionId'] as const) if (draft[key] !== initial[key]) payload[key] = draft[key];
      if (sectionChanged) Object.assign(payload, orderGuard(list));
      if (changingPlace && (place.newPlaces.length || !sameAvailability(place.availability, currentPlace))) {
        payload.expectedAvailability = source.availability || noAvailability;
        payload.availability = place.availability;
      }
      if (changingPerson && (person.newPeople.length || !samePeople(person.ids, currentPersonIds))) {
        payload.expectedRecipientIds = itemRecipientIds(source);
        payload.recipientIds = person.ids;
      }
      if (changingDescription && !sameDescription(description, currentDescription)) {
        payload.description = description;
        payload.expectedDescription = source.description ?? [];
        if (current?.note) {
          payload.note = '';
          payload.expectedNote = source.note;
        }
      }
      if (changingSubitems && !sameSubitems(subitems, current?.subitems)) {
        payload.subitems = subitems;
        payload.expectedSubitems = source.subitems ?? [];
      }
    } else {
      Object.assign(payload, { text: draft.text, note: draft.note, quantity: draft.quantity, sectionId: draft.sectionId }, orderGuard(list));
      if (richDescriptions && (description.length || changingDescription)) {
        payload.description = description;
        payload.note = '';
      }
      if (subchecklists && subitems.length) payload.subitems = subitems;
      if (hasPlace) payload.availability = place.availability;
      if (hasPerson) payload.recipientIds = person.ids;
    }
    if (place.newPlaces.length) payload.newPlaces = place.newPlaces;
    if (person.newPeople.length) payload.newRecipients = person.newPeople;
    if (current && Object.keys(payload).length === 2) { close(); return; }
    const action = draft.id ? 'item.update' : 'item.create';
    const result = await mutate('recipientIds' in payload ? `${action}WithPeople` : action, payload);
    if (result) saved(result, draft.id || result.createdItemId);
  };
  const title = surface === 'details' ? 'Add detail' : surface === 'place' ? 'Choose places' : surface === 'person' ? 'For someone' : surface === 'delete' ? 'Delete this item?' : startedForSomeone ? 'Duplicate for someone else' : !draft.id && initial.id ? 'Duplicate item' : 'Item details';
  return <Dialog title={title} busy={busy} close={closeSurface} className={richDescriptions || subchecklists ? 'item-details-dialog' : undefined}>
    {(error || validation) && <div className="dialog-error" role="alert">{validation || error}</div>}
    {surface === 'details' && <>
      <div className="detail-choices">{choices.map((key, index) => {
        const { label, icon: Icon } = detailChoices[key];
        return <button className="detail-choice" type="button" autoFocus={index === 0} key={key} onClick={() => select(key)}>
          <Icon size={20} aria-hidden="true" /><span>{label}{key === 'person' && <small>Who this item is for</small>}</span>
        </button>;
      })}</div>
      <div className="dialog-actions"><button className="secondary" onClick={() => showItem('add-detail')}>Cancel</button></div>
    </>}
    {surface === 'place' && <PlacesPicker initial={place} entries={usedFirst(originalPlaces, used.places)} back={closeSurface} done={value => { setPlace(value); showItem('place'); }} />}
    {surface === 'person' && <PersonPicker initial={person} people={usedFirst(originalPeople, used.people)}
      itemText={draft.text} requireChoice={startedForSomeone} back={closeSurface} done={value => { setPerson(value); showItem('person'); }} />}
    {surface === 'delete' && <>
      <p className="dialog-copy">“{current?.text || initial.text}” will be permanently removed from this list.</p>
      {list.numbered && orderSnapshot !== list.orderToken && <p className="field-error" role="alert">The list order changed. Review the latest order and try again.</p>}
      <div className="dialog-actions"><button className="secondary" onClick={() => showItem()}>Cancel</button><button className="danger" disabled={!current || Boolean(list.numbered && orderSnapshot !== list.orderToken)} onClick={async () => {
        if (await mutate('item.delete', { listId: list.id, itemId: initial.id, ...orderGuard(list) })) close();
        else showItem();
      }}>Delete item</button></div>
    </>}
    {surface === 'item' && <form ref={form} noValidate onSubmit={event => { event.preventDefault(); void submit(); }}>
      <label className="field">Item<input ref={input} autoFocus required maxLength={500} value={draft.text} onChange={event => changeDraft('text', event.target.value)} /></label>
      {hasDescription ? richDescriptions ? <RichDescriptionEditor value={description} lists={lists} disabled={busy} onChange={changeDescription} onPreparing={setPreparingImage} />
        : <label className="field">Description<textarea data-detail="description" rows={3} maxLength={4000} value={draft.note} onChange={event => changeDraft('note', event.target.value)} /></label>
        : <button className="text-button add-description" type="button" onClick={() => select('description')}><Plus size={16} />Add description</button>}
      {subchecklists && <SubchecklistEditor value={subitems} onChange={changeSubitems} open={hasSubchecklist} onOpen={() => setHasSubchecklist(true)} />}
      {hasPlace && <div className="assigned-detail">
        <button className="text-button" data-detail="place" type="button" onClick={() => select('place')}>Places<ChevronDown size={16} /></button>
        {place.availability.mode === 'anywhere' ? <span className="assigned-value">Anywhere</span> : <>
          <div className="assigned-values">
            {place.availability.placeIds.map(id => <button type="button" className="assigned-value" key={id} aria-label={`Remove ${placeName(id)}`} onClick={() => {
              const ids = place.availability.placeIds.filter(value => value !== id);
              setPlace({ ...place, availability: ids.length || place.newPlaces.length ? { mode: 'selected', placeIds: ids } : noAvailability });
            }}>{placeName(id)}<X size={15} aria-hidden="true" /></button>)}
            {place.newPlaces.map((entry, index) => <button type="button" className="assigned-value" key={index} aria-label={`Remove ${entry.name}`} onClick={() => {
              const newPlaces = place.newPlaces.filter((_, entryIndex) => entryIndex !== index);
              setPlace({ newPlaces, availability: newPlaces.length || place.availability.placeIds.length ? place.availability : noAvailability });
            }}>{placeLabel({ ...emptyPlaceFields, ...entry })}<X size={15} aria-hidden="true" /></button>)}
          </div>
          <button className="text-button" type="button" onClick={() => select('place')}><Plus size={16} />Add place</button>
        </>}
      </div>}
      {hasPerson && <div className="assigned-detail">
        <button className="text-button" data-detail="person" type="button" onClick={() => select('person')}>For someone<ChevronDown size={16} /></button>
        <div className="assigned-values">
          {person.ids.map(id => <button key={id} className="assigned-value" type="button" aria-label={`Remove ${personName(id)}`} onClick={() => setPerson({ ...person, ids: person.ids.filter(value => value !== id) })}>{personName(id)}<X size={15} aria-hidden="true" /></button>)}
          {person.newPeople.map(({ name }) => <button key={name} className="assigned-value" type="button" aria-label={`Remove ${name}`} onClick={() => setPerson({ ...person, newPeople: person.newPeople.filter(entry => entry.name !== name) })}>{name}<X size={15} aria-hidden="true" /></button>)}
        </div>
      </div>}
      {hasQuantity && <label className="field">Quantity<input maxLength={100} data-detail="quantity" value={draft.quantity} onChange={event => changeDraft('quantity', event.target.value)} /></label>}
      {hasSection && <label className="field">Section<select data-detail="section" value={draft.sectionId || ''} onChange={event => changeDraft('sectionId', event.target.value || null)}>
        <option value="">Unsectioned</option>{missingSection && <option value={draft.sectionId!} disabled>Section removed</option>}{list.sections.slice().sort(byPosition).map(section => <option key={section.id} value={section.id}>{section.title}</option>)}
      </select>{list.sections.length === 0 && <span className="field-help">Add a section from List actions to group items.</span>}</label>}
      {choices.length > 0 && <button className="text-button add-detail" data-detail="add-detail" type="button" onClick={() => { returnFocus.current = 'add-detail'; setSurface('details'); }}><Plus size={17} />Add detail</button>}
      {missingPlaces.map(id => <p className="field-error" role="alert" key={id}>{placeName(id)} is no longer available. Choose another or remove it.</p>)}
      {missingPeople.map(id => <p className="field-error" role="alert" key={id}>{personName(id)} is no longer available. Choose another or remove them.</p>)}
      {missingSection && <p className="field-error" role="alert">This section is no longer available. Choose another or move the item to Unsectioned.</p>}
      {(placeConflict || personConflict || descriptionConflict || subitemsConflict) && <div className="detail-conflict" role="alert">
        <p>Someone changed this item while you were editing.</p>
        {placeConflict && <p>Current places: {describePlaces(currentPlace)}</p>}
        {personConflict && <p>Current people: {currentPersonIds.map(personName).join(', ') || 'Not set'}</p>}
        {descriptionConflict && <p>The description changed.</p>}
        {subitemsConflict && <p>The subchecklist changed.</p>}
        <button type="button" className="text-button" onClick={() => { if (current) setSource(current); clearError(); }}>Keep my changes</button>
        <button type="button" className="text-button" onClick={() => {
          if (placeConflict) setPlace({ availability: currentPlace, newPlaces: [] });
          if (personConflict) setPerson({ ids: currentPersonIds, newPeople: [] });
          if (descriptionConflict && current) {
            setDescription(currentDescription);
            setHasDescription(Boolean(currentDescription.length));
          }
          if (subitemsConflict) {
            setSubitems(current?.subitems ?? []);
            setHasSubchecklist(Boolean(current?.subitems?.length));
          }
          if (current) setSource(current);
          clearError();
        }}>Use current details</button>
      </div>}
      {orderChanged && <div className="detail-conflict" role="alert"><p>The list order changed. Review the latest order and try again.</p><button type="button" className="text-button" onClick={() => { showFullOrder(); setOrderSnapshot(list.orderToken); clearError(); }}>Review order</button></div>}
      {move && <details className="item-tools">
        <summary className="text-button"><ChevronDown size={17} />Item actions</summary>
        {filtered ? <div className="reorder-controls"><p className="small-muted">Show all items to edit their order.</p><button type="button" className="secondary" onClick={showFullOrder}>Edit order</button></div> : <div className="reorder-controls">
          <button type="button" className="secondary" disabled={move.targetIndex(-1) === null} onClick={async () => {
            const result = await mutate('item.move', { listId: list.id, itemId: current!.id, sectionId: current!.sectionId, index: move.targetIndex(-1), ...orderGuard(list) });
            if (result) { setOrderSnapshot(result.lists.find(entry => entry.id === list.id)?.orderToken); useManualOrder(); }
          }}><ArrowUp size={16} />Move up</button>
          <button type="button" className="secondary" disabled={move.targetIndex(1) === null} onClick={async () => {
            const result = await mutate('item.move', { listId: list.id, itemId: current!.id, sectionId: current!.sectionId, index: move.targetIndex(1), ...orderGuard(list) });
            if (result) { setOrderSnapshot(result.lists.find(entry => entry.id === list.id)?.orderToken); useManualOrder(); }
          }}><ArrowDown size={16} />Move down</button>
        </div>}
        <div className="item-tool-actions">
          <button type="button" className="text-button" onClick={() => savedDuplicate(false)}><Copy size={16} />Duplicate item</button>
          {organization && <button type="button" className="text-button" onClick={() => savedDuplicate(true)}><UserRound size={16} />Duplicate for someone else</button>}
          <button type="button" className="text-button danger-text" onClick={() => { clearError(); setOrderSnapshot(list.orderToken); setSurface('delete'); }}><Trash2 size={16} />Delete item</button>
        </div>
      </details>}
      <div className="dialog-actions"><button type="button" className="secondary" onClick={close}>Cancel</button><button className="primary" disabled={blocked}>{busy ? 'Saving…' : 'Save'}</button></div>
    </form>}
  </Dialog>;

  function savedDuplicate(forAnotherPerson: boolean) {
    const copied = { ...draft, id: '', checked: false };
    setDraft(copied);
    setSource(copied);
    setSubitems(subitems.map(step => ({ ...step, id: newSubitemId(), checked: false })));
    setOrderSnapshot(list.orderToken);
    setStartedForSomeone(forAnotherPerson);
    if (forAnotherPerson) { setPerson(emptyPeople); setSurface('person'); }
    else input.current?.focus();
  }
}

export function PlacesPicker({ initial, entries, back, done, bulk }: {
  initial: PlaceDraft;
  entries: NonNullable<GatherList['places']>;
  back: () => void;
  done: (value: PlaceDraft) => void;
  bulk?: { count: number; anywhere: number };
}) {
  const [draft, setDraft] = useState(initial);
  const [query, setQuery] = useState('');
  const [creation, setCreation] = useState<PlaceFields | null>(null);
  const [distinguish, setDistinguish] = useState(false);
  const [error, setError] = useState('');
  const search = useRef<HTMLInputElement>(null);
  const options = entries.filter(entry => nameKey(placeLabel(entry)).includes(nameKey(query)));
  const equivalents = (draftPlace: NewPlace) => [draftPlace.kind, draftPlace.region, draftPlace.countryCode].some(Boolean);
  const toggle = (id: string) => {
    const ids = draft.availability.placeIds.includes(id) ? draft.availability.placeIds.filter(value => value !== id) : [...draft.availability.placeIds, id];
    if (ids.length + draft.newPlaces.length > 25) { setError('Choose up to 25 places.'); return; }
    setDraft({ ...draft, availability: ids.length || draft.newPlaces.length ? { mode: 'selected', placeIds: ids } : noAvailability });
    setError('');
  };
  const create = (value: PlaceFields) => {
    const name = normalizeName(value.name);
    if (!name) { setError('Enter a place name.'); return; }
    if (!/^(?:[A-Z]{2})?$/.test(value.countryCode)) { setError('Enter a two-letter country code.'); return; }
    const region = normalizeName(value.region);
    const newPlace: NewPlace = { name, ...(value.kind !== 'other' && { kind: value.kind }), ...(region && { region }), ...(value.countryCode && { countryCode: value.countryCode }) };
    const matches = (entry: NewPlace) => nameKey(entry.name) === nameKey(name) && (!equivalents(newPlace) || ((!newPlace.kind || entry.kind === newPlace.kind) && (!newPlace.region || nameKey(entry.region || '') === nameKey(newPlace.region)) && (!newPlace.countryCode || entry.countryCode === newPlace.countryCode)));
    if (entries.some(matches) || draft.newPlaces.some(matches)) { setError('This place already exists. Choose it instead.'); setDistinguish(true); return; }
    if (draft.availability.placeIds.length + draft.newPlaces.length >= 25) { setError('Choose up to 25 places.'); return; }
    setDraft({ availability: { mode: 'selected', placeIds: draft.availability.placeIds }, newPlaces: [...draft.newPlaces, newPlace] });
    setCreation(null);
    setQuery('');
    setError('');
    setDistinguish(false);
    requestAnimationFrame(() => search.current?.focus());
  };
  if (creation) return <form onSubmit={event => { event.preventDefault(); create(creation); }}>
    {error && <div className="dialog-error" role="alert">{error}</div>}
    <PlaceInputs value={creation} change={setCreation} />
    <div className="dialog-actions"><button type="button" className="secondary" onClick={() => { setCreation(null); setError(''); }}>Cancel</button><button className="primary">Add place</button></div>
  </form>;
  return <>
    <p className="dialog-copy">{bulk ? 'Keeps existing named places.' : 'Any one of these places works.'}</p>
    {bulk && bulk.anywhere > 0 && <p className="bulk-place-notice">
      {bulk.anywhere} {bulk.anywhere === 1 ? 'item marked Anywhere will use' : 'items marked Anywhere will use'} the selected places instead.
    </p>}
    {error && <div className="dialog-error" role="alert">{error}</div>}
    <label className="field">{bulk ? 'Search or add a place' : 'Search places'}<input ref={search} autoFocus maxLength={100} value={query} onChange={event => { setQuery(event.target.value); setError(''); setDistinguish(false); }} /></label>
    {!bulk && <div className="availability-choices">
      <button type="button" className={`secondary ${draft.availability.mode === 'unassigned' ? 'choice-active' : ''}`} aria-pressed={draft.availability.mode === 'unassigned'} onClick={() => setDraft({ availability: noAvailability, newPlaces: [] })}>No places set</button>
      <button type="button" className={`secondary ${draft.availability.mode === 'anywhere' ? 'choice-active' : ''}`} aria-pressed={draft.availability.mode === 'anywhere'} onClick={() => setDraft({ availability: { mode: 'anywhere', placeIds: [] }, newPlaces: [] })}>Anywhere</button>
    </div>}
    {(options.length > 0 || draft.newPlaces.length > 0) && <div className="organization-choice-list">
      {options.map(entry => <label className="organization-choice" key={entry.id}><input type="checkbox" checked={draft.availability.placeIds.includes(entry.id)} onChange={() => toggle(entry.id)} /><span>{placeLabel(entry)}</span></label>)}
      {draft.newPlaces.map((entry, index) => <label className="organization-choice" key={index}><input type="checkbox" checked onChange={() => {
        const newPlaces = draft.newPlaces.filter((_, entryIndex) => entryIndex !== index);
        setDraft({ newPlaces, availability: newPlaces.length || draft.availability.placeIds.length ? draft.availability : noAvailability });
      }} /><span>{placeLabel({ ...emptyPlaceFields, ...entry })}</span></label>)}
    </div>}
    {normalizeName(query) && <button type="button" className="text-button" onClick={() => create({ ...emptyPlaceFields, name: query })}><Plus size={16} />Create “{normalizeName(query)}”</button>}
    {distinguish && <button type="button" className="text-button distinguish-place" onClick={() => { setCreation({ ...emptyPlaceFields, name: normalizeName(query) }); setError(''); }}>Add a different place</button>}
    <div className="dialog-actions">{bulk ? <button type="button" className="secondary" onClick={back}>Cancel</button> : <PickerBack back={back} />}
      <button type="button" className="primary" disabled={Boolean(bulk && (!bulk.count || !draft.availability.placeIds.length && !draft.newPlaces.length))} onClick={() => done(draft)}>
        {bulk ? `Add to ${bulk.count} ${bulk.count === 1 ? 'item' : 'items'}` : 'Done'}
      </button>
    </div>
  </>;
}

export function PersonPicker({ initial = emptyPeople, people, itemText, requireChoice, bulkCount, back, done }: {
  initial?: PersonDraft;
  people: NonNullable<GatherList['recipients']>;
  itemText?: string;
  requireChoice?: boolean;
  bulkCount?: number;
  back: () => void;
  done: (value: PersonDraft) => void;
}) {
  const [draft, setDraft] = useState(initial);
  const [query, setQuery] = useState('');
  const [error, setError] = useState('');
  const search = useRef<HTMLInputElement>(null);
  const name = normalizeName(query);
  const options = people.filter(entry => nameKey(entry.name).includes(nameKey(name)) || draft.ids.includes(entry.id));
  const selected = draft.ids.length + draft.newPeople.length;
  const isBulk = bulkCount !== undefined;
  const nameExists = people.some(entry => nameKey(entry.name) === nameKey(name)) || draft.newPeople.some(entry => nameKey(entry.name) === nameKey(name));
  const toggle = (id: string) => {
    if (!draft.ids.includes(id) && selected >= 25) { setError('Choose up to 25 people.'); return; }
    setDraft({ ...draft, ids: draft.ids.includes(id) ? draft.ids.filter(value => value !== id) : [...draft.ids, id] });
    setError('');
  };
  return <>
    <p className="dialog-copy">{isBulk ? 'Keeps existing people on the selected items.' : itemText}</p>
    {error && <div className="dialog-error" role="alert">{error}</div>}
    <label className="field">Search or add a name<input ref={search} autoFocus maxLength={100} value={query} onChange={event => { setQuery(event.target.value); setError(''); }} /></label>
    {(options.length > 0 || draft.newPeople.length > 0) && <div className="organization-choice-list">
      {options.map(entry => <label className="organization-choice" key={entry.id}>
        <input type="checkbox" checked={draft.ids.includes(entry.id)} onChange={() => toggle(entry.id)} /><span>{entry.name}</span>
      </label>)}
      {draft.newPeople.map(({ name }) => <label className="organization-choice" key={name}>
        <input type="checkbox" checked onChange={() => { setDraft({ ...draft, newPeople: draft.newPeople.filter(entry => entry.name !== name) }); setError(''); }} /><span>{name}</span>
      </label>)}
    </div>}
    {name && !nameExists && <button type="button" className="text-button" onClick={() => {
      if (selected >= 25) { setError('Choose up to 25 people.'); return; }
      setDraft({ ...draft, newPeople: [...draft.newPeople, { name }] });
      setQuery(''); setError('');
      search.current?.focus();
    }}><Plus size={16} />Create “{name}”</button>}
    <div className="dialog-actions"><button type="button" className="secondary" onClick={back}>Cancel</button>
      <button type="button" className="primary" disabled={bulkCount === 0 || Boolean((isBulk || requireChoice) && !selected)} onClick={() => done(draft)}>
        {isBulk ? `Add to ${bulkCount} ${bulkCount === 1 ? 'item' : 'items'}` : 'Done'}
      </button>
    </div>
  </>;
}
