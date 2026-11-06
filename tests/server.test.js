import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, rm, writeFile, mkdir } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { startServer } from '../server/index.js';
import { createStore, WORKSPACE_BYTE_LIMIT } from '../server/store.mjs';
import { DatabaseSync } from 'node:sqlite';
import { seedWorkspace } from '../server/seed.mjs';
import { scryptSync } from 'node:crypto';

async function fixture(t) {
  const dir = await mkdtemp(join(tmpdir(), 'gather-test-'));
  const authEnv = { GATHER_SETUP_TOKEN: 'test-setup-token' };
  // Exercise existing saved sample content through the real owner migration.
  const seeded=createStore(dir);seeded.close();
  const legacyAuth=new DatabaseSync(join(dir,'account.sqlite'));
  legacyAuth.exec('CREATE TABLE account(id INTEGER PRIMARY KEY,username TEXT,password_hash TEXT,created_at INTEGER)');
  const salt=Buffer.alloc(16,7),hash=scryptSync('test-password-123',salt,64,{N:16384,r:8,p:5,maxmem:32*1024*1024});
  legacyAuth.prepare('INSERT INTO account VALUES(1,?,?,?)').run('owner',`${salt.toString('base64')}:${hash.toString('base64')}`,Date.now());legacyAuth.close();
  let server = await startServer({ dataDir: dir, distDir: join(dir,'dist'), port: 0, searchEnv: { SEARCH_PROVIDER:'fuzzy' }, authEnv });
  let base = `http://127.0.0.1:${server.address().port}`;
  const stop = () => new Promise(resolve => server.close(resolve));
  t.after(async () => { await stop(); await rm(dir, { recursive: true, force: true }); });
  let cookie = '';
  const login = await fetch(base + '/api/auth/login', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ username: 'owner', password: 'test-password-123' }) });
  assert.equal(login.status, 200, await login.text());
  cookie = login.headers.get('set-cookie')?.split(';', 1)[0] || '';
  const request = async (path, value, headers = {}) => { const res = await fetch(base + path, value === undefined ? { headers: cookie ? { Cookie: cookie, ...headers } : headers } : { method: 'POST', headers: { 'Content-Type':'application/json', ...(cookie ? { Cookie: cookie } : {}), ...headers }, body: JSON.stringify(value) }); return { status: res.status, value: await res.json() }; };
  const action = async (type, payload = {}) => { const response = await request('/api/actions',{type,payload}); assert.equal(response.status,200,JSON.stringify(response.value)); return response.value; };
  return { dir, request, action, restart: async () => { await stop(); server = await startServer({ dataDir:dir,port:0,searchEnv:{SEARCH_PROVIDER:'fuzzy'},authEnv }); base = `http://127.0.0.1:${server.address().port}`; } };
}
test('samples seed once, granular concurrent additions persist across restart', async t => {
  const f = await fixture(t);
  const initial = (await f.request('/api/workspace')).value;
  assert.equal(initial.lists.length,7);
  await f.action('samples.remove');
  let w = await f.action('list.create',{title:'Our shopping'});
  const listId = w.lists[0].id;
  await Promise.all(['Apples','Bread'].map(text => f.action('item.create',{listId,text})));
  await f.restart();
  w = (await f.request('/api/workspace')).value;
  assert.equal(w.lists.length,1);
  assert.deepEqual(w.lists[0].items.map(i=>i.text).sort(),['Apples','Bread']);
  assert.equal(w.lists[0].isSample,false);
});
test('template cloning resets state and isolates children, notes and order survive export restore', async t => {
  const f = await fixture(t);
  const initial = (await f.request('/api/workspace')).value;
  const master = initial.lists.find(l=>l.isTemplate);
  await f.action('item.update',{listId:master.id,itemId:master.items[0].id,checked:true,quantity:'2 bags',note:'Bring decaf'});
  let w = await f.action('list.clone',{listId:master.id,title:'Weekend away'});
  const copy = w.lists.at(-1);
  assert.equal(copy.isTemplate,false); assert.equal(copy.isSample,false); assert.equal(copy.favorite,false);
  assert.ok(copy.items.every(i=>!i.checked));
  assert.equal(copy.items[0].quantity,'2 bags'); assert.equal(copy.items[0].note,'Bring decaf');
  assert.notEqual(copy.items[0].id,master.items[0].id); assert.notEqual(copy.sections[0].id,master.sections[0].id);
  w = await f.action('item.update',{listId:copy.id,itemId:copy.items[0].id,text:'Changed copy'});
  assert.equal(w.lists.find(l=>l.id===master.id).items[0].text,master.items[0].text);
  await f.action('item.move',{listId:copy.id,itemId:copy.items[0].id,sectionId:null,index:0});
  const exported = (await f.request('/api/export')).value;
  await f.action('samples.remove');
  const restored = await f.request('/api/import',exported);
  assert.equal(restored.status,200);
  assert.equal(restored.value.lists.length,exported.lists.length+1);
  const imported=restored.value.lists.filter(l=>!exported.lists.some(old=>old.id===l.id));
  assert.equal(imported.length,exported.lists.length);
  const importedCopy=imported.find(l=>l.title==='Weekend away');
  assert.equal(importedCopy.items[0].text,'Changed copy');
  assert.equal(importedCopy.items[0].note,'Bring decaf');
  assert.equal(importedCopy.items[0].sectionId,null);
  assert.ok(importedCopy.items.every(i=>!i.checked));
});
test('invalid imports, body limits and foreign parent IDs never partially change the database', async t => {
  const f = await fixture(t), baseline = (await f.request('/api/workspace')).value;
  const invalid = structuredClone(baseline); invalid.lists[1].items[0].sectionId = baseline.lists[0].sections[0].id;
  assert.equal((await f.request('/api/import',invalid)).status,400);
  const duplicate = structuredClone(baseline); duplicate.lists[1].id = duplicate.lists[0].id;
  assert.equal((await f.request('/api/import',duplicate)).status,400);
  const oversized = structuredClone(baseline); oversized.lists[0].title = 'a'.repeat(201);
  assert.equal((await f.request('/api/import',oversized)).status,400);
  const tooMany = structuredClone(baseline); tooMany.categories = Array(101).fill(baseline.categories[0]);
  assert.equal((await f.request('/api/import',tooMany)).status,400);
  assert.equal((await f.request('/api/import',{extra:'x'.repeat(5*1024*1024)})).status,413);
  assert.equal((await f.request('/api/actions',{type:'item.update',payload:{listId:baseline.lists[0].id,itemId:baseline.lists[1].items[0].id,text:'Nope'}})).status,400);
  assert.equal((await f.request('/api/actions',{type:'item.create',payload:{listId:baseline.lists[0].id,sectionId:baseline.lists[1].sections[0]?.id || 'foreign',text:'Nope'}})).status,400);
  assert.equal((await f.request('/api/actions',{type:'item.create',payload:{listId:baseline.lists[0].id,text:'  '}})).status,400);
  assert.deepEqual((await f.request('/api/workspace')).value,baseline);
});
test('section deletion preserves items and category deletion uncategorizes lists', async t => {
  const f = await fixture(t), baseline = (await f.request('/api/workspace')).value, list = baseline.lists[0];
  const section = list.sections[0], ids = list.items.filter(i=>i.sectionId===section.id).map(i=>i.id);
  let w = await f.action('section.delete',{listId:list.id,sectionId:section.id});
  assert.ok(w.lists[0].items.filter(i=>ids.includes(i.id)).every(i=>i.sectionId===null));
  assert.equal(w.lists[0].items.length,list.items.length);
  w = await f.action('category.delete',{categoryId:list.categoryId});
  assert.equal(w.lists[0].categoryId,null);
});
test('write origin protection and static security headers', async t => {
  const f = await fixture(t);
  const before = (await f.request('/api/workspace')).value;
  assert.equal((await f.request('/api/actions',{type:'samples.remove',payload:{}},{Origin:'https://evil.example'})).status,403);
  assert.equal((await f.request('/api/actions',{type:'samples.remove',payload:{}},{'Sec-Fetch-Site':'cross-site'})).status,403);
  assert.equal((await f.request('/api/actions',{type:'samples.remove',payload:{}},{'Content-Type':'text/plain'})).status,415);
  assert.equal((await f.request('/api/actions',{payload:{}})).status,400);
  assert.deepEqual((await f.request('/api/workspace')).value,before);
  await mkdir(join(f.dir,'dist'));
  await writeFile(join(f.dir,'dist','index.html'),'<!doctype html><title>Gather</title>');
  const server = await startServer({dataDir:join(f.dir,'static-data'),distDir:join(f.dir,'dist'),port:0,searchEnv:{SEARCH_PROVIDER:'fuzzy'}});
  try { const res = await fetch(`http://127.0.0.1:${server.address().port}/lists/123`); assert.equal(res.status,200); assert.equal(res.headers.get('x-content-type-options'),'nosniff'); assert.match(await res.text(),/Gather/); } finally { await new Promise(resolve=>server.close(resolve)); }
});
test('restored sparse positions append new entries and moved items after existing content', async t => {
  const dir=await mkdtemp(join(tmpdir(),'gather-order-'));
  const store=createStore(dir);
  t.after(async()=>{store.close();await rm(dir,{recursive:true,force:true});});
  const f={action:async(type,payload)=>store.action({type,payload}),request:async(path,input)=>({status:200,value:store.import(input)})};
  const imported=store.get();
  imported.categories.forEach((c,index)=>{c.position=10+index*10;});
  imported.lists.forEach((l,index)=>{l.position=20+index*20; l.sections.forEach((s,index)=>{s.position=30+index*30;}); l.items.forEach((i,index)=>{i.position=50+index*50;});});
  assert.equal((await f.request('/api/import',imported)).status,200);
  let w = await f.action('category.create',{name:'Last category'});
  assert.equal(w.categories.at(-1).position,41);
  w = await f.action('list.create',{title:'Last list'}); assert.equal(w.lists.at(-1).position,141);
  w = await f.action('list.clone',{listId:imported.lists[0].id}); assert.equal(w.lists.at(-1).position,142);
  const listId = imported.lists[0].id, sectionId = imported.lists[0].sections[0].id;
  w = await f.action('section.create',{listId,title:'Last section'}); assert.equal(w.lists[0].sections.at(-1).position,61);
  w = await f.action('item.create',{listId,sectionId,text:'Last item'}); assert.equal(w.lists[0].items.at(-1).position,151);
  w = await f.action('item.create',{listId,text:'Already unsectioned'});
  const unsectionedId = w.lists[0].items.at(-1).id;
  w = await f.action('item.update',{listId,itemId:imported.lists[0].items[0].id,sectionId:null});
  let group = w.lists[0].items.filter(i=>i.sectionId===null).sort((a,b)=>a.position-b.position);
  assert.equal(group[0].id,unsectionedId); assert.equal(group.at(-1).id,imported.lists[0].items[0].id);
  // Re-import sparse unsectioned positions to verify section deletion appends too.
  w.lists[0].items.filter(i=>i.sectionId===null).sort((a,b)=>a.position-b.position).forEach((i,index)=>{i.position=1000+index*1000;});
  assert.equal((await f.request('/api/import',w)).status,200);
  w = await f.action('section.delete',{listId,sectionId});
  group = w.lists[0].items.filter(i=>i.sectionId===null).sort((a,b)=>a.position-b.position);
  assert.equal(group[0].id,unsectionedId); assert.equal(group[1].id,imported.lists[0].items[0].id);
  assert.ok(group.slice(2).every(i=>i.position>2000));
});
test('aggregate workspace byte limit rolls back valid growing actions and permits reducing data', async t => {
  const f = await fixture(t), large = (await f.request('/api/workspace')).value;
  large.lists = [large.lists[0]];
  const list = large.lists[0]; list.sections = [];
  list.items = Array.from({length:1200},(_,position)=>({id:`large-${position}`,text:'A saved idea',quantity:'',note:'x'.repeat(4000),checked:false,sectionId:null,position}));
  assert.ok(Buffer.byteLength(JSON.stringify(large)) < WORKSPACE_BYTE_LIMIT);
  assert.equal((await f.request('/api/import',large)).status,200);
  const before = (await f.request('/api/export')).value;
  const importedList=before.lists.find(l=>l.items.length===1200);
  const rejected = await f.request('/api/actions',{type:'list.clone',payload:{listId:importedList.id}});
  assert.equal(rejected.status,413); assert.match(rejected.value.error,/Workspace exceeds 5 MiB/);
  assert.deepEqual((await f.request('/api/export')).value,before);
  assert.equal((await f.request('/api/import',before)).status,413);
  await f.action('item.delete',{listId:importedList.id,itemId:importedList.items[0].id});
  await f.restart(); assert.equal((await f.request('/api/workspace')).value.lists.find(l=>l.id===importedList.id).items.length,1199);
  const store = createStore(join(f.dir,'direct-store'));
  try { const baseline=store.get(); assert.throws(()=>store.import({...large,ignored:'x'.repeat(WORKSPACE_BYTE_LIMIT)}),/Workspace exceeds 5 MiB/); assert.deepEqual(store.get(),baseline); } finally { store.close(); }
});
test('create and clone return exact created list metadata without persisting it', async t => {
  const f = await fixture(t);
  const responses = await Promise.all(['First client','Second client'].map(title => f.action('list.create',{title})));
  for (const [index,response] of responses.entries()) assert.equal(response.lists.find(l=>l.id===response.createdListId).title,['First client','Second client'][index]);
  assert.notEqual(responses[0].createdListId,responses[1].createdListId);
  const cloned = await f.action('list.clone',{listId:responses[0].createdListId,title:'My copy'});
  assert.equal(cloned.lists.find(l=>l.id===cloned.createdListId).title,'My copy');
  assert.notEqual(cloned.createdListId,responses[0].createdListId);
  assert.equal(Object.hasOwn((await f.request('/api/workspace')).value,'createdListId'),false);
  assert.equal(Object.hasOwn((await f.request('/api/export')).value,'createdListId'),false);
  await f.restart();
  assert.equal(Object.hasOwn((await f.request('/api/workspace')).value,'createdListId'),false);
});
function legacyWorkspace() {
  const workspace = seedWorkspace(); workspace.version = 1;
  workspace.lists.forEach(l=>{delete l.kind; delete l.repeatCadence;});
  return workspace;
}
function migratedWorkspace(v1) {
  return {...v1,version:2,lists:v1.lists.map(l=>({...l,kind:'checklist',repeatCadence:'manual'}))};
}
function writeLegacyDatabase(dir, workspace) {
  const db = new DatabaseSync(join(dir,'gather.sqlite'));
  db.exec('CREATE TABLE workspace(id INTEGER PRIMARY KEY CHECK(id=1),document TEXT NOT NULL)');
  db.prepare('INSERT INTO workspace(id,document) VALUES(1,?)').run(JSON.stringify(workspace)); db.close();
}
test('existing v1 database migration persists all original content and never reseeds removed samples', async t => {
  const dir = await mkdtemp(join(tmpdir(),'gather-migration-'));
  t.after(()=>rm(dir,{recursive:true,force:true}));
  const v1 = legacyWorkspace(); v1.lists = [v1.lists[0]]; v1.lists[0].isSample=false;
  v1.lists[0].title = '  Existing title  '; v1.lists[0].items[0].text = '  Saved spacing  ';
  v1.lists[0].position=77; v1.lists[0].items[0].position=999;
  writeLegacyDatabase(dir,v1);
  let store = createStore(dir);
  assert.deepEqual(store.get(),migratedWorkspace(v1)); store.close();
  store = createStore(dir); assert.deepEqual(store.get(),migratedWorkspace(v1)); store.close();
  const db = new DatabaseSync(join(dir,'gather.sqlite'));
  assert.deepEqual(JSON.parse(db.prepare('SELECT document FROM workspace').get().document),migratedWorkspace(v1)); db.close();
  const brokenDir = await mkdtemp(join(tmpdir(),'gather-broken-migration-'));
  t.after(()=>rm(brokenDir,{recursive:true,force:true}));
  const invalid = legacyWorkspace(); invalid.lists[1].items[0].sectionId='missing-section';
  writeLegacyDatabase(brokenDir,invalid); assert.throws(()=>createStore(brokenDir),/Section not found/);
  const broken = new DatabaseSync(join(brokenDir,'gather.sqlite'));
  assert.deepEqual(JSON.parse(broken.prepare('SELECT document FROM workspace').get().document),invalid); broken.close();
});
test('v1 backup copies migrate defaults and v2 import validation stays atomic', async t => {
  const f=await fixture(t),before=(await f.request('/api/workspace')).value,v1=legacyWorkspace();
  const imported=await f.request('/api/import',v1);
  assert.equal(imported.status,200);assert.equal(imported.value.lists.length,before.lists.length+v1.lists.length);
  const copies=imported.value.lists.filter(l=>!before.lists.some(old=>old.id===l.id));
  assert.ok(copies.every(l=>l.kind==='checklist' && l.repeatCadence==='manual'));
  assert.ok(copies.every(l=>!v1.lists.some(old=>old.id===l.id)));
  const v2=migratedWorkspace(v1);v2.lists[0].kind='notes';v2.lists[0].repeatCadence='weekly';
  assert.equal((await f.request('/api/import',v2)).status,200);
  const snapshot=(await f.request('/api/export')).value;
  assert.ok(snapshot.lists.some(l=>l.kind==='notes' && l.repeatCadence==='weekly'));
  for(const [field,value] of [['kind','journal'],['repeatCadence','monthly'],['kind',undefined],['repeatCadence',undefined]]) {
    const invalid=structuredClone(v2);if(value===undefined) delete invalid.lists[0][field];else invalid.lists[0][field]=value;
    assert.equal((await f.request('/api/import',invalid)).status,400);
    assert.deepEqual((await f.request('/api/export')).value,snapshot);
  }
});
test('notes conversion preserves checked history and cadence, rejects completion edits and reset', async t => {
  const f = await fixture(t), initial=(await f.request('/api/workspace')).value;
  const list=initial.lists[0], listId=list.id, itemId=list.items[0].id;
  let w=await f.action('list.update',{listId,kind:'notes',repeatCadence:'daily'});
  assert.deepEqual(w.lists[0].items,list.items);
  const before=w;
  for (const command of [{type:'item.update',payload:{listId,itemId,checked:false}},{type:'item.update',payload:{listId,itemId,checked:true}},{type:'item.create',payload:{listId,text:'Checked note',checked:true}},{type:'list.reset',payload:{listId}}]) {
    assert.equal((await f.request('/api/actions',command)).status,400);
    assert.deepEqual((await f.request('/api/workspace')).value,before);
  }
  w=await f.action('item.update',{listId,itemId,note:'Still editable',quantity:'2'});
  assert.equal(w.lists[0].items[0].checked,true);
  await f.action('item.create',{listId,text:'New note',checked:false});
  w=await f.action('list.update',{listId,kind:'checklist'});
  assert.equal(w.lists[0].items[0].checked,true); assert.equal(w.lists[0].repeatCadence,'daily');
  const content=structuredClone(w.lists[0]); w=await f.action('list.reset',{listId});
  assert.ok(w.lists[0].items.every(i=>!i.checked));
  assert.deepEqual(w.lists[0].items.map(({checked,...i})=>i),content.items.map(({checked,...i})=>i));
  const template=initial.lists.find(l=>l.isTemplate);
  assert.equal((await f.request('/api/actions',{type:'list.reset',payload:{listId:template.id}})).status,400);
  await f.action('list.update',{listId,archived:true});
  assert.equal((await f.request('/api/actions',{type:'list.reset',payload:{listId}})).status,400);
});
test('copies inherit kind and cadence, reset even hidden history, and leave masters unchanged', async t => {
  const f=await fixture(t), initial=(await f.request('/api/workspace')).value;
  const master=initial.lists.find(l=>l.isTemplate);
  await f.action('item.update',{listId:master.id,itemId:master.items[0].id,checked:true});
  const changed=await f.action('list.update',{listId:master.id,kind:'notes',repeatCadence:'weekly'});
  const before=changed.lists.find(l=>l.id===master.id);
  const copied=await f.action('list.clone',{listId:master.id});
  const copy=copied.lists.find(l=>l.id===copied.createdListId);
  assert.equal(copy.kind,'notes'); assert.equal(copy.repeatCadence,'weekly'); assert.equal(copy.isTemplate,false);
  assert.ok(copy.items.every(i=>!i.checked)); assert.deepEqual(copied.lists.find(l=>l.id===master.id),before);
  await f.restart(); assert.equal((await f.request('/api/workspace')).value.lists.find(l=>l.id===copy.id).kind,'notes');
});
test('read-only search API validates bounds, scopes results, refreshes edits and exposes safe status', async t => {
  const f=await fixture(t), before=(await f.request('/api/workspace')).value;
  let response=await f.request('/api/search',{query:'grocreis',scope:'library'});
  assert.equal(response.status,200); assert.equal(response.value.results[0].listId,before.lists[0].id);
  assert.equal(response.value.provider,'fuzzy');
  response=await f.request('/api/search',{query:'USB-C cable',scope:'library'});
  assert.equal(response.value.results[0].listId,before.lists.find(l=>l.title==='Kyoto packing list').id);
  assert.deepEqual((await f.request('/api/workspace')).value,before);
  for (const input of [{query:'x'.repeat(501)},{query:'groceries',scope:'unknown'},{query:'groceries',categoryId:'missing'},{query:'groceries',limit:126},{query:'groceries',limit:0},{query:15}]) assert.equal((await f.request('/api/search',input)).status,400);
  assert.equal((await f.request('/api/search',{query:'groceries'},{Origin:'https://evil.example'})).status,403);
  assert.equal((await f.request('/api/search',{query:'groceries',padding:'x'.repeat(8192)})).status,413);
  const status=(await f.request('/api/search/status')).value;
  assert.equal(status.provider,'fuzzy'); assert.equal(Object.hasOwn(status,'credentials'),false);
  await f.action('list.update',{listId:before.lists[0].id,archived:true});
  assert.equal((await f.request('/api/search',{query:'Weekly groceries',scope:'library'})).value.results.length,0);
  assert.equal((await f.request('/api/search',{query:'Weekly groceries',scope:'archive'})).value.results[0].listId,before.lists[0].id);
  await f.action('list.delete',{listId:before.lists[0].id});
  assert.equal((await f.request('/api/search',{query:'Weekly groceries',scope:'all'})).value.results.length,0);
  assert.equal((await f.request('/api/health')).status,200);
});
