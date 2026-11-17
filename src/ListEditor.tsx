import { useEffect, useMemo, useRef, useState, type FormEvent, type ReactNode } from 'react';
import { ArrowLeft, Check, ChevronDown, Copy, MoreHorizontal, Plus, Search, Star, Users, X } from 'lucide-react';
import ItemEditor, { makeItemDraft } from './ItemEditor';
import { DescriptionPreview, DictionaryDialog, FiltersDialog } from './OrganizationDetails';
import { byPosition, canonicalItemGroups } from './itemOrder';
import { emptyFilters, filterDefaults, filterLabels, filterOptions, hasFilters, matchesListFilters, namedPersonId, namedPlaceId, orderGuard, placeLabel, placeSummary } from './organization';
import type { ListFilters } from './organization';
import type { Category, GatherList, Item, Mutation, Section, Workspace } from './types';

export interface ListView {
  query: string;
  filters: ListFilters;
  showDetails: boolean;
}

interface Props {
  list: GatherList;
  category?: Category;
  icon: ReactNode;
  badges: ReactNode;
  organization: boolean;
  busy: boolean;
  error: string;
  clearError: () => void;
  savedView?: ListView;
  updateView: (view: ListView) => void;
  mutate: Mutation;
  back: () => void;
  edit: () => void;
  section: (section?: Section) => void;
  removeSection: (section: Section) => void;
  clone: () => void;
  listConfirm: (action: 'archive' | 'delete' | 'reset') => void;
  share: () => void;
}

function ActionMenu({ label, children }: { label: string; children: ReactNode }) {
  return <details className="context-menu" onClick={event => {
    if ((event.target as HTMLElement).closest('button')) { event.currentTarget.open = false; event.currentTarget.querySelector('summary')?.focus(); }
  }} onKeyDown={event => {
    if (event.key === 'Escape') { event.currentTarget.open = false; event.currentTarget.querySelector('summary')?.focus(); }
  }}>
    <summary className="icon-button" aria-label={label}><MoreHorizontal size={21} /></summary><div className="menu-panel">{children}</div>
  </details>;
}

export default function ListEditor({ list, category, icon, badges, organization, busy, error, clearError, savedView, updateView, mutate, back, edit, section, removeSection, clone, listConfirm, share }: Props) {
  const [draft, setDraft] = useState('');
  const [addSection, setAddSection] = useState('');
  const [addError, setAddError] = useState('');
  const [query, setQuery] = useState(savedView?.query || '');
  const [filters, setFilters] = useState<ListFilters>(savedView?.filters || emptyFilters);
  const [doneOpen, setDoneOpen] = useState<boolean | null>(null);
  const [showDetails, setShowDetails] = useState(savedView?.showDetails ?? true);
  const [overlay, setOverlay] = useState<'filters' | 'place' | 'recipient' | null>(null);
  const [editing, setEditing] = useState<{ item: Item; fromQuickAdd: boolean } | null>(null);
  const [hiddenItemId, setHiddenItemId] = useState<string | null>(null);
  const [filterMessage, setFilterMessage] = useState('');
  const addRef = useRef<HTMLInputElement>(null);
  const rowButtons = useRef(new Map<string, HTMLButtonElement>());
  const reviewOrderId = useRef('');
  const previousList = useRef(list);
  const done = list.items.filter(item => item.checked).length;
  const isChecklist = list.kind !== 'notes' && !list.isTemplate;
  const groups = useMemo(() => canonicalItemGroups(list), [list]);
  const ordinals = useMemo(() => list.numbered ? new Map(groups.flatMap(group => group.items).map((item, index) => [item.id, index + 1])) : null, [groups, list.numbered]);
  const places = new Map((list.places || []).map(place => [place.id, place]));
  const recipients = new Map((list.recipients || []).map(person => [person.id, person]));
  const filterQuery = query.trim().toLocaleLowerCase();
  const activePlace = namedPlaceId(filters.place);
  const activePerson = namedPersonId(filters.person);
  const activeFilters: ListFilters = {
    place: activePlace && !places.has(activePlace) ? 'all' : filters.place,
    person: activePerson && !recipients.has(activePerson) ? 'all' : filters.person,
    notDone: isChecklist && filters.notDone,
  };
  const usingFilters = hasFilters(activeFilters);
  const filtered = usingFilters || Boolean(filterQuery);
  const isVisible = (item: Item) => matchesListFilters(item, activeFilters, filterQuery);
  const shown = list.items.filter(isVisible).length;
  const detailsExist = list.items.some(item => Boolean(item.note || item.quantity || item.recipientId || item.availability?.mode === 'anywhere' || item.availability?.placeIds.length));
  const options = filterOptions(list);
  const canFilter = usingFilters || Boolean(isChecklist && options.hasChecked || options.places.size || options.anywhere || options.people.size);
  const summary = [...filterLabels(list, activeFilters), ...(filterQuery ? [query.trim()] : []), `${shown} shown`].join(' · ');
  const defaults = filterDefaults(activeFilters);
  const defaultLabels = [activePlace && places.get(activePlace) ? placeLabel(places.get(activePlace)!) : activeFilters.place === 'anywhere' ? 'Anywhere' : activeFilters.place === 'unassigned' ? 'No places set' : '',
    activePerson && recipients.get(activePerson) ? `For ${recipients.get(activePerson)!.name}` : activeFilters.person === 'unassigned' ? 'For someone: Not set' : ''].filter(Boolean);
  const selectedSection = list.sections.some(section => section.id === addSection) ? addSection : null;

  useEffect(() => { if (!list.items.length) addRef.current?.focus(); }, []);
  useEffect(() => { updateView({ query, filters, showDetails }); }, [query, filters, showDetails]);
  useEffect(() => {
    const previous = previousList.current;
    previousList.current = list;
    const messages: string[] = [];
    if (activePlace && activeFilters.place === 'all') {
      const entry = previous.places?.find(place => place.id === activePlace);
      messages.push(`${entry ? placeLabel(entry) : 'The place'} was removed. The filter was cleared.`);
    }
    if (activePerson && activeFilters.person === 'all') {
      const entry = previous.recipients?.find(person => person.id === activePerson);
      messages.push(`${entry?.name || 'The person'} was removed. The filter was cleared.`);
    }
    if (messages.length) setFilterMessage(messages.join(' '));
    if (activeFilters.place !== filters.place || activeFilters.person !== filters.person || activeFilters.notDone !== filters.notDone) setFilters(activeFilters);
  }, [list, filters]);

  const clearFilters = () => { setFilters(emptyFilters); setQuery(''); setFilterMessage(''); setHiddenItemId(null); setDoneOpen(null); };
  const focusRow = (id: string, focus = true) => requestAnimationFrame(() => {
    const target = rowButtons.current.get(id);
    target?.scrollIntoView({ block: 'center' });
    if (focus) target?.focus({ preventScroll: true });
  });
  const viewItem = (id: string) => {
    const item = list.items.find(entry => entry.id === id);
    clearFilters();
    setHiddenItemId(null);
    if (item?.checked) setDoneOpen(true);
    focusRow(id);
  };
  const closeEditing = () => {
    setEditing(null);
    if (reviewOrderId.current) { focusRow(reviewOrderId.current); reviewOrderId.current = ''; }
  };
  const editingSaved = (next: Workspace, id: string | undefined) => {
    const saved = next.lists.find(entry => entry.id === list.id)?.items.find(entry => entry.id === id);
    if (saved && !isVisible(saved)) setHiddenItemId(saved.id);
    else setHiddenItemId(null);
    if (editing?.fromQuickAdd) { setDraft(''); setAddError(''); requestAnimationFrame(() => addRef.current?.focus()); }
    closeEditing();
  };
  const submit = async (event: FormEvent) => {
    event.preventDefault();
    if (!draft.trim()) { setAddError('Enter an item.'); addRef.current?.focus(); return; }
    if (addSection && !selectedSection) { setAddError('This section is no longer available. Choose another section.'); return; }
    const result = await mutate('item.create', {
      listId: list.id, text: draft, sectionId: selectedSection, ...orderGuard(list),
      ...(defaults.availability.mode !== 'unassigned' && { availability: defaults.availability }),
      ...(defaults.recipientId && { recipientId: defaults.recipientId }),
    });
    if (result) {
      setDraft('');
      setAddError('');
      editingSaved(result, result.createdItemId);
      requestAnimationFrame(() => addRef.current?.focus());
    }
  };
  const openItem = (item: Item) => { clearError(); setEditing({ item, fromQuickAdd: false }); };
  const openOverlay = (next: typeof overlay) => { clearError(); setOverlay(next); };
  const row = (item: Item) => {
    const ordinal = ordinals?.get(item.id);
    const recipient = item.recipientId && item.recipientId !== namedPersonId(activeFilters.person) ? recipients.get(item.recipientId)?.name : '';
    const metadata = [item.quantity, recipient && `For ${recipient}`, placeSummary(item, places, activeFilters.place)].filter(Boolean).join(' · ');
    return <div className={`item-row ${isChecklist && item.checked ? 'completed' : ''}`} key={item.id}>
      {isChecklist ? <label className="check-target">
        <input type="checkbox" checked={item.checked} disabled={busy} aria-label={`${item.checked ? 'Uncheck' : 'Complete'} ${item.text}`} onChange={() => void mutate('item.update', { listId: list.id, itemId: item.id, checked: !item.checked })} />
        <span className="checkbox-visual" aria-hidden="true">{item.checked && <Check size={15} />}</span>
      </label> : !ordinal && <span className="note-bullet" aria-hidden="true">•</span>}
      {ordinal != null && <span className="item-ordinal" aria-hidden="true">{ordinal.toLocaleString()}.</span>}
      <div className="item-main">
        <button className="item-content" aria-label={ordinal == null ? undefined : `${ordinal.toLocaleString()}. ${item.text}${showDetails && metadata ? `. ${metadata}` : ''}`} ref={node => {
          if (node) rowButtons.current.set(item.id, node); else rowButtons.current.delete(item.id);
        }} onClick={() => openItem(item)}>
          <span className="item-text">{item.text}</span>{showDetails && metadata && <span className="item-metadata">{metadata}</span>}
        </button>
        {showDetails && item.note && <DescriptionPreview text={item.note} className="item-description" />}
      </div>
      <button className="icon-button item-menu" aria-label={`Edit ${item.text}`} onClick={() => openItem(item)}><MoreHorizontal size={21} /></button>
    </div>;
  };

  const checked = isChecklist && !list.numbered && !activeFilters.notDone ? groups.flatMap(group => group.items).filter(item => item.checked && isVisible(item)) : [];
  const showDone = doneOpen ?? filtered;
  const orderedSections = list.sections.slice().sort(byPosition);
  return <div className="editor">
    <div className="editor-back"><button className="text-button" onClick={back}><ArrowLeft size={18} />Back to collection</button></div>
    <header className="editor-heading">
      <div className={`editor-icon ${list.color}`}>{icon}</div>
      <div className="editor-title">
        <div className="editor-badges"><span className="badge category-badge"><span className={`dot ${category?.color || list.color}`} />{category?.name || 'Uncategorized'}</span>{badges}</div>
        <h1>{list.title}</h1>
        {list.description && <DescriptionPreview text={list.description} lines={3} />}
      </div>
      <div className="editor-actions">
        <button className={`icon-button favorite ${list.favorite ? 'is-favorite' : ''}`} aria-label={list.favorite ? 'Unfavorite list' : 'Favorite list'} aria-pressed={list.favorite} disabled={busy} onClick={() => void mutate('list.update', { listId: list.id, favorite: !list.favorite })}><Star size={23} fill={list.favorite ? 'currentColor' : 'none'} /></button>
        <ActionMenu label="List actions">
          <button onClick={edit}>Edit details</button>
          {canFilter && <button onClick={() => openOverlay('filters')}>Filter this list</button>}
          {organization && list.role !== 'editor' && <button aria-pressed={Boolean(list.numbered)} aria-description={list.shared ? 'Everyone who can see this list sees item numbers.' : undefined} disabled={busy} onClick={() => void mutate('list.update', { listId: list.id, numbered: !list.numbered, ...orderGuard(list, true) })}>{list.numbered && <Check size={16} aria-hidden="true" />}Number items</button>}
          {detailsExist && <button onClick={() => setShowDetails(!showDetails)}>{showDetails ? 'Hide item details' : 'Show item details'}</button>}
          <button onClick={() => section()}>Add section</button>
          {list.places?.length ? <button onClick={() => openOverlay('place')}>Manage places</button> : null}
          {list.recipients?.length ? <button onClick={() => openOverlay('recipient')}>Manage people</button> : null}
          <button onClick={share}>{list.role === 'editor' ? 'People & access' : 'Share list'}</button>
          <button onClick={clone}>{list.isTemplate ? 'Start list' : 'Make a private copy'}</button>
          {!list.isTemplate && list.role !== 'editor' && <button disabled={busy} onClick={() => void mutate('list.update', { listId: list.id, isTemplate: true })}>Make a template</button>}
          {isChecklist && !list.archived && done > 0 && <button onClick={() => listConfirm('reset')}>Start again</button>}
          {list.archived ? <button disabled={busy} onClick={() => void mutate('list.update', { listId: list.id, archived: false })}>Restore for me</button> : <button onClick={() => listConfirm('archive')}>Archive for me</button>}
          {list.role !== 'editor' && <button className="danger-text" onClick={() => listConfirm('delete')}>Delete list</button>}
        </ActionMenu>
      </div>
    </header>
    {list.isTemplate && <div className="template-banner"><span>This is a reusable template. Start a list to create a private copy.</span><button className="primary" disabled={busy} onClick={clone}><Copy size={17} />Start list</button></div>}
    <div className="list-sharing-line"><span>{list.shared ? <><Users size={16} aria-hidden="true" />Shared · {list.role === 'editor' ? `Owned by ${list.ownerUsername}` : 'You own this list'}</> : 'Private · Only you have access'}</span><button className="secondary" disabled={busy} onClick={share}><Users size={17} aria-hidden="true" />{list.role === 'editor' ? 'People & access' : 'Share'}</button></div>
    {list.items.length > 0 && (isChecklist ? <div className="editor-progress"><div className="progress-summary"><span><strong>{done} of {list.items.length}</strong> done <span className="small-muted">· {list.items.length - done} remaining</span></span></div><div className="progress-track"><div style={{ width: `${done / list.items.length * 100}%` }} /></div></div> : <div className="editor-count">{list.items.length} {list.items.length === 1 ? 'item' : 'items'}</div>)}
    {(list.items.length > 0 || query) && <div className="editor-toolbar organization-toolbar">
      <label className="item-search"><Search size={17} aria-hidden="true" /><input aria-label="Filter items" placeholder="Filter items" value={query} onChange={event => { setQuery(event.target.value); setDoneOpen(null); }} /></label>
      {query && <button className="icon-button" aria-label="Clear item search" onClick={() => setQuery('')}><X size={17} /></button>}
    </div>}
    {usingFilters && <div className="active-list-filters"><button className="text-button" onClick={() => openOverlay('filters')}><span>{summary}</span><ChevronDown size={16} aria-hidden="true" /></button><button className="text-button" onClick={clearFilters}>Clear</button></div>}
    {list.numbered && filtered && shown > 0 && shown < list.items.length && <p className="small-muted numbered-filter-help">Numbers follow the full list order.</p>}
    {filterMessage && <p className="filter-message" role="status">{filterMessage}</p>}
    {hiddenItemId && <div className="filtered-save" role="status">Item saved. It isn't shown with these filters. <button className="text-button" onClick={() => viewItem(hiddenItemId)}>View item</button></div>}
    <div className="checklist">
      {!list.items.length && !filtered && <p className="editor-empty">Add your first item.</p>}
      {groups.map((group, index) => {
        const visible = group.items.filter(item => isVisible(item) && (!isChecklist || list.numbered || !item.checked));
        if (!visible.length && (filtered || group.id === null)) return null;
        const currentSection = orderedSections[index - 1];
        return <section className="item-group" key={group.id || 'none'}>
          {group.title && <div className="section-heading"><h2>{group.title}</h2><span className="small-muted">{visible.length}</span>{currentSection && <ActionMenu label={`${group.title} section actions`}>
            <button onClick={() => section(currentSection)}>Rename section</button>
            {filtered ? <button onClick={clearFilters}>Edit order</button> : <>
              <button disabled={busy || index <= 1} onClick={() => void mutate('section.move', { listId: list.id, sectionId: group.id, index: index - 2, ...orderGuard(list) })}>Move section up</button>
              <button disabled={busy || index >= orderedSections.length} onClick={() => void mutate('section.move', { listId: list.id, sectionId: group.id, index, ...orderGuard(list) })}>Move section down</button>
            </>}
            <button className="danger-text" disabled={busy} onClick={() => removeSection(currentSection)}>Delete section</button>
          </ActionMenu>}</div>}
          {visible.map(row)}
        </section>;
      })}
      {filtered && shown === 0 && <div className="filter-empty"><p>No items match these filters.</p><button className="text-button" onClick={clearFilters}>Clear filters</button></div>}
      {checked.length > 0 && <div className="done-area"><button className="done-heading" aria-expanded={showDone} onClick={() => setDoneOpen(!showDone)}><ChevronDown className={showDone ? 'rotated' : ''} size={20} /><span>Done</span><span className="badge">{checked.length}</span><span className="small-muted">{showDone ? 'Hide completed' : 'Show completed'}</span></button>{showDone && checked.map(row)}</div>}
    </div>
    <div className="quick-add-wrap"><form className="quick-add" onSubmit={submit}>
      <div className="quick-add-top"><Plus size={21} aria-hidden="true" /><input ref={addRef} aria-label="Add item" placeholder="Add item…" maxLength={500} disabled={busy} value={draft} onChange={event => { setDraft(event.target.value); setAddError(''); }} /><button className="primary" disabled={busy} type="submit">{busy ? 'Saving…' : 'Add'}</button></div>
      {(orderedSections.length > 0 || addSection) && <label className="add-section-select"><span>Add to</span><select aria-label="Add item to section" value={addSection} onChange={event => { setAddSection(event.target.value); setAddError(''); }}><option value="">Unsectioned</option>{addSection && !selectedSection && <option value={addSection} disabled>Section removed</option>}{orderedSections.map(section => <option key={section.id} value={section.id}>{section.title}</option>)}</select></label>}
      {defaultLabels.length > 0 && <div className="quick-add-context"><span>{defaultLabels.join(' · ')}</span><button type="button" className="text-button" disabled={busy} onClick={() => {
        clearError(); setEditing({ item: makeItemDraft({ text: draft, sectionId: selectedSection, ...defaults }), fromQuickAdd: true });
      }}>Edit details</button></div>}
      {addError && <span className="field-error" role="alert">{addError}</span>}
    </form></div>
    {overlay === 'filters' && <FiltersDialog list={list} filters={activeFilters} query={filterQuery} close={() => setOverlay(null)} apply={next => {
      setFilters(next); setOverlay(null); setFilterMessage(''); setHiddenItemId(null); setDoneOpen(null);
    }} />}
    {(overlay === 'place' || overlay === 'recipient') && <DictionaryDialog list={list} kind={overlay} busy={busy} error={error} clearError={clearError} mutate={mutate} close={() => { clearError(); setOverlay(null); }} />}
    {editing && <ItemEditor list={list} initial={editing.item} organization={organization} filtered={filtered} busy={busy} error={error} clearError={clearError} mutate={mutate} saved={editingSaved} close={() => { clearError(); closeEditing(); }} showFullOrder={() => {
      clearFilters();
      if (editing.item.checked) setDoneOpen(true);
      if (editing.item.id) { reviewOrderId.current = editing.item.id; focusRow(editing.item.id, false); }
    }} />}
  </div>;
}
