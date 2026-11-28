import AnimatedChecklist from './AnimatedChecklist';
import { useCallback, useEffect, useId, useMemo, useRef, useState, type FormEvent, type ReactNode } from 'react';
import { flushSync } from 'react-dom';
import { ArrowDown, ArrowLeft, ArrowUp, Check, ChevronDown, ChevronRight, Copy, GripVertical, Link2, ListOrdered, MapPin, MoreHorizontal, Plus, Search, SlidersHorizontal, SquareCheck, Star, UserRoundPlus, Users, X } from 'lucide-react';
import { listCoverUrl } from './api';
import { useAuth } from './auth';
import Dialog from './Dialog';
import GroupOrderDialog from './GroupOrderDialog';
import ItemEditor, { makeItemDraft, PersonPicker, PlacesPicker } from './ItemEditor';
import RichDescription from './RichDescription';
import { SubchecklistPreview } from './Subchecklist';
import normalizeCover from './normalizeCover';
import { DescriptionPreview, DictionaryDialog, ViewDialog } from './OrganizationDetails';
import { byPosition, canonicalItemGroups, itemGroupIds, itemOrdinals, reorderItemIds, sortItemGroupIds, splitItemGroups, type ItemGrouping, type ItemSort } from './itemOrder';
import { emptyFilters, filterDefaults, filterLabels, filterOptions, hasFilters, itemRecipientIds, matchesListFilters, namedPersonId, namedPlaceId, noAvailability, orderGuard, personFilter, placeFilter, placeLabel, placeSummary } from './organization';
import { useItemDrag } from './useItemDrag';
import useListHeaderMotion from './useListHeaderMotion';
import type { ListFilters } from './organization';
import type { Category, GatherList, Item, Mutation, Payload, Section, Workspace } from './types';

const emptyGroupOrder: string[] = [];

export interface ListView {
  query: string;
  filters: ListFilters;
  showDetails: boolean;
  sort: ItemSort;
  groupBy: ItemGrouping;
}

interface Props {
  list: GatherList;
  lists: Pick<GatherList, 'id' | 'title'>[];
  openLinkedList: (id: string) => void;
  canDeleteDone: boolean;
  richDescriptions: boolean;
  subchecklists: boolean;
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
  listConfirm: (action: 'archive' | 'delete' | 'reset' | 'deleteDone') => void;
  share: () => void;
  openAppearance: () => void;
  openSettings: () => void;
  uploadCover?: (image: Blob) => Promise<boolean>;
}

function storedSort(key: string): ItemSort | null {
  try {
    const stored = localStorage.getItem(key);
    return stored === 'added' || stored === 'newest' || stored === 'name' || stored === 'manual' ? stored : null;
  } catch { return null; }
}

function ActionMenu({ label, children }: { label: string; children: ReactNode }) {
  return <details className="context-menu" onClick={event => {
    if ((event.target as HTMLElement).closest('button')) { event.currentTarget.open = false; event.currentTarget.querySelector('summary')?.focus(); }
  }} onBlur={event => {
    if (event.relatedTarget && !event.currentTarget.contains(event.relatedTarget)) event.currentTarget.open = false;
  }} onKeyDown={event => {
    if (event.key === 'Escape') { event.currentTarget.open = false; event.currentTarget.querySelector('summary')?.focus(); }
  }}>
    <summary className="icon-button" aria-label={label}><MoreHorizontal size={21} /></summary><div className="menu-panel">{children}</div>
  </details>;
}

export default function ListEditor({ list, lists, openLinkedList, canDeleteDone, richDescriptions, subchecklists, category, icon, badges, organization, busy, error, clearError, savedView, updateView, mutate, back, edit, section, removeSection, clone, listConfirm, share, openAppearance, openSettings, uploadCover }: Props) {
  const { session } = useAuth();
  const sortKey = `gather-list-sort:${session.userId || session.username}:${list.id}`;
  const [draft, setDraft] = useState('');
  const [addSection, setAddSection] = useState('');
  const [addError, setAddError] = useState('');
  const [query, setQuery] = useState(savedView?.query || '');
  const [searchOpen, setSearchOpen] = useState(false);
  const searchFieldId = useId();
  const [filters, setFilters] = useState<ListFilters>(savedView?.filters || emptyFilters);
  const [doneOpen, setDoneOpen] = useState<boolean | null>(null);
  const [showDetails, setShowDetails] = useState(savedView?.showDetails ?? true);
  const [sort, setSort] = useState<ItemSort>(() => savedView?.sort ?? storedSort(sortKey) ?? (list.numbered ? 'manual' : 'added'));
  const [groupBy, setGroupBy] = useState<ItemGrouping>(savedView?.groupBy ?? 'none');
  const [mode, setMode] = useState<'select' | 'reorder' | null>(null);
  const [groupOrderOpen, setGroupOrderOpen] = useState(false);
  const [selection, setSelection] = useState<Set<string>>(() => new Set());
  const [bulk, setBulk] = useState<'place' | 'person' | null>(null);
  const [actionMessage, setActionMessage] = useState('');
  const [overlay, setOverlay] = useState<'filters' | 'place' | 'recipient' | null>(null);
  const [editing, setEditing] = useState<{ item: Item; fromQuickAdd: boolean } | null>(null);
  const [hiddenItemId, setHiddenItemId] = useState<string | null>(null);
  const [filterMessage, setFilterMessage] = useState('');
  const [coverBusy, setCoverBusy] = useState(false);
  const [coverNotice, setCoverNotice] = useState('');
  const [coverError, setCoverError] = useState('');
  const [brokenCoverId, setBrokenCoverId] = useState<string>();
  const addRef = useRef<HTMLInputElement>(null);
  const searchRef = useRef<HTMLInputElement>(null);
  const searchButtonRef = useRef<HTMLButtonElement>(null);
  const coverFileRef = useRef<HTMLInputElement>(null);
  const { editorRef, toolbarRef, heroRef, titleRef } = useListHeaderMotion();
  const reviewOrderId = useRef('');
  const previousList = useRef(list);
  const done = list.items.filter(item => item.checked).length;
  const doneSteps = list.items.some(item => item.subitems?.some(step => step.checked));
  const isChecklist = list.kind !== 'notes' && !list.isTemplate;
  const listIds = useMemo(() => new Set(lists.map(entry => entry.id)), [lists]);
  const displayedSort = mode === 'reorder' ? 'manual' : sort;
  const groups = useMemo(() => canonicalItemGroups(list, displayedSort), [list, displayedSort]);
  const ordinals = useMemo(() => list.numbered ? itemOrdinals(list) : null, [list]);
  const places = useMemo(() => new Map((list.places || []).map(place => [place.id, place])), [list.places]);
  const recipients = useMemo(() => new Map((list.recipients || []).map(person => [person.id, person])), [list.recipients]);
  const groupLabel = useCallback((id: string) => {
    if (id === 'unassigned') return groupBy === 'place' ? 'No places set' : 'No recipient';
    if (id === 'anywhere') return 'Anywhere';
    if (id.startsWith('place:')) {
      const place = places.get(id.slice(6));
      return place ? placeLabel(place) : '';
    }
    return recipients.get(id.slice(7))?.name || '';
  }, [groupBy, places, recipients]);
  const storedGroupOrder = (groupBy === 'person' ? list.personGroupOrder : groupBy === 'place' ? list.placeGroupOrder : undefined) ?? emptyGroupOrder;
  const orderedGroupIds = useMemo(() => groupBy === 'none' ? emptyGroupOrder : sortItemGroupIds(
    [...new Set(list.items.flatMap(item => itemGroupIds(item, groupBy)))], storedGroupOrder, groupLabel
  ), [list.items, groupBy, storedGroupOrder, groupLabel]);
  const groupPositions = useMemo(() => new Map(orderedGroupIds.map((id, index) => [id, index])), [orderedGroupIds]);
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
  const detailsExist = list.items.some(item => Boolean(item.note || item.description?.length || item.subitems?.length || item.quantity || itemRecipientIds(item).length || item.availability?.mode === 'anywhere' || item.availability?.placeIds.length));
  const summary = [...filterLabels(list, activeFilters), ...(filterQuery ? [query.trim()] : []), `${shown} shown`].join(' · ');
  const defaults = filterDefaults(activeFilters);
  const defaultLabels = [activePlace && places.get(activePlace) ? placeLabel(places.get(activePlace)!) : activeFilters.place === 'anywhere' ? 'Anywhere' : activeFilters.place === 'unassigned' ? 'No places set' : '',
    activePerson && recipients.get(activePerson) ? `For ${recipients.get(activePerson)!.name}` : activeFilters.person === 'unassigned' ? 'For someone: Not set' : ''].filter(Boolean);
  const selectedSection = list.sections.some(section => section.id === addSection) ? addSection : null;
  const selectedItems = list.items.filter(item => selection.has(item.id));
  const selectedCount = selectedItems.length;
  const selectedAnywhere = selectedItems.filter(item => item.availability?.mode === 'anywhere').map(item => item.id);
  const coverSrc = list.coverId && brokenCoverId !== list.coverId ? listCoverUrl(list.id, list.coverId) : undefined;

  const closeSearch = () => {
    setSearchOpen(false);
    searchButtonRef.current?.focus({ preventScroll: true });
  };
  const toggleSearch = () => {
    if (searchOpen) { closeSearch(); return; }
    flushSync(() => setSearchOpen(true));
    searchRef.current?.focus({ preventScroll: true });
  };
  const changeCover = async (file: File) => {
    if (!uploadCover || busy || coverBusy) return;
    setCoverBusy(true);
    setCoverNotice('');
    setCoverError('');
    clearError();
    try {
      const image = await normalizeCover(file);
      if (await uploadCover(image)) { setBrokenCoverId(undefined); setCoverNotice('Cover updated.'); }
      else setCoverError('Couldn’t save the cover. Try again.');
    } catch (failure) {
      setCoverError(failure instanceof Error ? failure.message : 'Couldn’t prepare the cover.');
    } finally { setCoverBusy(false); }
  };

  useEffect(() => { if (!list.items.length) addRef.current?.focus(); }, []);
  useEffect(() => {
    if (!coverNotice) return;
    const timer = window.setTimeout(() => setCoverNotice(''), 3000);
    return () => clearTimeout(timer);
  }, [coverNotice]);
  useEffect(() => { updateView({ query, filters, showDetails, sort, groupBy }); }, [query, filters, showDetails, sort, groupBy]);
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
    const target = editorRef.current?.querySelector<HTMLElement>(`[data-item-control][data-item-id="${CSS.escape(id)}"]`);
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
    if (busy) return;
    addRef.current?.focus();
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
  const saveReorder = async (itemIds: string[], sourceId: string, targetId: string, after: boolean, token = list.orderToken) => {
    if (busy || !token) return;
    const reordered = reorderItemIds(itemIds, sourceId, targetId, after);
    if (!reordered) return;
    setActionMessage('');
    if (await mutate('item.reorderVisible', { listId: list.id, itemIds: reordered, expectedOrderToken: token })) {
      chooseSort('manual');
      setActionMessage(`Moved ${list.items.find(item => item.id === sourceId)?.text || 'item'}.`);
    }
  };
  const chooseSort = (next: ItemSort) => {
    setSort(next);
    try { localStorage.setItem(sortKey, next); } catch { /* The current session still remembers the sort. */ }
  };
  const drag = useItemDrag<string>(({ itemIds, id, token }, destination) => {
    void saveReorder(itemIds, id, destination.id, destination.after, token);
  });
  const endMode = () => {
    drag.cancel();
    setMode(null);
    setSelection(new Set());
    requestAnimationFrame(() => (editorRef.current?.querySelector<HTMLElement>('[aria-label="List actions"]') || addRef.current)?.focus({ preventScroll: true }));
  };
  const startSelection = () => {
    setActionMessage('');
    setMode('select');
    requestAnimationFrame(() => editorRef.current?.querySelector<HTMLButtonElement>('[data-finish-mode]')?.focus({ preventScroll: true }));
  };
  const startReorder = (groupsOnly = false) => {
    setActionMessage('');
    setMode('reorder');
    if (groupsOnly) {
      clearError();
      setGroupOrderOpen(true);
      return;
    }
    requestAnimationFrame(() => editorRef.current?.querySelector<HTMLButtonElement>('[data-finish-mode]')?.focus({ preventScroll: true }));
  };
  const toggleSelection = (id: string) => setSelection(previous => {
    const next = new Set(previous);
    if (next.has(id)) next.delete(id); else next.add(id);
    return next;
  });
  const saveBulk = async (fields: Payload) => {
    if (!selectedCount || busy) return;
    if (await mutate('item.bulkAdd', { listId: list.id, itemIds: selectedItems.map(item => item.id), ...fields })) {
      setBulk(null);
      endMode();
      setActionMessage(`Applied changes to ${selectedCount} ${selectedCount === 1 ? 'item' : 'items'}.`);
    }
  };
  const row = (item: Item, subgroup: string, bucket: string, itemIds: string[], index: number) => {
    const memberships = subgroup ? itemGroupIds(item, groupBy) : [];
    const appearance = subgroup === memberships[0] ? '' : subgroup;
    const motion = { 'data-motion-item': item.id, 'data-motion-row': JSON.stringify([item.id, appearance]) };
    const ordinal = ordinals?.get(item.id);
    const personIds = itemRecipientIds(item);
    const names = personIds.filter(id => id !== activePerson).map(id => recipients.get(id)?.name).filter(Boolean);
    const personSummary = groupBy !== 'person' && names.length ? `${activePerson && personIds.includes(activePerson) ? 'Also for' : 'For'} ${names.join(', ')}` : '';
    const metadata = [item.quantity, personSummary, groupBy !== 'place' && placeSummary(item, places, activeFilters.place)].filter(Boolean).join(' · ');
    const otherGroups = memberships.filter(id => id !== subgroup).map(groupLabel).filter(Boolean);
    const alias = otherGroups.length > 0 && <span className="item-metadata item-alias">
      <Link2 size={15} aria-hidden="true" />
      <span>Also under {otherGroups[0]}{otherGroups.length > 1 && <>
        <span aria-hidden="true"> +{otherGroups.length - 1}</span>
        <span className="visually-hidden">{`, ${otherGroups.slice(1).join(', ')}`}</span>
      </>}</span>
    </span>;
    const selected = mode === 'select' && selection.has(item.id);
    const preview = drag.preview?.source.group === bucket ? drag.preview : null;
    const dropTarget = preview?.destination?.id === item.id ? preview.destination : null;
    const rowClass = [
      'item-row', isChecklist && item.checked && 'completed', selected && 'item-selected',
      preview?.source.id === item.id && 'item-lifted', dropTarget && (dropTarget.after ? 'drop-after' : 'drop-before'),
    ].filter(Boolean).join(' ');
    const contents = <><span className="item-text">{item.text}</span>{showDetails && metadata && <span className="item-metadata">{metadata}</span>}{alias}</>;
    if (mode === 'select') return <label {...motion} className={`${rowClass} item-selection`} key={item.id}>
      <span className="check-target">
        <input type="checkbox" aria-label={`Select ${item.text}${subgroup ? ` in ${groupLabel(subgroup)}` : ''}`} checked={selected} onChange={() => toggleSelection(item.id)} />
        <span className="checkbox-visual" aria-hidden="true">{selected && <Check size={15} />}</span>
      </span>
      {ordinal != null && <span className="item-ordinal" aria-hidden="true">{ordinal.toLocaleString()}.</span>}
      <span className="item-content">{contents}</span>
    </label>;
    if (mode === 'reorder') return <div {...motion} className={rowClass} key={item.id} data-reorder-group={bucket} data-reorder-item={item.id}>
      <span className={`item-grip${itemIds.length < 2 ? ' item-grip-inactive' : ''}`} aria-hidden="true"
        onPointerDown={event => {
          if (!busy && list.orderToken) drag.start(event, { group: bucket, id: item.id, itemIds, token: list.orderToken });
        }}
        onPointerMove={drag.move} onPointerUp={drag.finish} onPointerCancel={drag.cancel} onLostPointerCapture={drag.cancel}>
        {itemIds.length > 1 && <GripVertical size={20} />}
      </span>
      {ordinal != null && <span className="item-ordinal" aria-hidden="true">{ordinal.toLocaleString()}.</span>}
      <div className="item-content">{contents}</div>
      {itemIds.length > 1 && <ActionMenu label={`Reorder ${item.text}${subgroup ? ` in ${groupLabel(subgroup)}` : ''}`}>
        <button disabled={busy || index <= 0} onClick={() => void saveReorder(itemIds, item.id, itemIds[index - 1], false)}><ArrowUp size={16} aria-hidden="true" />Move up</button>
        <button disabled={busy || index >= itemIds.length - 1} onClick={() => void saveReorder(itemIds, item.id, itemIds[index + 1], true)}><ArrowDown size={16} aria-hidden="true" />Move down</button>
      </ActionMenu>}
    </div>;
    return <div {...motion} className={rowClass} key={item.id}>
      {isChecklist ? <label className="check-target">
        <input type="checkbox" checked={item.checked} disabled={busy} aria-label={`${item.checked ? 'Uncheck' : 'Complete'} ${item.text}`} onChange={() => void mutate('item.update', { listId: list.id, itemId: item.id, checked: !item.checked })} />
        <span className="checkbox-visual" aria-hidden="true">{item.checked && <Check size={15} />}</span>
      </label> : !ordinal && <span className="note-bullet" aria-hidden="true">•</span>}
      {ordinal != null && <span className="item-ordinal" aria-hidden="true">{ordinal.toLocaleString()}.</span>}
      <div className="item-main">
        <button className="item-content" data-item-control="" data-item-id={item.id}
          aria-label={ordinal == null ? undefined : `${ordinal.toLocaleString()}. ${item.text}${showDetails && metadata ? `. ${metadata}` : ''}${otherGroups.length ? `. Also under ${otherGroups.join(', ')}` : ''}`}
          onClick={() => openItem(item)}>
          {contents}
        </button>
        {showDetails && (item.description?.length ? <RichDescription blocks={item.description}
          navigation={{ listIds, openList: openLinkedList }} busy={busy || !richDescriptions} toggle={(index, checked) => {
            const description = item.description?.map((block, position) => position === index && block.type === 'checkbox' ? { ...block, checked } : block);
            if (description) void mutate('item.updateDescription', {
              listId: list.id, itemId: item.id, description, expectedDescription: item.description,
            });
          }} /> : item.note && <DescriptionPreview text={item.note} className="item-description" />)}
        {showDetails && list.kind === 'checklist' && Boolean(item.subitems?.length) && <SubchecklistPreview itemText={item.text}
          value={item.subitems!} busy={busy || !subchecklists || !isChecklist}
          expandedBySearch={Boolean(filterQuery && item.subitems?.some(step => step.text.toLocaleLowerCase().includes(filterQuery)))}
          toggle={step => void mutate('subitem.update', { listId: list.id, itemId: item.id, subitemId: step.id, checked: !step.checked })} />}
      </div>
      <button className="icon-button item-menu" aria-label={`Edit ${item.text}`} onClick={() => openItem(item)}><ChevronRight size={18} aria-hidden="true" /></button>
    </div>;
  };

  const renderGroupRows = (items: Item[], sectionId: string | null, completed = false) => {
    const Heading = list.sections.length ? 'h3' : 'h2';
    return splitItemGroups(items, groupBy).sort((left, right) =>
      (groupPositions.get(left.id) ?? 0) - (groupPositions.get(right.id) ?? 0)
    ).map(group => {
      const itemIds = mode === 'reorder' ? group.items.map(item => item.id) : [];
      const bucket = JSON.stringify([sectionId, completed, group.id]);
      const rows = group.items.map((item, index) => row(item, group.id, bucket, itemIds, index));
      return group.id ? <section className="item-subgroup" key={group.id}>
        <Heading data-motion-row={bucket} data-jump-group={group.id} tabIndex={-1}>{groupLabel(group.id)}</Heading>{rows}
      </section> : rows;
    });
  };

  const checked = isChecklist && !list.numbered && !activeFilters.notDone ? groups.flatMap(group => group.items).filter(item => item.checked && isVisible(item)) : [];
  const showDone = doneOpen ?? filtered;
  const orderedSections = list.sections.slice().sort(byPosition);
  const showCheckedSections = Boolean(mode === 'reorder' || groupBy !== 'none');
  const canReorderItems = Boolean(list.orderToken && list.items.length > 1);
  const canReorderGroups = groupBy !== 'none' && (orderedGroupIds.length > 1 || storedGroupOrder.length > 0);
  const contextGroups = new Map<string, number>();
  if (groupBy !== 'none') for (const item of list.items) {
    if (!isVisible(item) || (isChecklist && !list.numbered && item.checked && !showDone)) continue;
    for (const id of itemGroupIds(item, groupBy)) contextGroups.set(id, (contextGroups.get(id) ?? 0) + 1);
  }
  const contextSections = groupBy === 'none' ? groups.filter(group => group.title
    && (group.id !== null && !filtered || group.items.some(item => isVisible(item) && (!isChecklist || list.numbered || !item.checked)))) : [];
  const assignments = useMemo(() => filterOptions(list), [list]);
  const jumpTo = (attribute: 'data-jump-group' | 'data-jump-section', id: string) => {
    const heading = editorRef.current?.querySelector<HTMLElement>(`[${attribute}="${CSS.escape(id)}"]`);
    heading?.focus({ preventScroll: true });
    heading?.scrollIntoView({ block: 'start', behavior: matchMedia('(prefers-reduced-motion: reduce)').matches ? 'auto' : 'smooth' });
  };
  const listSummary = <>
    {list.description && <DescriptionPreview text={list.description} lines={3} />}
    <div className="list-hero-meta">
      <div className="editor-badges">{category && <span className="badge category-badge"><span className={`dot ${category.color}`} />{category.name}</span>}{badges}</div>
      <span className="list-access-label">{list.shared ? list.role === 'editor' ? `Owned by ${list.ownerUsername}` : 'You own this list' : 'Private · Only you have access'}</span>
    </div>
    {list.items.length > 0 && (isChecklist ? <div className="list-hero-progress">
      <span><strong>{done} of {list.items.length}</strong> complete</span>
      <div className="progress-track" aria-hidden="true"><div style={{ width: `${done / list.items.length * 100}%` }} /></div>
    </div> : <div className="list-hero-count">{list.items.length} {list.items.length === 1 ? 'item' : 'items'}</div>)}
  </>;
  return <div className={`editor list-editor-layout${mode ? ' editor-mode-active' : ''}`} ref={editorRef} onKeyDown={event => {
    if (event.key === 'Escape' && drag.preview) { event.stopPropagation(); drag.cancel(); }
  }}>
    <div className="editor-pane">
      <div className="list-topbar" ref={toolbarRef}>
        <div className="list-toolbar-content">
          <button className="icon-button list-back" aria-label="Back to collection" title="Back to collection" onClick={back}><ArrowLeft size={20} aria-hidden="true" /></button>
          <span className="list-compact-title" aria-hidden="true" title={list.title}>{list.title}</span>
          {!mode && (list.items.length > 0 || query) && <>
            <button className={`icon-button list-search-toggle${query || searchOpen ? ' is-active' : ''}`} ref={searchButtonRef}
              aria-label="Search items" title="Search items" aria-expanded={searchOpen} aria-controls={searchFieldId} onClick={toggleSearch}><Search size={20} aria-hidden="true" /></button>
            <button className="list-view-toggle" onClick={() => openOverlay('filters')}><SlidersHorizontal size={18} aria-hidden="true" />View</button>
          </>}
        <ActionMenu label="List actions">
          <button onClick={edit}>Edit details</button>
          {uploadCover && <button disabled={busy || coverBusy} onClick={() => coverFileRef.current?.click()}>Change cover…</button>}
          <button aria-pressed={list.favorite} disabled={busy} onClick={() => void mutate('list.update', { listId: list.id, favorite: !list.favorite })}>{list.favorite ? 'Unfavorite list' : 'Favorite list'}</button>
          {!mode && organization && list.items.length > 0 && <button disabled={busy} onClick={startSelection}>Select items</button>}
          {!mode && list.items.length > 0 && <button onClick={() => openOverlay('filters')}>Filter this list</button>}
          {!mode && (canReorderItems || canReorderGroups) && <button disabled={busy} onClick={() => startReorder(!canReorderItems)}>{canReorderItems ? 'Reorder items' : 'Reorder groups'}</button>}
          {organization && list.role !== 'editor' && <button aria-pressed={Boolean(list.numbered)} aria-description={list.shared ? 'Everyone who can see this list sees item numbers.' : undefined} disabled={busy} onClick={() => void mutate('list.update', { listId: list.id, numbered: !list.numbered, ...orderGuard(list, true) })}>{list.numbered && <Check size={16} aria-hidden="true" />}Number items</button>}
          {detailsExist && <button onClick={() => setShowDetails(!showDetails)}>{showDetails ? 'Hide item details' : 'Show item details'}</button>}
          <button onClick={() => section()}>Add section</button>
          {list.places?.length ? <button onClick={() => openOverlay('place')}>Manage places</button> : null}
          {list.recipients?.length ? <button onClick={() => openOverlay('recipient')}>Manage people</button> : null}
          <button onClick={share}>{list.role === 'editor' ? 'People & access' : 'Share list'}</button>
          <button onClick={clone}>{list.isTemplate ? 'Start list' : 'Make a private copy'}</button>
          {!list.isTemplate && list.role !== 'editor' && <button disabled={busy} onClick={() => void mutate('list.update', { listId: list.id, isTemplate: true })}>Make a template</button>}
          {isChecklist && !list.archived && (done > 0 || doneSteps) && <button onClick={() => listConfirm('reset')}>Start again</button>}
          {canDeleteDone && isChecklist && !list.archived && done > 0 && <button className="danger-text" disabled={busy} onClick={() => listConfirm('deleteDone')}>Delete done</button>}
          {list.archived ? <button disabled={busy} onClick={() => void mutate('list.update', { listId: list.id, archived: false })}>Restore for me</button> : <button onClick={() => listConfirm('archive')}>Archive for me</button>}
          {list.role !== 'editor' && <button className="danger-text" onClick={() => listConfirm('delete')}>Delete list</button>}
          <button className="list-mobile-setting" onClick={openAppearance}>Appearance</button>
          <button className="list-mobile-setting" onClick={openSettings}>Settings & backup</button>
        </ActionMenu>
        </div>
        {!mode && searchOpen && <div className="list-search-panel" id={searchFieldId}>
          <label className="item-search"><Search size={18} aria-hidden="true" /><input ref={searchRef} aria-label="Filter items" placeholder="Filter items" value={query} maxLength={500}
            onChange={event => { setQuery(event.target.value); setDoneOpen(null); }}
            onKeyDown={event => { if (event.key === 'Escape') { event.stopPropagation(); closeSearch(); } }} /></label>
          {query && <button className="icon-button" aria-label="Clear item search" onClick={() => { setQuery(''); searchRef.current?.focus({ preventScroll: true }); }}><X size={18} aria-hidden="true" /></button>}
        </div>}
        <div className={coverBusy || coverNotice ? 'list-cover-status' : 'visually-hidden'} role="status">{coverBusy ? 'Preparing and saving cover…' : coverNotice}</div>
        {coverError && <div className="list-cover-status list-cover-error" role="alert">{error || coverError}<button className="icon-button" aria-label="Dismiss cover error" onClick={() => { setCoverError(''); clearError(); }}><X size={17} aria-hidden="true" /></button></div>}
        {error && !coverBusy && !coverError && !editing && !bulk && !overlay && !groupOrderOpen && <div className="list-cover-status list-cover-error" role="alert">
          {error}<button className="icon-button" aria-label="Dismiss error" onClick={clearError}><X size={17} aria-hidden="true" /></button>
        </div>}
      </div>
      {uploadCover && <input type="file" ref={coverFileRef} accept="image/*" hidden aria-label="Choose a list cover photo" onChange={event => {
        const file = event.currentTarget.files?.[0];
        event.currentTarget.value = '';
        if (file) void changeCover(file);
      }} />}
      <header className="list-hero" ref={heroRef}>
        <div className="list-hero-art" aria-hidden="true">
          {coverSrc && <img key={coverSrc} className="list-cover-photo" alt="" src={coverSrc} onError={() => setBrokenCoverId(list.coverId)} />}
        </div>
        <div className="list-hero-body">
          <div className="list-hero-main">
            <div className="list-hero-name">
              <span className={`list-hero-icon ${list.color}`}>{icon}</span>
              <h1 ref={titleRef}>{list.title}</h1>
            </div>
            <div className="list-hero-actions">
              <button className={`icon-button favorite ${list.favorite ? 'is-favorite' : ''}`} aria-label={list.favorite ? 'Unfavorite list' : 'Favorite list'} aria-pressed={list.favorite} disabled={busy} onClick={() => void mutate('list.update', { listId: list.id, favorite: !list.favorite })}><Star size={21} fill={list.favorite ? 'currentColor' : 'none'} /></button>
              <button className="icon-button" aria-label={list.role === 'editor' ? 'People & access' : 'Share'} title={list.role === 'editor' ? 'People & access' : 'Share'} disabled={busy} onClick={share}><Users size={20} aria-hidden="true" /></button>
            </div>
          </div>
          <div className="list-hero-summary">{listSummary}</div>
        </div>
      </header>
      {list.isTemplate && <div className="template-banner"><span>This is a reusable template. Start a list to create a private copy.</span><button className="primary" disabled={busy} onClick={clone}><Copy size={17} />Start list</button></div>}
    {(filtered || groupBy !== 'none') && <div className="active-list-filters">
      {mode ? <span>{groupBy !== 'none' ? `Grouped by ${groupBy} · ` : ''}{filtered ? summary : `${shown} ${shown === 1 ? 'item' : 'items'}`}</span> :
        <button className="text-button" onClick={() => filtered && !usingFilters && groupBy === 'none' ? toggleSearch() : openOverlay('filters')}><span>{groupBy !== 'none' ? `Grouped by ${groupBy} · ` : ''}{filtered ? summary : `${shown} ${shown === 1 ? 'item' : 'items'}`}</span><ChevronDown size={16} aria-hidden="true" /></button>}
      {!mode && filtered && <button className="text-button" onClick={clearFilters}>Clear</button>}
    </div>}
    {mode === 'select' && <div className="list-mode-bar" aria-label="Item selection">
      <strong className="mode-title" aria-live="polite"><SquareCheck size={17} aria-hidden="true" />{selectedCount} selected</strong>
      <div className="list-mode-actions">
        <button className="text-button" disabled={!selectedCount || busy} onClick={() => { clearError(); setBulk('place'); }}><MapPin size={17} aria-hidden="true" />Add places</button>
        <button className="text-button" disabled={!selectedCount || busy} onClick={() => { clearError(); setBulk('person'); }}><UserRoundPlus size={17} aria-hidden="true" />Add people</button>
        <button className="primary" data-finish-mode="" disabled={busy} onClick={endMode}>Done</button>
      </div>
    </div>}
    {mode === 'reorder' && <>
      <div className="list-mode-bar" aria-label="Reorder items">
        <strong className="mode-title">Manual order</strong>
        <div className="list-mode-actions">
          {canReorderGroups && <button className="secondary" disabled={busy} aria-haspopup="dialog" onClick={() => {
            clearError(); setGroupOrderOpen(true);
          }}><ListOrdered size={17} aria-hidden="true" />Reorder groups</button>}
          <button className="primary" data-finish-mode="" disabled={busy} onClick={endMode}>Done</button>
        </div>
      </div>
      {canReorderItems && <p className="list-mode-help">Drag a handle or use the row menu to move an item {groupBy === 'none' ? 'within its section' : 'within its group'}.</p>}
    </>}
    <div className={actionMessage ? 'filter-message' : 'visually-hidden'} role="status">{actionMessage}</div>
    {list.numbered && filtered && shown > 0 && shown < list.items.length && <p className="small-muted numbered-filter-help">Numbers follow the full list order.</p>}
    {filterMessage && <p className="filter-message" role="status">{filterMessage}</p>}
    {hiddenItemId && <div className="filtered-save" role="status">Item saved. It isn't shown with these filters. <button className="text-button" onClick={() => viewItem(hiddenItemId)}>View item</button></div>}
    <AnimatedChecklist items={list.items} count={list.items.length} view={JSON.stringify([displayedSort, groupBy, filters, query, showDone, storedGroupOrder])}>
      {!list.items.length && !filtered && <p className="editor-empty">Add your first item.</p>}
      {groups.map((group, index) => {
        const visible = group.items.filter(item => isVisible(item) && (!isChecklist || list.numbered || !item.checked));
        if (!visible.length && (filtered || group.id === null)) return null;
        const currentSection = orderedSections[index - 1];
        return <section className="item-group" key={group.id || 'none'}>
          {group.title && <div className="section-heading"><h2 data-jump-section={group.id ?? ''} tabIndex={-1}>{group.title}</h2><span className="small-muted">{visible.length}</span>{currentSection && !mode && <ActionMenu label={`${group.title} section actions`}>
            <button onClick={() => section(currentSection)}>Rename section</button>
            {filtered ? <button onClick={clearFilters}>Edit order</button> : <>
              <button disabled={busy || index <= 1} onClick={() => void mutate('section.move', { listId: list.id, sectionId: group.id, index: index - 2, ...orderGuard(list) })}>Move section up</button>
              <button disabled={busy || index >= orderedSections.length} onClick={() => void mutate('section.move', { listId: list.id, sectionId: group.id, index, ...orderGuard(list) })}>Move section down</button>
            </>}
            <button className="danger-text" disabled={busy} onClick={() => removeSection(currentSection)}>Delete section</button>
          </ActionMenu>}</div>}
          {renderGroupRows(visible, group.id)}
        </section>;
      })}
      {filtered && shown === 0 && <div className="filter-empty"><p>No items match these filters.</p>{!mode && <button className="text-button" onClick={clearFilters}>Clear filters</button>}</div>}
      {checked.length > 0 && <div className="done-area">
        <button className="done-heading" aria-expanded={showDone} onClick={() => setDoneOpen(!showDone)}><ChevronDown className={showDone ? 'rotated' : ''} size={20} /><span>Done</span><span className="badge">{checked.length}</span><span className="small-muted">{showDone ? 'Hide completed' : 'Show completed'}</span></button>
        {showDone && (showCheckedSections ? groups.map(group => {
          const visible = group.items.filter(item => item.checked && isVisible(item));
          return visible.length > 0 && <section className="item-group" key={group.id || 'none'}>
            {group.title && <div className="section-heading"><h2>{group.title}</h2></div>}
            {renderGroupRows(visible, group.id, true)}
          </section>;
        }) : checked.map((item, index) => row(item, '', '', [], index)))}
      </div>}
    </AnimatedChecklist>
    {!mode && <div className="quick-add-wrap"><form className="quick-add" onSubmit={submit}>
      <div className="quick-add-top"><Plus size={21} aria-hidden="true" /><input ref={addRef} aria-label="Add item" placeholder="Add item…" autoCapitalize="sentences" enterKeyHint="done" maxLength={500} aria-busy={busy} value={draft} onChange={event => { if (busy) return; setDraft(event.target.value); setAddError(''); }} /><button className="primary" onMouseDown={event => event.preventDefault()} disabled={busy} type="submit">{busy ? 'Saving…' : 'Add'}</button></div>
      {(orderedSections.length > 0 || addSection) && <label className="add-section-select"><span>Add to</span><select aria-label="Add item to section" value={addSection} onChange={event => { setAddSection(event.target.value); setAddError(''); }}><option value="">Unsectioned</option>{addSection && !selectedSection && <option value={addSection} disabled>Section removed</option>}{orderedSections.map(section => <option key={section.id} value={section.id}>{section.title}</option>)}</select></label>}
      {defaultLabels.length > 0 && <div className="quick-add-context"><span>{defaultLabels.join(' · ')}</span><button type="button" className="text-button" disabled={busy} onClick={() => {
        clearError(); setEditing({ item: makeItemDraft({ text: draft, sectionId: selectedSection, ...defaults }), fromQuickAdd: true });
      }}>Edit details</button></div>}
      {addError && <span className="field-error" role="alert">{addError}</span>}
    </form></div>}
    </div>
    <aside className="list-context-rail" aria-label="List overview">
      <section className="list-rail-summary">
        <h2>List details</h2>
        {listSummary}
        {!list.items.length && <span className="list-hero-count">0 items</span>}
      </section>
      {contextGroups.size > 0 ? <>
        <h2>Jump to {groupBy === 'place' ? 'place' : 'person'}</h2>
        <ul>{orderedGroupIds.filter(id => contextGroups.has(id)).map(id => <li key={id}>
          <button type="button" className="list-rail-link" onClick={() => jumpTo('data-jump-group', id)}>{groupLabel(id)}<span>{contextGroups.get(id)}</span></button>
        </li>)}</ul>
      </> : contextSections.length > 0 ? <>
        <h2>Jump to section</h2>
        <ul>{contextSections.map(group => <li key={group.id ?? ''}>
          <button type="button" className="list-rail-link" onClick={() => jumpTo('data-jump-section', group.id ?? '')}>{group.title}</button>
        </li>)}</ul>
      </> : !mode && <>
        {assignments.places.size > 0 && <>
          <h2>Places</h2>
          <ul>{(list.places || []).filter(place => assignments.places.has(place.id)).map(place => <li key={place.id}>
            <button type="button" className="list-rail-link" aria-pressed={activePlace === place.id} onClick={() => {
              setFilters({ ...activeFilters, place: activePlace === place.id ? 'all' : placeFilter(place.id) });
              setDoneOpen(null);
            }}>{placeLabel(place)}</button>
          </li>)}</ul>
        </>}
        {assignments.people.size > 0 && <>
          <h2>Recipients</h2>
          <ul>{(list.recipients || []).filter(person => assignments.people.has(person.id)).map(person => <li key={person.id}>
            <button type="button" className="list-rail-link" aria-pressed={activePerson === person.id} onClick={() => {
              setFilters({ ...activeFilters, person: activePerson === person.id ? 'all' : personFilter(person.id) });
              setDoneOpen(null);
            }}>{person.name}</button>
          </li>)}</ul>
        </>}
      </>}
    </aside>
    {overlay === 'filters' && <ViewDialog list={list} filters={activeFilters} query={filterQuery} sort={sort} groupBy={groupBy} close={() => setOverlay(null)} apply={(next, nextSort, nextGroup) => {
      setFilters(next); if (nextSort !== sort) chooseSort(nextSort); setGroupBy(nextGroup); setOverlay(null); setFilterMessage(''); setHiddenItemId(null); setDoneOpen(null);
    }} />}
    {mode === 'reorder' && groupOrderOpen && groupBy !== 'none' && <GroupOrderDialog
      groups={orderedGroupIds.map(id => ({ id, label: groupLabel(id) }))}
      preferred={storedGroupOrder} busy={busy} error={error}
      close={() => { clearError(); setGroupOrderOpen(false); }}
      save={async (groupIds, expectedGroupIds) => Boolean(await mutate('group.reorder', { listId: list.id, kind: groupBy, groupIds, expectedGroupIds }))}
    />}
    {bulk && <Dialog title={bulk === 'place' ? 'Add places' : 'Add people'} busy={busy} close={() => { clearError(); setBulk(null); }}>
      {error && <div className="dialog-error" role="alert">{error}</div>}
      {bulk === 'place' ? <PlacesPicker
        initial={{ availability: noAvailability, newPlaces: [] }}
        entries={list.places || []}
        bulk={{ count: selectedCount, anywhere: selectedAnywhere.length }}
        back={() => { clearError(); setBulk(null); }}
        done={({ availability, newPlaces }) => void saveBulk({ placeIds: availability.placeIds, newPlaces, expectedAnywhereItemIds: selectedAnywhere })}
      /> : <PersonPicker people={list.recipients || []} bulkCount={selectedCount} back={() => { clearError(); setBulk(null); }}
        done={({ ids, newPeople }) => void saveBulk({ recipientIds: ids, newRecipients: newPeople })}
      />}
    </Dialog>}
    {(overlay === 'place' || overlay === 'recipient') && <DictionaryDialog list={list} kind={overlay} busy={busy} error={error} clearError={clearError} mutate={mutate} close={() => { clearError(); setOverlay(null); }} />}
    {editing && <ItemEditor list={list} lists={lists} initial={editing.item} organization={organization} richDescriptions={richDescriptions}
      subchecklists={subchecklists && list.kind === 'checklist'} filtered={filtered} busy={busy} error={error} clearError={clearError} mutate={mutate} saved={editingSaved} close={() => { clearError(); closeEditing(); }} useManualOrder={() => chooseSort('manual')} showFullOrder={() => {
      clearFilters();
      chooseSort('manual');
      if (editing.item.checked) setDoneOpen(true);
      if (editing.item.id) { reviewOrderId.current = editing.item.id; focusRow(editing.item.id, false); }
    }} />}
  </div>;
}
