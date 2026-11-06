import { DatabaseSync } from 'node:sqlite';
import { randomUUID, randomBytes, createHash } from 'node:crypto';
import { mkdirSync } from 'node:fs';
import { join } from 'node:path';
import { seedWorkspace } from './seed.mjs';

export class InputError extends Error { constructor(message, status = 400) { super(message); this.status = status; } }
export const WORKSPACE_BYTE_LIMIT = 5 * 1024 * 1024;
function boundedDocument(value) {
  const document = JSON.stringify(value);
  if (Buffer.byteLength(document, 'utf8') > WORKSPACE_BYTE_LIMIT) throw new InputError('Workspace exceeds 5 MiB limit. Remove items or shorten notes before adding more.', 413);
  return document;
}
const fail = message => { throw new InputError(message); };
const object = value => value && typeof value === 'object' && !Array.isArray(value) || fail('Expected an object.');
const string = (value, name, max, required = false) => {
  if (typeof value !== 'string' || value.length > max || (required && !value.trim())) fail(`${name} must be ${required ? 'non-empty text' : 'text'} up to ${max} characters.`);
  return value;
};
const bool = (value, name) => typeof value === 'boolean' ? value : fail(`${name} must be a boolean.`);
const id = value => string(value, 'ID', 100, true);
const pos = value => Number.isSafeInteger(value) && value >= 0 ? value : fail('Position must be a nonnegative integer.');
const colors = new Set(['sage', 'peach', 'lavender', 'sky', 'sand', 'rose']);
const color = value => colors.has(value) ? value : fail('Unknown color.');
const find = (array, value, name) => array.find(entry => entry.id === value) || fail(`${name} not found.`);
function normalize(array) { array.sort((a,b) => a.position - b.position).forEach((entry, index) => { entry.position = index; }); }
function appendPosition(array) { return pos(array.reduce((max, entry) => Math.max(max, entry.position), -1) + 1); }
function normalizeItems(list) { for (const sectionId of [null, ...list.sections.map(s => s.id)]) normalize(list.items.filter(item => item.sectionId === sectionId)); }
function section(list, value) { if (value !== null) find(list.sections, id(value), 'Section'); return value; }
function fields(target, source, specs) { for (const [key, validate] of Object.entries(specs)) if (Object.hasOwn(source, key)) target[key] = validate(source[key]); }
const itemFields = { text: v => string(v, 'Item text', 500, true), quantity: v => string(v, 'Quantity', 100), note: v => string(v, 'Note', 4000), checked: v => bool(v, 'Checked') };
const kind = value => ['checklist', 'notes'].includes(value) ? value : fail('Kind must be checklist or notes.');
const repeatCadence = value => ['manual', 'daily', 'weekly'].includes(value) ? value : fail('Repeat cadence must be manual, daily, or weekly.');
const listFields = { title: v => string(v, 'Title', 200, true), description: v => string(v, 'Description', 4000), icon: v => string(v, 'Icon', 40, true), color, favorite: v => bool(v, 'Favorite'), archived: v => bool(v, 'Archived'), isTemplate: v => bool(v, 'Template'), kind, repeatCadence };

export function validateWorkspace(input) {
  object(input);
  boundedDocument(input);
  if (![1, 2].includes(input.version) || !Array.isArray(input.categories) || !Array.isArray(input.lists)) fail('Expected a version 1 or 2 workspace with categories and lists.');
  if (input.categories.length > 100 || input.lists.length > 1000) fail('Workspace exceeds category or list limit.');
  const ids = new Set();
  const unique = value => { id(value); if (ids.has(value)) fail('IDs must be unique throughout the workspace.'); ids.add(value); return value; };
  const categories = input.categories.map(c => { object(c); return { id: unique(c.id), name: string(c.name, 'Category name', 80, true), color: color(c.color), position: pos(c.position) }; });
  let count = 0;
  const lists = input.lists.map(l => {
    object(l);
    if (!Array.isArray(l.sections) || !Array.isArray(l.items) || l.sections.length > 100 || l.items.length > 5000 || (count += l.items.length) > 20000) fail('Workspace exceeds section or item limit.');
    const result = { id: unique(l.id), position: pos(l.position) };
    for (const [key, validator] of Object.entries(listFields)) result[key] = validator(input.version === 1 && key === 'kind' ? 'checklist' : input.version === 1 && key === 'repeatCadence' ? 'manual' : l[key]);
    result.categoryId = l.categoryId === null ? null : find(categories, id(l.categoryId), 'Category').id;
    result.isSample = bool(l.isSample, 'Sample');
    for (const key of ['createdAt', 'updatedAt']) { result[key] = string(l[key], key, 40, true); if (!Number.isFinite(Date.parse(result[key]))) fail('Invalid timestamp.'); }
    result.sections = l.sections.map(s => { object(s); return { id: unique(s.id), title: string(s.title, 'Section title', 200, true), position: pos(s.position) }; });
    result.items = l.items.map(i => { object(i); const item = { id: unique(i.id), position: pos(i.position), sectionId: section(result, i.sectionId) }; for (const [key, validator] of Object.entries(itemFields)) item[key] = validator(i[key]); return item; });
    return result;
  });
  return { version: 2, categories, lists };
}

export function applyAction(workspace, action) {
  object(action); object(action.payload);
  const p = action.payload, type = string(action.type, 'Action type', 80, true), now = new Date().toISOString();
  if (type === 'samples.remove') { workspace.lists = workspace.lists.filter(l => !l.isSample); normalize(workspace.lists); return; }
  if (type === 'category.create') { if (workspace.categories.length >= 100) fail('Category limit reached.'); workspace.categories.push({ id: randomUUID(), name: string(p.name, 'Category name', 80, true), color: color(p.color ?? 'sage'), position: appendPosition(workspace.categories) }); return; }
  if (type.startsWith('category.')) {
    const c = find(workspace.categories, id(p.categoryId), 'Category');
    if (type === 'category.update') fields(c, p, { name: v => string(v, 'Category name', 80, true), color });
    else if (type === 'category.delete') { workspace.categories = workspace.categories.filter(x => x !== c); for (const l of workspace.lists) if (l.categoryId === c.id) { l.categoryId = null; l.updatedAt = now; } normalize(workspace.categories); }
    else fail('Unknown action.');
    return;
  }
  if (type === 'list.create') {
    if (workspace.lists.length >= 1000) fail('List limit reached.');
    const l = { id: randomUUID(), title: string(p.title, 'Title', 200, true), description: '', icon: 'list', color: 'sage', categoryId: null, favorite: false, archived: false, isTemplate: false, isSample: false, kind: 'checklist', repeatCadence: 'manual', createdAt: now, updatedAt: now, position: appendPosition(workspace.lists), sections: [], items: [] };
    fields(l, p, listFields); if (Object.hasOwn(p, 'categoryId')) l.categoryId = p.categoryId === null ? null : find(workspace.categories, id(p.categoryId), 'Category').id;
    workspace.lists.push(l); return l.id;
  }
  const l = find(workspace.lists, id(p.listId), 'List');
  if (type === 'list.update') { fields(l, p, listFields); if (Object.hasOwn(p, 'categoryId')) l.categoryId = p.categoryId === null ? null : find(workspace.categories, id(p.categoryId), 'Category').id; }
  else if (type === 'list.delete') { workspace.lists = workspace.lists.filter(x => x !== l); normalize(workspace.lists); }
  else if (type === 'list.reset') { if (l.kind !== 'checklist' || l.isTemplate || l.archived) fail('Only active checklist working lists can start again.'); l.items.forEach(i => { i.checked = false; }); }
  else if (type === 'list.clone') {
    if (workspace.lists.length >= 1000) fail('List limit reached.');
    if (workspace.lists.reduce((n,x) => n+x.items.length,0) + l.items.length > 20000) fail('Workspace item limit reached.');
    const copy = structuredClone(l), mapping = new Map(copy.sections.map(s => [s.id, randomUUID()]));
    Object.assign(copy, { id: randomUUID(), title: p.title === undefined ? `${l.title} ${l.isTemplate ? '— ' + new Date().toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric' }) : '(copy)'}`.slice(0,200) : string(p.title, 'Title', 200, true), favorite: false, archived: false, isTemplate: false, isSample: false, createdAt: now, updatedAt: now, position: appendPosition(workspace.lists) });
    copy.sections.forEach(s => { s.id = mapping.get(s.id); }); copy.items.forEach(i => { i.id = randomUUID(); i.checked = false; i.sectionId = i.sectionId === null ? null : mapping.get(i.sectionId); });
    workspace.lists.push(copy); return copy.id;
  }
  else if (type === 'section.create') { if (l.sections.length >= 100) fail('Section limit reached.'); l.sections.push({ id: randomUUID(), title: string(p.title, 'Section title', 200, true), position: appendPosition(l.sections) }); }
  else if (type.startsWith('section.')) {
    const s = find(l.sections, id(p.sectionId), 'Section');
    if (type === 'section.update') s.title = string(p.title, 'Section title', 200, true);
    else if (type === 'section.delete') { let next = appendPosition(l.items.filter(i => i.sectionId === null)); l.items.filter(i => i.sectionId === s.id).sort((a,b) => a.position-b.position).forEach(i => { i.sectionId = null; i.position = pos(next++); }); l.sections = l.sections.filter(x => x !== s); normalize(l.sections); }
    else if (type === 'section.move') { const index = pos(p.index); if (index >= l.sections.length) fail('Index out of range.'); const ordered = l.sections.toSorted((a,b) => a.position-b.position).filter(x => x !== s); ordered.splice(index,0,s); ordered.forEach((x,index) => { x.position = index; }); }
    else fail('Unknown action.');
  }
  else if (type === 'item.create') { if (l.kind === 'notes' && Object.hasOwn(p, 'checked') && p.checked !== false) fail('Notes cannot have new checked items.'); if (l.items.length >= 5000 || workspace.lists.reduce((n,x) => n+x.items.length,0) >= 20000) fail('Item limit reached.'); const sectionId = section(l, p.sectionId ?? null); const i = { id: randomUUID(), text: string(p.text,'Item text',500,true), quantity: '', note: '', checked: false, sectionId, position: appendPosition(l.items.filter(x => x.sectionId === sectionId)) }; fields(i,p,itemFields); l.items.push(i); }
  else if (type.startsWith('item.')) {
    const i = find(l.items, id(p.itemId), 'Item');
    if (type === 'item.delete') l.items = l.items.filter(x => x !== i);
    else if (type === 'item.update') { if (l.kind === 'notes' && Object.hasOwn(p, 'checked')) fail('Completion edits are unavailable for notes.'); fields(i,p,itemFields); if (Object.hasOwn(p,'sectionId') && p.sectionId !== i.sectionId) { i.sectionId = section(l,p.sectionId); i.position = appendPosition(l.items.filter(x => x !== i && x.sectionId === i.sectionId)); } }
    else if (type === 'item.move') { const sectionId = section(l,p.sectionId); const ordered = l.items.filter(x => x !== i && x.sectionId === sectionId).sort((a,b) => a.position-b.position); const index = pos(p.index); if (index > ordered.length) fail('Index out of range.'); i.sectionId = sectionId; ordered.splice(index,0,i); ordered.forEach((x,index) => { x.position = index; }); }
    else fail('Unknown action.');
    normalizeItems(l);
  }
  else fail('Unknown action.');
  l.updatedAt = now;
}

export function createStore(dataDir) {
  mkdirSync(dataDir, { recursive: true });
  const db = new DatabaseSync(join(dataDir, 'gather.sqlite'));
  db.exec('PRAGMA journal_mode=WAL; PRAGMA synchronous=FULL; PRAGMA busy_timeout=5000; CREATE TABLE IF NOT EXISTS workspace (id INTEGER PRIMARY KEY CHECK (id=1), document TEXT NOT NULL);');
  const read = db.prepare('SELECT document FROM workspace WHERE id=1');
  const write = db.prepare('INSERT INTO workspace(id,document) VALUES(1,?) ON CONFLICT(id) DO UPDATE SET document=excluded.document');
  if (!read.get()) write.run(boundedDocument(seedWorkspace()));
  const get = () => JSON.parse(read.get().document);
  const transaction = callback => { db.exec('BEGIN IMMEDIATE'); try { const w = callback(get()); write.run(boundedDocument(w)); db.exec('COMMIT'); return w; } catch (error) { db.exec('ROLLBACK'); throw error; } };
  try { if (get().version === 1) transaction(w => validateWorkspace(w)); }
  catch (error) { db.close(); throw error; }
  return {
    get,
    action: action => {
      let createdListId;
      const workspace = transaction(w => { createdListId = applyAction(w,action); return w; });
      return createdListId ? { ...workspace, createdListId } : workspace;
    },
    import: input => { const validated = validateWorkspace(input); return transaction(() => validated); },
    close: () => db.close(),
  };
}

// User workspaces contain canonical owned content; per-user list presentation and
// membership live separately so one editor cannot reorganize another's library.
export function createUserStore(dataDir, { legacyOwnerId = null, username = () => 'Gather user' } = {}) {
  mkdirSync(dataDir,{recursive:true});
  const db=new DatabaseSync(join(dataDir,'gather.sqlite'));
  db.exec(`PRAGMA journal_mode=WAL; PRAGMA synchronous=FULL; PRAGMA busy_timeout=5000; PRAGMA foreign_keys=ON;
    CREATE TABLE IF NOT EXISTS user_workspaces(user_id TEXT PRIMARY KEY,document TEXT NOT NULL);
    CREATE TABLE IF NOT EXISTS list_owners(list_id TEXT PRIMARY KEY,owner_id TEXT NOT NULL);
    CREATE TABLE IF NOT EXISTS list_members(list_id TEXT NOT NULL REFERENCES list_owners(list_id) ON DELETE CASCADE,user_id TEXT NOT NULL,joined_at INTEGER NOT NULL,PRIMARY KEY(list_id,user_id));
    CREATE INDEX IF NOT EXISTS list_members_user ON list_members(user_id);
    CREATE TABLE IF NOT EXISTS list_preferences(user_id TEXT NOT NULL,list_id TEXT NOT NULL REFERENCES list_owners(list_id) ON DELETE CASCADE,document TEXT NOT NULL,PRIMARY KEY(user_id,list_id));
    CREATE TABLE IF NOT EXISTS list_invitations(list_id TEXT PRIMARY KEY REFERENCES list_owners(list_id) ON DELETE CASCADE,token_hash TEXT NOT NULL UNIQUE,expires_at INTEGER NOT NULL,consumed_by TEXT);
    CREATE TABLE IF NOT EXISTS user_migrations(name TEXT PRIMARY KEY);`);
  const read=db.prepare('SELECT document FROM user_workspaces WHERE user_id=?');
  const write=db.prepare('INSERT INTO user_workspaces VALUES(?,?) ON CONFLICT(user_id) DO UPDATE SET document=excluded.document');
  const owner=db.prepare('SELECT owner_id FROM list_owners WHERE list_id=?');
  const member=db.prepare('SELECT user_id FROM list_members WHERE list_id=? AND user_id=?');
  const readPref=db.prepare('SELECT document FROM list_preferences WHERE user_id=? AND list_id=?');
  const writePref=db.prepare('INSERT INTO list_preferences VALUES(?,?,?) ON CONFLICT(user_id,list_id) DO UPDATE SET document=excluded.document');
  const personalKeys=['favorite','archived','categoryId','position'];
  const personal=list=>Object.fromEntries(personalKeys.map(key=>[key,list[key]]));
  const canonical=list=> {
    const result=structuredClone(list);
    for(const key of ['role','ownerUsername','shared']) delete result[key];
    Object.assign(result,{favorite:false,archived:false,categoryId:null});
    return result;
  };
  const transaction=callback=> { db.exec('BEGIN IMMEDIATE'); try {const result=callback();db.exec('COMMIT');return result;} catch(error){db.exec('ROLLBACK');throw error;} };
  function raw(userId) {const row=read.get(userId);return row ? JSON.parse(row.document) : null;}
  function ensure(userId) {
    id(userId);
    if(!read.get(userId)) write.run(userId,boundedDocument({version:2,categories:seedWorkspace().categories,lists:[]}));
  }
  function saveOwned(userId,workspace) {
    boundedDocument(workspace);
    const normalized=validateWorkspace(workspace);
    for(const row of db.prepare('SELECT list_id FROM list_owners WHERE owner_id=?').all(userId)) if(!normalized.lists.some(l=>l.id===row.list_id)) db.prepare('DELETE FROM list_owners WHERE list_id=?').run(row.list_id);
    for(const list of normalized.lists) {
      const existing=owner.get(list.id);
      if(existing && existing.owner_id!==userId) throw new InputError('List identifier conflict.',409);
      db.prepare('INSERT OR IGNORE INTO list_owners VALUES(?,?)').run(list.id,userId);
    }
    write.run(userId,boundedDocument(normalized));
  }
  // Migration is explicit to the existing singleton owner's stable account ID.
  // An unowned prototype is never assigned to the first public registrant.
  if(legacyOwnerId && !db.prepare("SELECT name FROM user_migrations WHERE name='singleton-owner-v1'").get()) {
    try {
      transaction(()=> {
        const legacy=db.prepare("SELECT name FROM sqlite_master WHERE type='table' AND name='workspace'").get();
        const row=legacy ? db.prepare('SELECT document FROM workspace WHERE id=1').get() : null;
        if(row && !read.get(legacyOwnerId)) {
          const document=validateWorkspace(JSON.parse(row.document));
          saveOwned(legacyOwnerId,{...document,lists:document.lists.map(canonical)});
          for(const list of document.lists) writePref.run(legacyOwnerId,list.id,JSON.stringify(personal(list)));
        }
        db.prepare("INSERT INTO user_migrations VALUES('singleton-owner-v1')").run();
      });
    } catch(error) {db.close();throw error;}
  }
  function access(userId,listId) {
    id(userId);id(listId);
    const row=owner.get(listId);
    if(!row || (row.owner_id!==userId && !member.get(listId,userId))) throw new InputError('List not found or access has ended.',404);
    return row.owner_id;
  }
  function get(userId) {
    ensure(userId);
    const own=raw(userId),lists=own.lists.map(l=>({...l,role:'owner',ownerUsername:username(userId)}));
    const joined=db.prepare('SELECT m.list_id,o.owner_id,m.joined_at FROM list_members m JOIN list_owners o ON o.list_id=m.list_id WHERE m.user_id=? ORDER BY m.joined_at,m.list_id').all(userId);
    for(const entry of joined) {
      const list=raw(entry.owner_id)?.lists.find(l=>l.id===entry.list_id);
      if(list) lists.push({...list,role:'editor',ownerUsername:username(entry.owner_id),position:lists.length});
    }
    for(const list of lists) {
      const row=readPref.get(userId,list.id);
      if(row) Object.assign(list,JSON.parse(row.document));
      list.shared=Boolean(db.prepare('SELECT 1 FROM list_members WHERE list_id=? LIMIT 1').get(list.id));
      if(list.categoryId!==null && !own.categories.some(c=>c.id===list.categoryId)) list.categoryId=null;
    }
    return {version:2,categories:own.categories,lists:lists.sort((a,b)=>a.position-b.position || a.id.localeCompare(b.id))};
  }
  function persistProjection(userId,workspace,editedOwner=null,editedListId=null) {
    validateWorkspace(workspace);
    const owned=workspace.lists.filter(l=>l.role!=='editor');
    saveOwned(userId,{version:2,categories:workspace.categories,lists:owned.map(canonical)});
    for(const list of workspace.lists) writePref.run(userId,list.id,JSON.stringify(personal(list)));
    if(editedOwner && editedOwner!==userId) {
      const source=raw(editedOwner),changed=workspace.lists.find(l=>l.id===editedListId);
      source.lists=source.lists.map(l=>l.id===editedListId ? canonical(changed) : l);
      saveOwned(editedOwner,source);
    }
  }
  function action(input,userId) {
    object(input);object(input.payload);
    const type=string(input.type,'Action type',80,true),p=input.payload;
    let createdListId;
    transaction(()=> {
      const workspace=get(userId);
      const originalTimes=new Map(workspace.lists.map(list=>[list.id,list.updatedAt]));
      let editedOwner=null;
      if(!['samples.remove','category.create','category.update','category.delete','list.create'].includes(type)) {
        editedOwner=access(userId,id(p.listId));
        if(editedOwner!==userId) {
          if(type==='list.delete') throw new InputError('Only the owner can delete this list. Leave it to remove it from your library.',403);
          if(type==='list.update') {
            const allowed=new Set(['listId','title','description','icon','color',...personalKeys]);
            if(Object.keys(p).some(key=>!allowed.has(key))) throw new InputError('Only the owner can change list type, template, or repeat settings.',403);
          }
        }
      }
      // Position is personal presentation, handled separately from list content.
      if(type==='list.update' && Object.hasOwn(p,'position')) pos(p.position);
      if(type==='samples.remove') workspace.lists=workspace.lists.filter(l=>l.role==='editor' || !l.isSample);
      else createdListId=applyAction(workspace,input);
      if(type==='category.delete' || (type==='list.update' && Object.keys(p).every(key=>key==='listId' || personalKeys.includes(key)))) {
        for(const list of workspace.lists) if(originalTimes.has(list.id)) list.updatedAt=originalTimes.get(list.id);
      }
      if(type==='list.update' && Object.hasOwn(p,'position')) workspace.lists.find(l=>l.id===p.listId).position=p.position;
      if(createdListId) {
        const created=workspace.lists.find(l=>l.id===createdListId);
        delete created.role;delete created.ownerUsername;delete created.shared;
        // A clone of an editor's list is owned solely by the copying user.
      }
      persistProjection(userId,workspace,editedOwner,p.listId);
    });
    const workspace=get(userId);
    return createdListId ? {...workspace,createdListId} : workspace;
  }
  function exportOwned(userId) {
    const workspace=get(userId);
    return {version:2,categories:workspace.categories,lists:workspace.lists.filter(l=>l.role==='owner').map(l=> {const result={...l};delete result.role;delete result.ownerUsername;delete result.shared;return result;})};
  }
  function importCopies(input,userId) {
    const document=validateWorkspace(input);
    transaction(()=> {
      const workspace=get(userId),mapping=new Map();
      for(const category of document.categories) mapping.set(category.id,randomUUID());
      for(const list of document.lists) {mapping.set(list.id,randomUUID());for(const section of list.sections) mapping.set(section.id,randomUUID());for(const item of list.items) mapping.set(item.id,randomUUID());}
      const categoryStart=appendPosition(workspace.categories),listStart=appendPosition(workspace.lists);
      workspace.categories.push(...document.categories.toSorted((a,b)=>a.position-b.position).map((c,index)=>({...c,id:mapping.get(c.id),position:categoryStart+index})));
      workspace.lists.push(...document.lists.toSorted((a,b)=>a.position-b.position).map((l,index)=>({...l,id:mapping.get(l.id),categoryId:l.categoryId===null ? null : mapping.get(l.categoryId),position:listStart+index,isSample:false,sections:l.sections.map(s=>({...s,id:mapping.get(s.id)})),items:l.items.map(i=>({...i,id:mapping.get(i.id),sectionId:i.sectionId===null ? null : mapping.get(i.sectionId)}))})));
      persistProjection(userId,workspace);
    });
    return get(userId);
  }
  function sharing(userId,listId) {
    const ownerId=access(userId,id(listId)),isOwner=ownerId===userId;
    const invitation=db.prepare('SELECT expires_at,consumed_by FROM list_invitations WHERE list_id=?').get(listId);
    const active=Boolean(invitation && !invitation.consumed_by && invitation.expires_at>Date.now());
    return {owner:isOwner,role:isOwner ? 'owner' : 'editor',ownerUsername:username(ownerId),members:db.prepare('SELECT user_id FROM list_members WHERE list_id=? ORDER BY joined_at,user_id').all(listId).map(row=>({userId:row.user_id,username:username(row.user_id)})),inviteActive:isOwner && active,inviteExpiresAt:isOwner && active ? new Date(invitation.expires_at).toISOString() : null};
  }
  function requireOwner(userId,listId) {if(access(userId,id(listId))!==userId) throw new InputError('Only the list owner can manage access.',403);}
  const tokenHash=token=>createHash('sha256').update(token).digest('hex');
  function invitation(userId,token) {
    string(token,'Invitation',128,true);
    if(!/^[A-Za-z0-9_-]{43}$/.test(token)) throw new InputError('This invitation is no longer available.',404);
    const row=db.prepare('SELECT i.*,o.owner_id FROM list_invitations i JOIN list_owners o ON o.list_id=i.list_id WHERE token_hash=?').get(tokenHash(token));
    if(!row || row.expires_at<=Date.now() || (row.consumed_by && (row.consumed_by!==userId || !member.get(row.list_id,userId)))) throw new InputError('This invitation is no longer available.',404);
    return row;
  }
  return {
    ensure,get,action,import:importCopies,export:exportOwned,
    health:()=>db.prepare('SELECT 1').get(),
    sharing,
    invite(userId,listId) {
      requireOwner(userId,listId);
      const token=randomBytes(32).toString('base64url'),expires=Date.now()+7*24*60*60*1000;
      db.prepare('INSERT INTO list_invitations VALUES(?,?,?,NULL) ON CONFLICT(list_id) DO UPDATE SET token_hash=excluded.token_hash,expires_at=excluded.expires_at,consumed_by=NULL').run(listId,tokenHash(token),expires);
      return {...sharing(userId,listId),token};
    },
    revoke(userId,listId) {requireOwner(userId,listId);db.prepare('DELETE FROM list_invitations WHERE list_id=?').run(listId);return sharing(userId,listId);},
    remove(userId,listId,targetUserId) {
      requireOwner(userId,listId);id(targetUserId);
      if(targetUserId===userId) throw new InputError('The owner cannot be removed from their own list.',403);
      transaction(()=> {db.prepare('DELETE FROM list_members WHERE list_id=? AND user_id=?').run(listId,targetUserId);db.prepare('DELETE FROM list_preferences WHERE list_id=? AND user_id=?').run(listId,targetUserId);});
      return sharing(userId,listId);
    },
    leave(userId,listId) {
      if(access(userId,id(listId))===userId) throw new InputError('The owner cannot leave their own list.',403);
      transaction(()=> {db.prepare('DELETE FROM list_members WHERE list_id=? AND user_id=?').run(listId,userId);db.prepare('DELETE FROM list_preferences WHERE list_id=? AND user_id=?').run(listId,userId);});
      return get(userId);
    },
    preview(userId,token) {const row=invitation(userId,token),list=raw(row.owner_id).lists.find(l=>l.id===row.list_id);return {listId:row.list_id,listTitle:list.title,ownerUsername:username(row.owner_id),expiresAt:new Date(row.expires_at).toISOString(),alreadyMember:row.owner_id===userId || Boolean(member.get(row.list_id,userId))};},
    join(userId,token) {
      let listId;
      transaction(()=> {
        ensure(userId);
        const row=invitation(userId,token);listId=row.list_id;
        if(row.owner_id===userId) return;
        if(!member.get(listId,userId)) {
          if(get(userId).lists.length>=1000) throw new InputError('List limit reached.');
          db.prepare('INSERT INTO list_members VALUES(?,?,?)').run(listId,userId,Date.now());
          validateWorkspace(get(userId));
          db.prepare('UPDATE list_invitations SET consumed_by=? WHERE list_id=?').run(userId,listId);
        }
      });
      return {...get(userId),joinedListId:listId};
    },
    close:()=>db.close(),
  };
}
