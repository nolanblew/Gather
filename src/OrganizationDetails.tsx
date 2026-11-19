import { useId, useState } from 'react';
import { ArrowLeft, ChevronDown, FileText, Hash, Layers, MapPin, UserRound } from 'lucide-react';
import Dialog from './Dialog';
import type { ItemGrouping, ItemSort } from './itemOrder';
import { dictionaryDeletion, filterOptions, matchesListFilters, namedPersonId, namedPlaceId, normalizeName, personFilter, placeFilter, placeLabel } from './organization';
import type { ListFilters } from './organization';
import type { GatherList, Mutation, Place, PlaceFields, Recipient } from './types';

export const detailChoices = {
  description: { label: 'Description', icon: FileText },
  place: { label: 'Place', icon: MapPin },
  person: { label: 'For someone', icon: UserRound },
  quantity: { label: 'Quantity', icon: Hash },
  section: { label: 'Section', icon: Layers },
};

export function DescriptionPreview({ text, className = '', lines = 2 }: { text: string; className?: string; lines?: number }) {
  const [open, setOpen] = useState(false);
  const id = useId();
  const long = text.length > (lines === 2 ? 150 : 235) || text.split('\n').length > lines;
  const visible = open || !long ? text : text.split('\n').slice(0, lines).join('\n').slice(0, lines === 2 ? 150 : 235).trimEnd() + '…';
  return <div className={`description-preview ${className}`}>
    <p id={id}>{visible}</p>
    {long && <button className="text-button" type="button" aria-expanded={open} aria-controls={id} onClick={() => setOpen(!open)}>{open ? 'Show less' : 'Show more'}</button>}
  </div>;
}

interface Choice<T> { value: T; label: string; detail?: string; detailLabel?: string }

export function RadioChoices<T extends string>({ label, value, options, choose }: { label: string; value: T; options: Choice<T>[]; choose: (value: T) => void }) {
  const group = useId();
  return <fieldset className="organization-choices">
    <legend>{label}</legend>
    <div className="organization-choice-list">
      {options.map(option => <label className="organization-choice" key={option.value}>
        <input type="radio" name={group} checked={option.value === value} onChange={() => choose(option.value)} />
        <span>{option.label}{option.detail && <small aria-label={option.detailLabel}>{option.detail}</small>}</span>
      </label>)}
    </div>
  </fieldset>;
}

export function ViewDialog({ list, filters, query, sort, groupBy, apply, close }: {
  list: GatherList;
  filters: ListFilters;
  query: string;
  sort: ItemSort;
  groupBy: ItemGrouping;
  apply: (filters: ListFilters, sort: ItemSort, groupBy: ItemGrouping) => void;
  close: () => void;
}) {
  const [draft, setDraft] = useState(filters);
  const [draftSort, setDraftSort] = useState(sort);
  const [draftGroup, setDraftGroup] = useState(groupBy);
  const groupName = useId();
  const [originalOptions] = useState(() => filterOptions(list));
  const currentOptions = filterOptions(list);
  const places = (list.places || []).filter(place => originalOptions.places.has(place.id) || currentOptions.places.has(place.id) || place.id === namedPlaceId(draft.place) || place.id === namedPlaceId(filters.place));
  const people = (list.recipients || []).filter(person => originalOptions.people.has(person.id) || currentOptions.people.has(person.id) || person.id === namedPersonId(draft.person) || person.id === namedPersonId(filters.person));
  const draftPlace = namedPlaceId(draft.place);
  const draftPerson = namedPersonId(draft.person);
  const currentDraft = {
    ...draft,
    place: draftPlace && !places.some(place => place.id === draftPlace) ? 'all' as const : draft.place,
    person: draftPerson && !people.some(person => person.id === draftPerson) ? 'all' as const : draft.person,
    notDone: list.kind === 'checklist' && !list.isTemplate && draft.notDone,
  };
  const showCompletion = list.kind === 'checklist' && !list.isTemplate && (originalOptions.hasChecked || currentOptions.hasChecked || draft.notDone || filters.notDone);
  const showPlaces = places.length > 0 || originalOptions.anywhere > 0 || currentOptions.anywhere > 0 || filters.place !== 'all' || draft.place !== 'all';
  const showPeople = people.length > 0 || originalOptions.people.size > 0 || filters.person !== 'all' || draft.person !== 'all';
  const resultCount = list.items.filter(item => matchesListFilters(item, currentDraft, query)).length;
  const groupOptions: { value: ItemGrouping; label: string }[] = [{ value: 'none', label: 'None' }];
  if (showPlaces || groupBy === 'place') groupOptions.push({ value: 'place', label: 'Place' });
  if (showPeople || groupBy === 'person') groupOptions.push({ value: 'person', label: 'Person' });
  return <Dialog title="View" close={close} busy={false} className="list-view-dialog">
    <label className="field">Sort by<select value={draftSort} onChange={event => setDraftSort(event.target.value as ItemSort)}>
      <option value="added">Date added (oldest first)</option>
      <option value="newest">Date added (newest first)</option>
      <option value="name">Name (A–Z)</option>
      <option value="manual">Manual order</option>
    </select></label>
    {groupOptions.length > 1 && <fieldset className="view-group-choices">
      <legend>Group by</legend>
      <div className="segmented">
        {groupOptions.map(({ value, label }) => <label key={value}>
          <input type="radio" name={groupName} checked={draftGroup === value} onChange={() => setDraftGroup(value)} />
          <span>{label}</span>
        </label>)}
      </div>
    </fieldset>}
    {showPlaces && <label className="field">Places<select value={currentDraft.place} onChange={event => setDraft({ ...currentDraft, place: event.target.value as ListFilters['place'] })}>
      <option value="all">All places</option>
      {places.map(place => <option key={place.id} value={placeFilter(place.id)}>{placeLabel(place)}</option>)}
      <option value="anywhere">Anywhere only</option>
      <option value="unassigned">No places set ({currentOptions.unassigned})</option>
    </select></label>}
    {showPeople && <label className="field">For someone<select value={currentDraft.person} onChange={event => setDraft({ ...currentDraft, person: event.target.value as ListFilters['person'] })}>
      <option value="all">All people</option>
      {people.map(person => <option key={person.id} value={personFilter(person.id)}>{person.name}</option>)}
      <option value="unassigned">No recipient</option>
    </select></label>}
    {showCompletion && <RadioChoices label="Completion" value={currentDraft.notDone ? 'notDone' : 'all'} options={[{ value: 'all', label: 'All items' }, { value: 'notDone', label: 'Not done' }]} choose={value => setDraft({ ...currentDraft, notDone: value === 'notDone' })} />}
    <p className="small-muted" aria-live="polite">{resultCount} {resultCount === 1 ? 'item matches' : 'items match'}</p>
    <div className="dialog-actions"><button className="secondary" onClick={close}>Cancel</button><button className="primary" onClick={() => apply(currentDraft, draftSort, draftGroup)}>Apply</button></div>
  </Dialog>;
}

export const emptyPlaceFields: PlaceFields = { name: '', kind: 'other', region: '', countryCode: '' };

export function PlaceInputs({ value, change }: { value: PlaceFields; change: (value: PlaceFields) => void }) {
  return <>
    <label className="field">Place name<input autoFocus maxLength={100} value={value.name} onChange={event => change({ ...value, name: event.target.value })} /></label>
    <details className="form-options" open={value.kind !== 'other' || Boolean(value.region) || Boolean(value.countryCode) ? true : undefined}>
      <summary className="text-button"><ChevronDown size={17} />More options</summary>
      <label className="field">Kind<select value={value.kind} onChange={event => change({ ...value, kind: event.target.value as PlaceFields['kind'] })}>
        <option value="other">Other</option><option value="city">City</option><option value="store">Store</option><option value="home">Home</option><option value="work">Work</option><option value="online">Online</option>
      </select></label>
      <label className="field">Region<input maxLength={100} value={value.region} onChange={event => change({ ...value, region: event.target.value })} /></label>
      <label className="field">Country code<input maxLength={2} value={value.countryCode} onChange={event => change({ ...value, countryCode: event.target.value.toUpperCase() })} placeholder="JP" /></label>
    </details>
  </>;
}

export function DictionaryDialog({ list, kind, error, clearError, busy, mutate, close }: { list: GatherList; kind: 'place' | 'recipient'; error: string; clearError: () => void; busy: boolean; mutate: Mutation; close: () => void }) {
  const [editingId, setEditingId] = useState('');
  const [draftPlace, setDraftPlace] = useState(emptyPlaceFields);
  const [personName, setPersonName] = useState('');
  const [confirmation, setConfirmation] = useState<{ id: string; token: string; count: number; label: string } | null>(null);
  const [validation, setValidation] = useState('');
  const isPlace = kind === 'place';
  const title = isPlace ? 'Manage places' : 'Manage people';
  const entries: (Place | Recipient)[] = isPlace ? list.places || [] : list.recipients || [];
  const entityKey = isPlace ? 'placeId' : 'recipientId';
  const reset = () => { setEditingId(''); setConfirmation(null); setValidation(''); clearError(); };
  const createOrRename = async () => {
    const name = normalizeName(isPlace ? draftPlace.name : personName);
    if (!name) { setValidation(isPlace ? 'Enter a place name.' : 'Enter a name.'); return; }
    if (isPlace && !/^(?:[A-Z]{2})?$/.test(draftPlace.countryCode)) { setValidation('Enter a two-letter country code.'); return; }
    if (await mutate(`${kind}.${editingId === 'new' ? 'create' : 'update'}`, { listId: list.id, ...(editingId === 'new' ? {} : { [entityKey]: editingId }), ...(isPlace ? draftPlace : {}), name })) reset();
  };
  return <Dialog title={confirmation ? `Delete ${confirmation.label}?` : editingId ? isPlace ? 'Edit place' : 'Edit person' : title} busy={busy} close={close}>
    {(error || validation) && <div className="dialog-error" role="alert">{validation || error}</div>}
    {confirmation ? <>
      <p className="dialog-copy">{isPlace ? 'It' : 'They'} will be removed from {confirmation.count} {confirmation.count === 1 ? 'item' : 'items'}. {isPlace ? 'Items and other places will stay.' : 'Items will stay.'}</p>
      <div className="dialog-actions"><button className="secondary" onClick={reset}>Cancel</button><button className="danger" onClick={async () => {
        if (await mutate(`${kind}.delete`, { listId: list.id, [entityKey]: confirmation.id, expectedAssignmentToken: confirmation.token })) reset();
        else setConfirmation(null);
      }}>{isPlace ? 'Delete place' : 'Delete person'}</button></div>
    </> : editingId ? <form onSubmit={event => { event.preventDefault(); void createOrRename(); }}>
      {isPlace ? <PlaceInputs value={draftPlace} change={setDraftPlace} /> : <label className="field">Name<input autoFocus maxLength={100} value={personName} onChange={event => setPersonName(event.target.value)} /></label>}
      <div className="dialog-actions"><button className="secondary" type="button" onClick={reset}>Cancel</button><button className="primary">Save</button></div>
    </form> : <>
      <ul className="dictionary-entries">{entries.map(entry => {
        const label = 'kind' in entry ? placeLabel(entry) : entry.name;
        return <li key={entry.id}>
          <span>{label}</span>
          <button className="text-button" aria-label={`Edit ${label}`} onClick={() => {
            reset(); setEditingId(entry.id);
            if ('kind' in entry) setDraftPlace(entry); else setPersonName(entry.name);
          }}>Edit</button>
          <button className="text-button danger-text" aria-label={`Delete ${label}`} onClick={() => {
            const deletion = dictionaryDeletion(list, kind, entry.id);
            if (deletion) { clearError(); setConfirmation({ id: entry.id, label, ...deletion }); }
          }}>Delete</button>
        </li>;
      })}</ul>
      <div className="dialog-actions"><button className="secondary" onClick={() => { reset(); setEditingId('new'); setDraftPlace(emptyPlaceFields); setPersonName(''); }}>{isPlace ? 'Add place' : 'Add person'}</button><button className="primary" onClick={close}>Done</button></div>
    </>}
  </Dialog>;
}

export function PickerBack({ back }: { back: () => void }) {
  return <button type="button" className="text-button" onClick={back}><ArrowLeft size={16} />Back to item</button>;
}
