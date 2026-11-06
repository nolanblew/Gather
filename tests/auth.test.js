import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { startServer } from '../server/index.js';

async function fixture(t, authEnv={}) {
  const dir=await mkdtemp(join(tmpdir(),'gather-auth-'));
  let server,base;
  const start=async()=> {server=await startServer({dataDir:dir,distDir:join(dir,'dist'),port:0,searchEnv:{SEARCH_PROVIDER:'fuzzy'},authEnv});base=`http://127.0.0.1:${server.address().port}`;};
  const stop=()=>new Promise(resolve=>server.close(resolve));
  await start();t.after(async()=>{await stop();await rm(dir,{recursive:true,force:true});});
  const request=async(path,value,options={})=> {
    const response=await fetch(base+path,{method:value===undefined ? 'GET' : 'POST',headers:{...(value===undefined ? {} : {'Content-Type':'application/json'}),...(options.cookie ? {Cookie:options.cookie} : {}),...options.headers},...(value===undefined ? {} : {body:JSON.stringify(value)})});
    return {response,value:await response.json()};
  };
  return {dir,request,restart:async()=>{await stop();await start();}};
}
const sessionCookie=response=>response.headers.get('set-cookie')?.split(';',1)[0] || '';
const payload={username:'owner',password:'test-password-123'};

test('new passwords keep six characters and two character categories policy',async t=> {
  const f=await fixture(t);
  for(const password of ['Ab123','abcdef','ABCDEF','123456','!@#$%^','abc   ']) assert.equal((await f.request('/api/auth/register',{...payload,password})).response.status,400,password);
  let currentPassword='Abcdef';
  const registered=await f.request('/api/auth/register',{...payload,password:currentPassword});
  assert.equal(registered.response.status,200);
  let cookie=sessionCookie(registered.response);
  for(const newPassword of ['abcde1','abcde!','ABCDE1','ABCDE!','12345!']) {
    const changed=await f.request('/api/auth/password',{currentPassword,newPassword},{cookie});assert.equal(changed.response.status,200,newPassword);cookie=sessionCookie(changed.response);currentPassword=newPassword;
  }
  assert.equal((await f.request('/api/auth/password',{currentPassword,newPassword:'abcdef'},{cookie})).response.status,400);
});

test('data APIs require a session; public registration is available without setup keys',async t=> {
  const f=await fixture(t);
  assert.deepEqual((await f.request('/api/auth/session')).value,{authenticated:false,setupRequired:false});
  assert.equal((await f.request('/api/health')).response.status,200);
  for(const [path,value] of [['/api/workspace'],['/api/export'],['/api/search/status'],['/api/actions',{}],['/api/import',{}],['/api/search',{query:'test'}],['/api/invitations/preview',{token:'secret'}],['/api/invitations/join',{token:'secret'}]]) assert.equal((await f.request(path,value)).response.status,401,path);
  assert.equal((await f.request('/api/auth/setup',payload)).response.status,410);
  const registered=await f.request('/api/auth/register',payload);assert.equal(registered.response.status,200);assert.ok(registered.value.userId);
  const workspace=await f.request('/api/workspace',undefined,{cookie:sessionCookie(registered.response)});assert.equal(workspace.value.lists.length,0);assert.equal(workspace.value.categories.length,4);
});

test('concurrent registration cannot duplicate normalized usernames',async t=> {
  const f=await fixture(t);
  const results=await Promise.all([f.request('/api/auth/register',payload),f.request('/api/auth/register',{...payload,username:'OWNER'})]);
  assert.equal(results.filter(r=>r.response.status===200).length,1);assert.ok(results.every(r=>[200,409,503].includes(r.response.status)));
  assert.equal((await f.request('/api/auth/register',{...payload,username:' Owner '})).response.status,409);
});

test('login is generic and durable; rotation revokes only this account sessions and logout clears its cookie',async t=> {
  const f=await fixture(t);await f.request('/api/auth/register',payload);
  const wrong=await f.request('/api/auth/login',{...payload,password:'wrong-password'});assert.equal(wrong.response.status,401);assert.equal(wrong.value.error,'Invalid username or password.');
  const first=await f.request('/api/auth/login',payload),second=await f.request('/api/auth/login',payload);
  const firstCookie=sessionCookie(first.response),secondCookie=sessionCookie(second.response);assert.notEqual(firstCookie,secondCookie);
  await f.restart();assert.equal((await f.request('/api/workspace',undefined,{cookie:firstCookie})).response.status,200);
  const changed=await f.request('/api/auth/password',{currentPassword:payload.password,newPassword:'new-password-456'},{cookie:secondCookie});assert.equal(changed.response.status,200);
  const rotatedCookie=sessionCookie(changed.response);assert.notEqual(rotatedCookie,secondCookie);assert.equal((await f.request('/api/workspace',undefined,{cookie:firstCookie})).response.status,401);
  assert.equal((await f.request('/api/auth/logout',{},{cookie:rotatedCookie})).response.status,200);assert.equal((await f.request('/api/workspace',undefined,{cookie:rotatedCookie})).response.status,401);
});

test('auth posts enforce same-origin JSON and registration is throttled',async t=> {
  const f=await fixture(t);
  assert.equal((await f.request('/api/auth/register',payload,{headers:{Origin:'https://evil.example'}})).response.status,403);
  assert.equal((await f.request('/api/auth/register',payload,{headers:{'Content-Type':'text/plain'}})).response.status,415);
  assert.equal((await f.request('/api/auth/register',payload)).response.status,200);
  for(let i=0;i<7;i++) assert.equal((await f.request('/api/auth/register',payload)).response.status,409);
  assert.equal((await f.request('/api/auth/register',{...payload,username:'another'})).response.status,429);
});

test('current-password failures are bounded independently of login',async t=> {
  const f=await fixture(t),registered=await f.request('/api/auth/register',payload),cookie=sessionCookie(registered.response);
  for(let i=0;i<8;i++) assert.equal((await f.request('/api/auth/password',{currentPassword:'wrong-password',newPassword:'new-password-456'},{cookie})).response.status,401);
  assert.equal((await f.request('/api/auth/password',{currentPassword:'wrong-password',newPassword:'new-password-456'},{cookie})).response.status,429);
  assert.equal((await f.request('/api/auth/login',payload)).response.status,200);
});
