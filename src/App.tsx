import { passwordHint, validNewPassword } from './passwordPolicy';
import { useCallback, useEffect, useMemo, useRef, useState, type FormEvent } from 'react';
import { Archive, ArrowDown, ArrowLeft, ArrowUp, BookOpen, Check, ChevronDown, Copy, Gift, Home, Leaf, Lightbulb, List, Luggage, MoreHorizontal, Plus, Search, Palette, Settings, ShoppingBasket, Sparkles, Star, Tent, Trash2, Users, X } from 'lucide-react';
import Fuse from 'fuse.js';
import AppearancePicker from './AppearancePicker';
import { InstallBanner, InstallSettings } from './InstallGather';
import { useAuth } from './auth';
import { ApiError, getWorkspace, restoreWorkspace, runAction, validateBackup, searchLists, getSearchStatus } from './api';
import { byPosition } from './itemOrder';
import { orderGuard } from './organization';
import Dialog from './Dialog';
import ListEditor from './ListEditor';
import type { ListView } from './ListEditor';
import { SharingPanel, InvitationPanel } from './Sharing';
import { clearInvitation, pendingInvitation } from './invitations';
import type { Category, Color, GatherList, ListKind, Mutation, RepeatCadence, Payload, Section, Workspace, SearchResult, SearchResponse, SearchStatus } from './types';

type Destination = 'library' | 'favorites' | 'templates' | 'archive';
type Route = { destination: Destination; categoryId: string | null; query: string; searchAll: boolean; selectedId: string | null };
function readRoute(): Route {
  const params = new URLSearchParams(window.location.search);
  const view = params.get('view');
  return { destination: view === 'favorites' || view === 'templates' || view === 'archive' ? view : 'library', categoryId: params.get('category') || null, query: (params.get('q') || '').slice(0, 500), searchAll: params.get('scope') === 'all', selectedId: params.get('list') || null };
}
function writeRoute(route: Route, mode: 'push' | 'replace' = 'replace', state: unknown = window.history.state) {
  const url = new URL(window.location.href);
  for (const key of ['view', 'category', 'q', 'scope', 'list']) url.searchParams.delete(key);
  if (route.destination !== 'library') url.searchParams.set('view', route.destination);
  if (route.categoryId) url.searchParams.set('category', route.categoryId);
  if (route.query) url.searchParams.set('q', route.query);
  if (route.searchAll) url.searchParams.set('scope', 'all');
  if (route.selectedId) url.searchParams.set('list', route.selectedId);
  window.history[mode === 'push' ? 'pushState' : 'replaceState'](state, '', url.pathname + url.search + url.hash);
}

type Modal = { kind: 'list'; list?: GatherList } | { kind: 'sharing'; list: GatherList } | { kind: 'section'; list: GatherList; section?: Section } | { kind: 'clone'; list: GatherList } | { kind: 'confirm'; title: string; message: string; action: string; payload: Payload; label: string } | { kind: 'settings' | 'categories' | 'more' | 'appearance' } | { kind: 'restore'; document: Workspace; filename: string };
const colors: Color[] = ['sage', 'peach', 'lavender', 'sky', 'sand', 'rose'];
const iconNames = ['list', 'basket', 'gift', 'lightbulb', 'luggage', 'tent', 'home', 'sparkles'];
const destinationLabels: Record<Destination, string> = { library: 'Your library', favorites: 'Your favorites', templates: 'Reusable templates', archive: 'The archive' };

const matchLabels: Record<string, string> = { title: 'Title', description: 'Description', category: 'Category', section: 'Section', item: 'Item', note: 'Item description', quantity: 'Quantity' };
function localSearch(lists: GatherList[], categories: Category[], query: string): SearchResult[] {
  const chunks = lists.flatMap(list => [{ listId: list.id, text: list.title, field: 'title' }, { listId: list.id, text: list.description, field: 'description' }, { listId: list.id, text: categories.find(c => c.id === list.categoryId)?.name || '', field: 'category' }, ...list.sections.map(section => ({ listId: list.id, text: section.title, field: 'section', sectionId: section.id })), ...list.items.flatMap(item => ['item', 'note', 'quantity'].map(field => ({ listId: list.id, text: field === 'item' ? item.text : field === 'note' ? item.note : item.quantity, field, itemId: item.id })))]).filter(c => c.text);
  const exact = chunks.filter(c => c.text.toLocaleLowerCase().includes(query.toLocaleLowerCase())).map(c => ({ listId: c.listId, score: c.field === 'title' ? 100 : 90, match: c, matchType: 'exact' as const }));
  const fuzzy = new Fuse(chunks, { keys: ['text'], threshold: .4, ignoreLocation: true, includeScore: true }).search(query).map(hit => ({ listId: hit.item.listId, score: 60 * (1 - (hit.score || 0)), match: hit.item, matchType: 'fuzzy' as const }));
  const seen = new Set<string>(); return [...exact, ...fuzzy].sort((a, b) => b.score - a.score).filter(hit => { if (seen.has(hit.listId)) return false; seen.add(hit.listId); return true; }).slice(0, 125);
}
function ListIcon({ name, size = 24 }: { name: string; size?: number }) {
  const icons: Record<string, typeof List> = { list: List, basket: ShoppingBasket, gift: Gift, lightbulb: Lightbulb, luggage: Luggage, tent: Tent, home: Home, sparkles: Sparkles };
  const Icon = icons[name] || List; return <Icon size={size} strokeWidth={1.6} aria-hidden="true" />;
}
function Badges({ list }: { list: GatherList }) { return <>{list.shared && <span className="badge shared-badge"><Users size={12} aria-hidden="true" />Shared</span>}{list.archived && <span className="badge">Archived</span>}{list.kind === 'notes' && <span className="badge">Notes</span>}{list.kind !== 'notes' && list.repeatCadence !== 'manual' && <span className="badge">{list.repeatCadence === 'daily' ? 'Daily' : 'Weekly'}</span>}{list.isTemplate && <span className="badge master"><Copy size={12} /> Template master</span>}{list.isSample && <span className="badge">Sample</span>}</>; }

export default function App() {
  const auth = useAuth();
  const [workspace, setWorkspace] = useState<Workspace | null>(null);
  const [loadError, setLoadError] = useState('');
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [busy, setBusy] = useState(false);
  const busyRef = useRef(false);
  const sequence = useRef(0);
  const [destination, setDestination] = useState<Destination>(() => readRoute().destination);
  const [categoryId, setCategoryId] = useState<string | null>(() => readRoute().categoryId);
  const [query, setQuery] = useState(() => readRoute().query);
  const [searchAll, setSearchAll] = useState(() => readRoute().searchAll);
  const [ranked, setRanked] = useState<{ key: string; response: SearchResponse } | null>(null);
  const [searchBusy, setSearchBusy] = useState(false);
  const [searchFailed, setSearchFailed] = useState(false);
  const [searchRetry, setSearchRetry] = useState(0);
  const [selectedId, setSelectedId] = useState<string | null>(() => readRoute().selectedId);
  const listViews = useRef(new Map<string, ListView>());
  const [modal, setModal] = useState<Modal | null>(null);
  const [invitation, setInvitation] = useState(pendingInvitation);
  const [sharingBusy, setSharingBusy] = useState(false);
  useEffect(() => { const capture = () => { const token = pendingInvitation(); if (token) { setInvitation(token); setModal(null); } }; window.addEventListener('hashchange', capture); return () => window.removeEventListener('hashchange', capture); }, []);
  useEffect(() => {
    const resumeSearch = () => setSearchRetry(n => n + 1);
    document.addEventListener('visibilitychange', resumeSearch);
    return () => document.removeEventListener('visibilitychange', resumeSearch);
  }, []);
  useEffect(() => {
    const onPopState = () => {
      const route = readRoute();
      setDestination(route.destination); setCategoryId(route.categoryId); setQuery(route.query);
      setSearchAll(route.searchAll); setSelectedId(route.selectedId); setModal(null);
    };
    window.addEventListener('popstate', onPopState);
    return () => window.removeEventListener('popstate', onPopState);
  }, []);
  useEffect(() => { writeRoute({ destination, categoryId, query, searchAll, selectedId }); }, [destination, categoryId, query, searchAll, selectedId]);
  useEffect(() => { if (!selectedId && window.history.state?.gatherEditorEntry) window.history.replaceState({ gatherEditorEntry: false }, '', window.location.href); }, [selectedId]);
  useEffect(() => {
    if (!workspace) return;
    if (categoryId && !workspace.categories.some(c => c.id === categoryId)) setCategoryId(null);
    if (selectedId && !workspace.lists.some(l => l.id === selectedId)) { setSelectedId(null); setRanked(null); setNotice('This list is no longer available to your account.'); }
    if (modal && (('list' in modal && modal.list && !workspace.lists.some(l => l.id === modal.list!.id)) || (modal.kind === 'confirm' && typeof modal.payload.listId === 'string' && !workspace.lists.some(l => l.id === modal.payload.listId)))) { setModal(null); setSharingBusy(false); }
  }, [workspace, categoryId, selectedId, modal]);
  const load = useCallback(async (initial = false) => {
    if (busyRef.current) return;
    const seq = ++sequence.current;
    try { const next = await getWorkspace(); if (seq === sequence.current && !busyRef.current) { setWorkspace(next); setLoadError(''); } }
    catch (e) { if (seq === sequence.current) { if (initial) setLoadError((e as Error).message); else setError('Could not refresh. Your saved lists are safe; retry when connected.'); } }
  }, []);
  useEffect(() => { void load(true); const refresh = () => { if (!document.hidden) void load(); }; window.addEventListener('focus', refresh); const timer = window.setInterval(refresh, 15000); return () => { ++sequence.current; clearInterval(timer); window.removeEventListener('focus', refresh); }; }, [load]);
  const mutate: Mutation = async (type, payload) => {
    if (busyRef.current) return null;
    if (!navigator.onLine) { setError("You're offline. Reconnect to save changes."); return null; }
    busyRef.current = true; setBusy(true); setError(''); setNotice(''); const seq = ++sequence.current;
    try { const next = await runAction(type, payload); if (seq === sequence.current) { setWorkspace(next); setNotice('Changes saved'); } return next; }
    catch (error) {
      const apiError = error instanceof ApiError ? error : null;
      const uncertain = !apiError || apiError.status >= 500;
      const orderConflict = apiError?.code === 'ORDER_CHANGED' || apiError?.code === 'ORDER_TOKEN_REQUIRED';
      const message = orderConflict ? 'The list order changed. Review the latest order and try again.'
        : uncertain ? 'Could not confirm the save. Check the list before trying again.'
        : apiError.message;
      if (seq === sequence.current) setError(message);
      if (uncertain || apiError.status === 404 || apiError.status === 409) {
        try { const next = await getWorkspace(); if (seq === sequence.current) { setWorkspace(next); setRanked(null); } } catch { /* The original save error remains visible. */ }
      }
      return null;
    }
    finally { busyRef.current = false; setBusy(false); }
  };
  const selected = workspace?.lists.find(l => l.id === selectedId);
  const categories = useMemo(() => workspace?.categories.slice().sort(byPosition) || [], [workspace]);
  const go = (next: Destination) => { writeRoute({ destination: next, categoryId: null, query, searchAll, selectedId: null }, 'push', { gatherEditorEntry: false }); setDestination(next); setCategoryId(null); setSelectedId(null); setModal(null); };
  const backToCollection = () => { if (window.history.state?.gatherEditorEntry) window.history.back(); else { writeRoute({ destination, categoryId, query, searchAll, selectedId: null }, 'push', { gatherEditorEntry: false }); setSelectedId(null); } };
  const confirm = (title: string, message: string, action: string, payload: Payload, label: string) => setModal({ kind: 'confirm', title, message, action, payload, label });
  const open = (id: string) => { writeRoute({ destination, categoryId, query, searchAll, selectedId: id }, 'push', { gatherEditorEntry: true }); setSelectedId(id); setError(''); window.scrollTo({ top: 0 }); };
  const openCreated = (list: GatherList) => {
    const collection = { destination: list.isTemplate ? 'templates' as Destination : destination === 'favorites' && list.favorite ? 'favorites' as Destination : 'library' as Destination, categoryId: list.categoryId, query: '', searchAll: false, selectedId: null };
    writeRoute(collection, 'push', { gatherEditorEntry: false });
    writeRoute({ ...collection, selectedId: list.id }, 'push', { gatherEditorEntry: true });
    setDestination(collection.destination); setCategoryId(collection.categoryId); setQuery(''); setSearchAll(false); setSelectedId(list.id); setError(''); window.scrollTo({ top: 0 });
  };
  const listConfirm = (list: GatherList, action: 'archive' | 'delete' | 'reset') => {
    const config = { archive: ['Archive this list for you?', 'It will move to your Archive. Other people keep seeing it in their own library. All items and progress stay saved.', 'list.update', { listId: list.id, archived: true }, 'Archive for me'], delete: [list.shared ? 'Delete this list for everyone?' : 'Delete this list?', `“${list.title}” and all its items will be permanently deleted.${list.shared ? ' Everyone will lose access.' : ''} Export a backup first if you want to keep a copy.`, 'list.delete', { listId: list.id }, 'Delete list'], reset: ['Start this list again?', `Reset ${list.items.filter(i => i.checked).length} checked items?${list.shared ? ' This resets checks for everyone.' : ''} All item text, notes, quantities and sections stay as they are.`, 'list.reset', { listId: list.id }, 'Start again'] } as const;
    const [title, message, type, payload, label] = config[action]; confirm(title, message, type, payload, label);
  };
  const search = query.trim();
  const searchKey = useMemo(() => JSON.stringify([search, searchAll ? 'all' : destination, categoryId, workspace]), [search, searchAll, destination, categoryId, workspace]);
  useEffect(() => {
    if (!search || !workspace || selectedId || document.hidden) { setSearchBusy(false); setSearchFailed(false); return; }
    let stale = false; let retryTimer: number | undefined; const controller = new AbortController(); setSearchBusy(true); setSearchFailed(false);
    const timer = window.setTimeout(() => { void searchLists(search, searchAll ? 'all' : destination, categoryId, controller.signal).then(response => { if (!stale) { setRanked({ key: searchKey, response }); if (response.status === 'warming') retryTimer = window.setTimeout(() => setSearchRetry(n => n + 1), 3000); } }).catch(() => { if (!stale) { setRanked(null); setSearchFailed(true); } }).finally(() => { if (!stale) setSearchBusy(false); }); }, 500);
    return () => { stale = true; controller.abort(); clearTimeout(timer); clearTimeout(retryTimer); };
  }, [searchKey, searchRetry, selectedId]);
  const scopedLists = useMemo(() => (workspace?.lists || []).filter(l => (search && searchAll ? true : destination === 'archive' ? l.archived : !l.archived && (destination === 'templates' ? l.isTemplate : !l.isTemplate && (destination !== 'favorites' || l.favorite))) && (!categoryId || l.categoryId === categoryId)), [workspace, search, searchAll, destination, categoryId]);
  const localResults = useMemo(() => search ? localSearch(scopedLists, categories, search) : [], [scopedLists, categories, search]);
  let lists = scopedLists;
  let resultMatches = new Map<string, SearchResult>();
  const response = ranked?.key === searchKey ? ranked.response : null;
  if (search) {
    const results = response?.results || localResults;
    resultMatches = new Map(results.map(r => [r.listId, r]));
    const lookup = new Map(lists.map(l => [l.id, l]));
    lists = results.flatMap(r => lookup.get(r.listId) ? [lookup.get(r.listId)!] : []);
  } else lists = lists.slice().sort(byPosition);

  return <div className="app-shell">
    <aside className="sidebar"><a className="brand" href="#" onClick={e => { e.preventDefault(); go('library'); }}><Leaf size={38} strokeWidth={1.3} /><span>Gather</span></a><p className="tagline">Lists for everyday life.</p><p className="account-identity">Signed in as <strong>{auth.session.username}</strong></p><nav aria-label="Collections">{(['library', 'favorites', 'templates', 'archive'] as Destination[]).map(d => <button key={d} className={`nav-link ${destination === d ? 'active' : ''}`} onClick={() => go(d)}>{d === 'library' ? <BookOpen /> : d === 'favorites' ? <Star /> : d === 'templates' ? <Copy /> : <Archive />}<span>{d[0].toUpperCase() + d.slice(1)}</span></button>)}</nav><div className="sidebar-categories"><div className="eyebrow-row"><span className="eyebrow">Categories</span><button className="icon-button" aria-label="Manage categories" onClick={() => setModal({ kind: 'categories' })}><Plus size={17} /></button></div>{categories.map(c => <button key={c.id} className={`category-nav ${categoryId === c.id ? 'active' : ''}`} onClick={() => { const nextCategory = categoryId === c.id ? null : c.id; if (selectedId) writeRoute({ destination, categoryId: nextCategory, query, searchAll, selectedId: null }, 'push', { gatherEditorEntry: false }); setCategoryId(nextCategory); setSelectedId(null); }}><span className={`dot ${c.color}`} />{c.name}</button>)}</div><button className="nav-link appearance-link" onClick={() => setModal({ kind: "appearance" })}><Palette /><span>Appearance</span></button><button className="nav-link settings-link" onClick={() => setModal({ kind: 'settings' })}><Settings /><span>Settings & backup</span></button></aside>
    <main>
      <div className={`mobile-brand ${selected ? 'detail-mobile' : ''}`}>{selected ? <button className="text-button" onClick={backToCollection}><ArrowLeft size={18} /> Back</button> : <Leaf size={24} />}<span>Gather</span><button className="icon-button mobile-appearance" aria-label="Choose appearance" onClick={() => setModal({ kind: "appearance" })}><Palette size={21} /></button></div>
      <div className="save-status" aria-live="polite">{busy ? 'Saving…' : notice}</div>
      {error && <div className="error-banner" role="alert"><span>{error}</span><button className="icon-button" aria-label="Dismiss error" onClick={() => setError('')}><X size={18} /></button></div>}
      {!workspace ? <div className="loading-panel">{loadError ? <><h1>We couldn’t open your lists</h1><p>{loadError}</p><button className="primary" onClick={() => load(true)}>Retry</button></> : <><Leaf size={36} /><h1>Opening your lists…</h1></>}</div> : selected ? <ListEditor key={selected.id} list={selected} category={categories.find(category => category.id === selected.categoryId)}
        icon={<ListIcon name={selected.icon} size={34} />} badges={<Badges list={selected} />} organization={workspace.version >= 3} busy={busy} error={error} clearError={() => setError('')} mutate={mutate} back={backToCollection}
        savedView={listViews.current.get(selected.id)} updateView={view => listViews.current.set(selected.id, view)}
        edit={() => { setError(''); setModal({ kind: 'list', list: selected }); }} section={section => { setError(''); setModal({ kind: 'section', list: selected, section }); }}
        removeSection={section => confirm('Delete this section?', 'Its items will move to Unsectioned.', 'section.delete', { listId: selected.id, sectionId: section.id, ...orderGuard(selected) }, 'Delete section')}
        clone={() => setModal({ kind: 'clone', list: selected })} listConfirm={action => listConfirm(selected, action)} share={() => { setError(''); setModal({ kind: 'sharing', list: selected }); }} /> : <>
        <div className="library-toolbar"><label className="search-field"><Search size={21} /><input aria-label="Search lists and items" placeholder="Search lists, items and notes" maxLength={500} value={query} onChange={e => setQuery(e.target.value)} />{query && <button className="icon-button" aria-label="Clear search" onClick={() => setQuery('')}><X size={17} /></button>}</label><span className="today">{new Date().toLocaleDateString(undefined, { weekday: 'long', month: 'long', day: 'numeric' })}</span><button className="primary" onClick={() => setModal({ kind: 'list' })}><Plus size={20} />New list</button></div>
        {search && <div className="search-scope"><label>Search in <select aria-label="Search scope" value={searchAll ? "all" : "current"} onChange={e => setSearchAll(e.target.value === "all")}><option value="current">Current collection</option><option value="all">Entire workspace</option></select></label><span role="status">{searchBusy ? "Searching… · text matches available" : response?.provider !== "fuzzy" && response?.status === "ready" && !response.partial ? "Smart search · " + (response.provider === "local" ? "on this server" : "hosted") : response?.status === "ready" && response.partial ? "Smart search limited · text search covers all items" : response?.status === "warming" ? response.partial ? "Smart search preparing · text search covers all items" : "Text search · smart search starting" : searchFailed || response?.status === "degraded" ? "Text search · smart search unavailable" : "Text search"} · {searchAll ? "Entire workspace" : destinationLabels[destination]}</span></div>}
        <header className="page-heading"><h1>{destinationLabels[destination]}</h1><p>{destination === 'templates' ? 'Start a new list from a reusable template.' : destination === 'archive' ? 'Lists you’ve archived for yourself.' : destination === 'favorites' ? 'Lists you’ve marked as favorites.' : 'Your lists, all in one place.'}</p></header>
        <InstallBanner hidden={!!modal} />
        <div className="filter-line"><div className="category-pills"><button className={`pill ${!categoryId ? 'selected' : ''}`} onClick={() => setCategoryId(null)}>All {destination === 'templates' ? 'templates' : 'lists'}</button>{categories.map(c => <button className={`pill ${categoryId === c.id ? 'selected' : ''}`} key={c.id} onClick={() => setCategoryId(c.id)}><span className={`dot ${c.color}`} />{c.name}</button>)}</div><span className="collection-count">{lists.length} {lists.length === 1 ? 'list' : 'lists'}</span></div>
        {lists.length ? <div className="card-grid" aria-busy={searchBusy}>{lists.map(list => <ListCard key={list.id} list={list} category={categories.find(c => c.id === list.categoryId)} match={resultMatches.get(list.id)} busy={busy} open={() => open(list.id)} favorite={() => mutate('list.update', { listId: list.id, favorite: !list.favorite })} clone={() => setModal({ kind: 'clone', list })} restore={() => mutate('list.update', { listId: list.id, archived: false })} remove={() => listConfirm(list, 'delete')} />)}</div> : <div className="empty-state"><Leaf size={36} /><h2>{search ? 'No lists found' : destination === 'archive' ? 'No archived lists' : destination === 'favorites' ? 'No favorites yet' : destination === 'templates' ? 'No templates yet' : 'No lists yet'}</h2><p>{search ? 'Try another word, or clear the search to see your lists.' : destination === 'archive' ? 'Lists you archive will appear here.' : destination === 'favorites' ? 'Mark a list as a favorite to find it here.' : destination === 'templates' ? 'Create a template to reuse a list.' : 'Create a list to get started.'}</p>{search ? <button className="secondary" onClick={() => setQuery('')}>Clear search</button> : <button className="primary" onClick={() => setModal({ kind: 'list' })}><Plus size={18} />New list</button>}</div>}
        {destination !== 'templates' && <footer className="library-footer">Reuse a list for your next plan. <button className="text-button" onClick={() => go('templates')}>Browse templates <span aria-hidden="true">→</span></button></footer>}
      </>}
    </main>
    <nav className="bottom-nav" aria-label="Main navigation"><button className={destination === 'library' || destination === 'favorites' ? 'active' : ''} onClick={() => go('library')}><BookOpen size={21} />Library</button><button className={destination === 'templates' ? 'active' : ''} onClick={() => go('templates')}><Copy size={21} />Templates</button><button className={destination === 'archive' ? 'active' : ''} onClick={() => setModal({ kind: 'more' })}><MoreHorizontal size={22} />More</button></nav>
    {modal && workspace && (!('list' in modal) || !modal.list || workspace.lists.some(l => l.id === modal.list!.id)) && (modal.kind !== 'confirm' || typeof modal.payload.listId !== 'string' || workspace.lists.some(l => l.id === modal.payload.listId)) && <Dialog title={modal.kind === 'list' ? modal.list ? 'List details' : 'New list' : modal.kind === 'section' ? modal.section ? 'Rename section' : 'Add a section' : modal.kind === 'clone' ? modal.list.isTemplate ? 'Start from this template' : 'Duplicate list' : modal.kind === 'confirm' ? modal.title : modal.kind === 'categories' ? 'Your categories' : modal.kind === 'appearance' ? 'Appearance' : modal.kind === 'settings' ? 'Settings & backup' : modal.kind === 'restore' ? 'Import private copies?' : modal.kind === 'sharing' ? modal.list.role === 'editor' ? 'People on this list' : 'Share this list' : 'More'} busy={busy || sharingBusy} close={() => setModal(null)}>
      <div className="dialog-error" role="alert">{error}</div>
       {modal.kind === 'list' && <ListForm list={modal.list} categories={categories} defaultCategoryId={categoryId} defaultTemplate={destination === 'templates'} busy={busy} save={async payload => { const next = await mutate(modal.list ? 'list.update' : 'list.create', { ...(modal.list ? { listId: modal.list.id } : { favorite: destination === 'favorites' }), ...payload }); if (next) { setModal(null); if (!modal.list) { const created = next.lists.find(l => l.id === next.createdListId); if (created) openCreated(created); } } }} cancel={() => setModal(null)} />}
      {modal.kind === 'section' && <NameForm label="Section name" initial={modal.section?.title || ''} busy={busy} save={async title => { if (await mutate(modal.section ? 'section.update' : 'section.create', { listId: modal.list.id, ...(modal.section ? { sectionId: modal.section.id } : orderGuard(workspace.lists.find(list => list.id === modal.list.id) || modal.list)), title })) setModal(null); }} cancel={() => setModal(null)} />}
       {modal.kind === 'clone' && <><p className="dialog-copy">{modal.list.isTemplate ? 'Create a private working copy. People on the template won’t be added. Your template stays unchanged.' : modal.list.kind === 'notes' ? 'Create a private copy of every item and note. People on this list won’t be added.' : 'Create a private copy with all items unchecked. People on this list won’t be added.'}</p><NameForm label="New list name" initial={modal.list.isTemplate ? `${modal.list.title} — ${new Date().toLocaleDateString(undefined, { month: 'short', day: 'numeric', year: 'numeric' })}` : `${modal.list.title} (copy)`} busy={busy} submitLabel={modal.list.isTemplate ? 'Start list' : 'Create copy'} save={async title => { const next = await mutate('list.clone', { listId: modal.list.id, title }); if (next) { const created = next.lists.find(l => l.id === next.createdListId); setModal(null); if (created) openCreated(created); } }} cancel={() => setModal(null)} /></>}
      {modal.kind === 'confirm' && <><p className="dialog-copy">{modal.message}</p><div className="dialog-actions"><button className="secondary" disabled={busy} onClick={() => setModal(null)}>Cancel</button><button className={modal.action === 'list.delete' || modal.action === 'section.delete' || modal.action === 'samples.remove' || modal.action === 'category.delete' ? 'danger' : 'primary'} disabled={busy} onClick={async () => { if (await mutate(modal.action, modal.payload) || modal.action === 'section.delete') setModal(null); }}>{busy ? 'Saving…' : modal.label}</button></div></>}
      {modal.kind === 'sharing' && <SharingPanel list={modal.list} setBusy={setSharingBusy} changed={() => void load()} left={next => { setWorkspace(next); setModal(null); setSelectedId(null); setRanked(null); setNotice('You left the list. Your changes stay with it.'); }} />}{modal.kind === 'categories' && <CategoryManager categories={categories} busy={busy} mutate={mutate} remove={c => confirm('Remove this category?', `Lists in “${c.name}” will become uncategorized. The lists and their items will be kept.`, 'category.delete', { categoryId: c.id }, 'Remove category')} />}
      {modal.kind === 'appearance' && <AppearancePicker />}
      {modal.kind === 'settings' && <SettingsPanel workspace={workspace} busy={busy} error={setError} categories={() => setModal({ kind: 'categories' })} removeSamples={() => confirm('Remove sample lists?', 'All lists marked Sample, including sample templates, will be permanently removed. Your own lists and copies will stay. Samples will not reappear.', 'samples.remove', {}, 'Remove samples')} restore={(document, filename) => setModal({ kind: 'restore', document, filename })} />}
      {modal.kind === 'restore' && <><p className="dialog-copy">Import <strong>{modal.filename}</strong> as {modal.document.lists.length} private list copies?</p><p>Your existing lists stay unchanged. These copies are private; accounts and sharing aren’t imported.</p><div className="dialog-actions"><button className="secondary" disabled={busy} onClick={() => setModal({ kind: 'settings' })}>Cancel</button><button className="primary" disabled={busy} onClick={async () => { if (busyRef.current) return; busyRef.current = true; setBusy(true); setError(''); ++sequence.current; try { const next = await restoreWorkspace(modal.document); setWorkspace(next); setSelectedId(null); setCategoryId(null); setModal(null); setRanked(null); setNotice('Private backup copies imported'); } catch (e) { setError((e as Error).message); } finally { busyRef.current = false; setBusy(false); } }}>{busy ? 'Importing…' : 'Import private copies'}</button></div></>}
      {modal.kind === 'more' && <div className="more-links"><button onClick={() => go('favorites')}><Star />Favorites</button><button onClick={() => go('archive')}><Archive />Archive</button><button onClick={() => setModal({ kind: 'categories' })}><List />Manage categories</button><button onClick={() => setModal({ kind: 'settings' })}><Settings />Settings & backup</button></div>}
    </Dialog>}
    {invitation && workspace && <Dialog title="A list to share" busy={sharingBusy} close={() => { clearInvitation(); setInvitation(''); }}><InvitationPanel token={invitation} setBusy={setSharingBusy} cancel={() => { clearInvitation(); setInvitation(''); }} joined={(next, listId) => { clearInvitation(); setInvitation(''); setModal(null); setWorkspace(next); setRanked(null); const joined = next.lists.find(list => list.id === listId); if (joined) openCreated(joined); setNotice('List opened. Changes are shared with everyone on it.'); }} /></Dialog>}
  </div>;
}

function ListCard({ list, category, match, busy, open, favorite, clone, restore, remove }: { list: GatherList; category?: Category; match?: SearchResult; busy: boolean; open: () => void; favorite: () => unknown; clone: () => void; restore: () => unknown; remove: () => void }) {
  const done = list.items.filter(i => i.checked).length;
  return <article className={`list-card ${list.color}`}><div className="card-top"><span className="card-icon"><ListIcon name={list.icon} size={32} /></span><div className="card-badges"><Badges list={list} /></div>{!list.isTemplate && !list.archived && <button className={`icon-button favorite ${list.favorite ? 'is-favorite' : ''}`} aria-label={`${list.favorite ? 'Unfavorite' : 'Favorite'} ${list.title}`} aria-pressed={list.favorite} disabled={busy} onClick={favorite}><Star size={21} fill={list.favorite ? 'currentColor' : 'none'} /></button>}</div><button className="card-open" onClick={open}><h2>{list.title}</h2><span className="card-category"><span className={`dot ${category?.color || list.color}`} />{category?.name || 'Uncategorized'}</span><p className={match ? 'match-preview' : ''}>{match ? <>{match.matchType === "semantic" ? "Related " : ""}{matchLabels[match.match.field] || "Content"}: {match.match.text}</> : list.description || (list.isTemplate ? 'Reusable template' : 'No description')}</p></button><div className="card-bottom">{list.isTemplate ? <><span>{list.items.length} items · reusable</span><button className="text-button" disabled={busy} onClick={clone}>Start list <span aria-hidden="true">→</span></button></> : list.kind === 'notes' ? <span>{list.items.length ? `${list.items.length} ${list.items.length === 1 ? 'item' : 'items'}` : 'No items yet'}</span> : <><span>{list.items.length ? `${done} of ${list.items.length} done` : 'No items yet'}</span><div className="progress-track"><div style={{ width: `${list.items.length ? done / list.items.length * 100 : 0}%` }} /></div></>}{list.archived && <div className="archive-actions"><button className="text-button" disabled={busy} onClick={restore}>Restore</button>{list.role !== 'editor' && <button className="icon-button" aria-label={`Delete ${list.title}`} disabled={busy} onClick={remove}><Trash2 size={18} /></button>}</div>}</div></article>;
}

function ListForm({ list, categories, defaultCategoryId, defaultTemplate, busy, save, cancel }: { list?: GatherList; categories: Category[]; defaultCategoryId: string | null; defaultTemplate: boolean; busy: boolean; save: (payload: Payload) => Promise<void>; cancel: () => void }) {
  const [title, setTitle] = useState(list?.title || '');
  const [description, setDescription] = useState(list?.description || '');
  const [categoryId, setCategoryId] = useState(list ? list.categoryId || '' : defaultCategoryId || '');
  const [icon, setIcon] = useState(list?.icon || 'list');
  const [color, setColor] = useState<Color>(list?.color || 'sage');
  const [template, setTemplate] = useState(list?.isTemplate ?? defaultTemplate);
  const [kind, setKind] = useState<ListKind>(list?.kind || 'checklist');
  const [repeatCadence, setRepeatCadence] = useState<RepeatCadence>(list?.repeatCadence || 'manual');
  const [moreOptions, setMoreOptions] = useState(Boolean(list));
  const [showDescription, setShowDescription] = useState(Boolean(list?.description));
  const [validation, setValidation] = useState('');
  const titleRef = useRef<HTMLInputElement>(null);
  const owner = list?.role !== 'editor';
  return <form noValidate onSubmit={event => {
    event.preventDefault();
    if (!title.trim()) { setValidation('Enter a list name.'); titleRef.current?.focus(); return; }
    void save({ title, description, categoryId: categoryId || null, icon, color, ...(owner ? { kind, repeatCadence, isTemplate: template } : {}) });
  }}>
    {list?.shared && <p className="dialog-copy">Title, description, icon and color change for everyone. Your category is personal.</p>}
    <label className="field">List name<input ref={titleRef} autoFocus required maxLength={200} value={title} onChange={event => { setTitle(event.target.value); setValidation(''); }} /></label>
    {validation && <p className="field-error" role="alert">{validation}</p>}
    {!list && <button className="text-button form-options-button" type="button" aria-expanded={moreOptions} onClick={() => setMoreOptions(!moreOptions)}><ChevronDown size={17} />More options</button>}
    {moreOptions && <div className="list-form-options">
    {showDescription ? <label className="field">Description<textarea autoFocus={!list?.description} maxLength={4000} value={description} onChange={event => setDescription(event.target.value)} rows={3} /></label>
      : <button className="text-button add-description" type="button" onClick={() => setShowDescription(true)}><Plus size={16} />Add description</button>}
    {owner && <><fieldset><legend>List kind</legend><div className="segmented kind-options">{(['checklist', 'notes'] as ListKind[]).map(value => <button key={value} type="button" className={kind === value ? 'active' : ''} aria-pressed={kind === value} onClick={() => setKind(value)}>{value === 'checklist' ? 'Checklist' : 'Notes & ideas'}</button>)}</div><p className="field-help">{kind === 'notes' ? 'Keep ideas and bullet points. Existing checkmarks stay saved if you switch back.' : 'Check off tasks and essentials.'}</p></fieldset>{kind === 'checklist' && <label className="field">Repeat<select value={repeatCadence} onChange={event => setRepeatCadence(event.target.value as RepeatCadence)}><option value="manual">Manual</option><option value="daily">Daily</option><option value="weekly">Weekly</option></select><span className="field-help">Start again whenever you’re ready. Nothing resets automatically.</span></label>}</>}
    <label className="field">Your category<select value={categoryId} onChange={event => setCategoryId(event.target.value)}><option value="">Uncategorized</option>{categories.map(category => <option key={category.id} value={category.id}>{category.name}</option>)}</select><span className="field-help">Only you see this category.</span></label>
    <fieldset><legend>List icon</legend><div className="icon-options">{iconNames.map(name => <button className={`icon-choice ${icon === name ? 'chosen' : ''}`} aria-label={name} aria-pressed={icon === name} key={name} type="button" onClick={() => setIcon(name)}><ListIcon name={name} /></button>)}</div></fieldset>
    <fieldset><legend>Color</legend><div className="color-options">{colors.map(value => <button className={`color-choice ${value} ${color === value ? 'chosen' : ''}`} key={value} aria-label={value} aria-pressed={color === value} type="button" onClick={() => setColor(value)}>{color === value && <Check size={18} />}</button>)}</div></fieldset>
    {owner && <label className="check-field"><input type="checkbox" checked={template} onChange={event => setTemplate(event.target.checked)} />Keep as a reusable template</label>}
    </div>}
    <div className="dialog-actions"><button className="secondary" type="button" disabled={busy} onClick={cancel}>Cancel</button><button className="primary" disabled={busy}>{busy ? 'Saving…' : list ? 'Save' : 'Create'}</button></div>
  </form>;
}
function NameForm({ label, initial, busy, save, cancel, submitLabel = 'Save' }: { label: string; initial: string; busy: boolean; save: (title: string) => Promise<void>; cancel: () => void; submitLabel?: string }) {
  const [title, setTitle] = useState(initial); return <form onSubmit={e => { e.preventDefault(); void save(title); }}><label className="field">{label}<input autoFocus required maxLength={200} value={title} onChange={e => setTitle(e.target.value)} /></label><div className="dialog-actions"><button className="secondary" type="button" disabled={busy} onClick={cancel}>Cancel</button><button className="primary" disabled={busy || !title.trim()}>{busy ? 'Saving…' : submitLabel}</button></div></form>;
}
function CategoryManager({ categories, busy, mutate, remove }: { categories: Category[]; busy: boolean; mutate: Mutation; remove: (category: Category) => void }) {
  const [name, setName] = useState(''); const [color, setColor] = useState<Color>('sage');
  return <><p className="dialog-copy">Categories are personal to you.</p><div className="category-manager">{categories.map(c => <CategoryRow key={c.id} category={c} busy={busy} mutate={mutate} remove={() => remove(c)} />)}</div><form className="category-new" onSubmit={async e => { e.preventDefault(); if (await mutate('category.create', { name, color })) setName(''); }}><label className="field">New category<input placeholder="Category name" maxLength={80} required value={name} onChange={e => setName(e.target.value)} /></label><div className="category-new-actions"><select aria-label="New category color" value={color} onChange={e => setColor(e.target.value as Color)}>{colors.map(c => <option key={c}>{c}</option>)}</select><button className="primary" disabled={busy || !name.trim()}><Plus size={16} />Add category</button></div></form></>;
}
function CategoryRow({ category, busy, mutate, remove }: { category: Category; busy: boolean; mutate: Mutation; remove: () => void }) {
  const [name, setName] = useState(category.name); const [color, setColor] = useState(category.color);
  return <form className="category-edit" onSubmit={e => { e.preventDefault(); void mutate('category.update', { categoryId: category.id, name, color }); }}><input aria-label={`Rename ${category.name}`} maxLength={80} required value={name} onChange={e => setName(e.target.value)} /><select aria-label={`Color for ${category.name}`} value={color} onChange={e => setColor(e.target.value as Color)}>{colors.map(c => <option key={c}>{c}</option>)}</select><button className="icon-button" aria-label={`Save ${category.name} category`} disabled={busy || !name.trim()}><Check size={18} /></button><button type="button" className="icon-button danger-text" aria-label={`Remove ${category.name} category`} disabled={busy} onClick={remove}><Trash2 size={17} /></button></form>;
}
function AccountPanel() {
  const auth = useAuth();
  const [currentPassword, setCurrentPassword] = useState('');
  const [newPassword, setNewPassword] = useState('');
  const [confirmation, setConfirmation] = useState('');
  const [message, setMessage] = useState('');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const [signingOut, setSigningOut] = useState(false);
  const submit = async (event: FormEvent) => {
    event.preventDefault(); setMessage(''); setError('');
    if (!validNewPassword(newPassword)) { setError(passwordHint); return; }
    if (newPassword !== confirmation) { setError('The passwords do not match.'); return; }
    setBusy(true);
    try { await auth.changePassword(currentPassword, newPassword); setCurrentPassword(''); setNewPassword(''); setConfirmation(''); setMessage('Password changed. Other devices have been signed out.'); } catch (err) { setError((err as Error).message); } finally { setBusy(false); }
  };
  const signOut = async () => { setError(''); setSigningOut(true); try { await auth.logout(); } catch (err) { setError((err as Error).message); } finally { setSigningOut(false); } };
  return <section className="account-panel"><h3>Account</h3><p>Signed in as <strong>{auth.session.username || 'your account'}</strong>.</p><button className="secondary" disabled={busy || signingOut} onClick={() => void signOut()}>{signingOut ? 'Signing out…' : 'Sign out'}</button><form className="account-password" onSubmit={submit} noValidate><h4>Change password</h4><p>Changing your password signs out other devices.</p>{error && <div className="auth-error" role="alert">{error}</div>}{message && <div className="account-success" role="status">{message}</div>}<label className="field">Current password<input type="password" autoComplete="current-password" maxLength={128} value={currentPassword} onChange={event => setCurrentPassword(event.target.value)} /></label><label className="field">New password<input type="password" autoComplete="new-password" minLength={6} maxLength={128} value={newPassword} onChange={event => setNewPassword(event.target.value)} /><span className="field-help">{passwordHint}</span></label><label className="field">Confirm new password<input type="password" autoComplete="new-password" maxLength={128} value={confirmation} onChange={event => setConfirmation(event.target.value)} /></label><button className="primary" disabled={busy}>{busy ? 'Changing password…' : 'Change password'}</button></form></section>;
}

function SettingsPanel({ workspace, busy, error, categories, removeSamples, restore }: { workspace: Workspace; busy: boolean; error: (message: string) => void; categories: () => void; removeSamples: () => void; restore: (document: Workspace, filename: string) => void }) {
  const [reading, setReading] = useState(false);
  const [searchStatus, setSearchStatus] = useState<SearchStatus | null>(null);
  useEffect(() => { let stale = false; void getSearchStatus().then(value => { if (!stale) setSearchStatus(value); }).catch(() => {}); return () => { stale = true; }; }, []);
  return <div className="settings-panel"><AccountPanel /><InstallSettings /><section><h3>Appearance</h3><AppearancePicker /></section><section><h3>Search</h3><p>{!searchStatus ? "Text search is available. Smart search status is unavailable." : searchStatus.provider === "fuzzy" ? "Text search is available. Smart search is disabled on this server." : searchStatus.provider === "local" ? "Smart search processes content on this server. " + (searchStatus.state === "ready" ? "Ready." : searchStatus.state === "warming" ? "Starting; text matches remain available." : "Unavailable; text matches remain available.") : "Hosted smart search sends queries and relevant list content to the configured provider. " + (searchStatus.state === "ready" ? "Ready." : "Text matches remain available while unavailable.")}</p></section><section><h3>Export backup</h3><p>Export lists you own, with their notes and checkmarks. Lists you joined are excluded; make a private copy of one to keep it in your backup. Your places and who items are for are included. Accounts, sharing members and invite links are excluded.</p><a className="secondary" href="/api/export" download="gather-backup.json"><ArrowDown size={17} />Export JSON</a></section><section><h3>Import backup copies</h3><p>Add private copies from a Gather backup. Your current lists stay as they are. You’ll review before importing.</p><label className={`secondary upload-button ${busy || reading ? 'disabled' : ''}`}><ArrowUp size={17} />{reading ? 'Reading backup…' : 'Choose JSON backup'}<input type="file" accept="application/json,.json" disabled={busy || reading} onChange={async e => { const file = e.target.files?.[0]; e.target.value = ''; if (!file) return; setReading(true); error(''); try { if (file.size > 5 * 1024 * 1024) throw new Error('Backup is too large. Choose a file smaller than 5 MiB.'); const document: unknown = JSON.parse(await file.text()); validateBackup(document); if (document.version > workspace.version) throw new Error('This backup requires a newer Gather server.'); restore(document, file.name); } catch (err) { error(err instanceof SyntaxError ? 'This file is not valid JSON. Your workspace has not changed.' : (err as Error).message); } finally { setReading(false); } }} /></label></section><section><h3>Lists and categories</h3><button className="text-button" onClick={categories}>Manage categories <span aria-hidden="true">→</span></button>{workspace.lists.some(l => l.isSample) && <><p>Remove sample lists when you no longer need them.</p><button className="text-button danger-text" disabled={busy} onClick={removeSamples}>Remove sample lists</button></>}</section><p className="settings-footnote"><Leaf size={15} />Gather</p></div>;
}
