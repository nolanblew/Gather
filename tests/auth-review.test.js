import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, readFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { DatabaseSync } from 'node:sqlite';
import { startServer } from '../server/index.js';

const execFileAsync = promisify(execFile);
const PASSWORD = 'test-password-123';

function cookiePair(response) {
  return response.headers.get('set-cookie')?.split(';', 1)[0] || '';
}

function cookieParts(pair) {
  const match = /^([^=]+)=([A-Za-z0-9_-]+)$/.exec(pair);
  assert.ok(match, `expected a session cookie pair, got ${pair}`);
  return { name: match[1], token: match[2] };
}

async function fixture(t, authEnv = {}) {
  const dir = await mkdtemp(join(tmpdir(), 'gather-auth-review-'));
  let server = await startServer({
    dataDir: dir,
    distDir: join(dir, 'dist'),
    port: 0,
    searchEnv: { SEARCH_PROVIDER: 'fuzzy' },
    authEnv,
  });
  let base = `http://127.0.0.1:${server.address().port}`;
  const stop = () => server ? new Promise(resolve => server.close(() => { server = null; resolve(); })) : Promise.resolve();
  t.after(async () => { await stop(); await rm(dir, { recursive: true, force: true }); });
  const request = async (path, value, cookie = '') => {
    const response = await fetch(base + path, value === undefined
      ? { headers: cookie ? { Cookie: cookie } : {} }
      : { method: 'POST', headers: { 'Content-Type': 'application/json', ...(cookie ? { Cookie: cookie } : {}) }, body: JSON.stringify(value) });
    return { response, value: await response.json() };
  };
  const setup = async () => {
    const result = await request('/api/auth/register', { username: 'owner', password: PASSWORD });
    assert.equal(result.response.status, 200, JSON.stringify(result.value));
    return result;
  };
  const login = async (password = PASSWORD) => {
    const result = await request('/api/auth/login', { username: 'owner', password });
    const cookie = cookiePair(result.response);
    return { ...result, cookie };
  };
  return {
    dir,
    request,
    setup,
    login,
    close: async () => { await stop(); server = null; },
    restart: async () => {
      await stop();
      server = await startServer({ dataDir: dir, distDir: join(dir, 'dist'), port: 0, searchEnv: { SEARCH_PROVIDER: 'fuzzy' }, authEnv });
      base = `http://127.0.0.1:${server.address().port}`;
    },
  };
}

test('sessions are random bearer values while only SHA-256 token hashes persist', async t => {
  const f = await fixture(t, { GATHER_SETUP_TOKEN: 'review-setup-token' });
  const loggedIn = await f.setup();
  assert.equal(loggedIn.response.status, 200);
  const cookie = cookiePair(loggedIn.response);
  const { token } = cookieParts(cookie);

  const db = new DatabaseSync(join(f.dir, 'account.sqlite'));
  try {
    const rows = db.prepare('SELECT token_hash, username, expires_at FROM sessions').all();
    assert.equal(rows.length, 1);
    assert.equal(rows[0].username, 'owner');
    assert.match(rows[0].token_hash, /^[0-9a-f]{64}$/);
    assert.notEqual(rows[0].token_hash, token);
    assert.ok(rows[0].expires_at > Date.now());
    const account = db.prepare("SELECT password_hash FROM users WHERE username='owner'").get();
    assert.match(account.password_hash, /^[A-Za-z0-9+/]+=*:[A-Za-z0-9+/]+=*$/);
    assert.doesNotMatch(account.password_hash, /test-password-123/);
  } finally { db.close(); }
});

test('session cookie carries the durable lifetime and transport flags', async t => {
  const f = await fixture(t, { GATHER_SETUP_TOKEN: 'review-setup-token', GATHER_COOKIE_SECURE: 'true' });
  const setup = await f.setup();
  const header = setup.response.headers.get('set-cookie') || '';
  const cookie = cookiePair(setup.response);
  const { name } = cookieParts(cookie);
  assert.equal(name, '__Host-gather_session');
  assert.match(header, /HttpOnly/);
  assert.match(header, /SameSite=Lax/);
  assert.match(header, /Max-Age=7776000/);
  assert.match(header, /Secure/);
});

test('expired sessions are rejected and removed durably', async t => {
  const f = await fixture(t, { GATHER_SETUP_TOKEN: 'review-setup-token' });
  const setup = await f.setup();
  const cookie = cookiePair(setup.response);
  cookieParts(cookie);
  const db = new DatabaseSync(join(f.dir, 'account.sqlite'));
  try { db.prepare('UPDATE sessions SET expires_at=?').run(Date.now() - 1); } finally { db.close(); }
  assert.equal((await f.request('/api/workspace', undefined, cookie)).response.status, 401);
  const after = new DatabaseSync(join(f.dir, 'account.sqlite'));
  try { assert.equal(after.prepare('SELECT COUNT(*) AS count FROM sessions').get().count, 0); } finally { after.close(); }
});

test('failed logins are throttled per peer after eight attempts', async t => {
  const f = await fixture(t, { GATHER_SETUP_TOKEN: 'review-setup-token' });
  await f.setup();
  for (let attempt = 1; attempt <= 8; attempt++) {
    const result = await f.login('wrong-password-123');
    assert.equal(result.response.status, 401, `attempt ${attempt}`);
  }
  const throttled = await f.login('wrong-password-123');
  assert.equal(throttled.response.status, 429);
  assert.equal(throttled.value.error, 'Too many failed login attempts. Try again later.');
});

test('targeted recovery preserves identity, content, sharing and another account session', async t => {
  const f = await fixture(t, { GATHER_SETUP_TOKEN: 'review-setup-token' });
  await f.setup();
  const loggedIn = await f.login();
  const before = (await f.request('/api/export', undefined, loggedIn.cookie)).value;
  const ownerIdentity=(await f.request('/api/auth/session',undefined,loggedIn.cookie)).value.userId;
  const other=await f.request('/api/auth/register',{username:'other',password:PASSWORD});
  const otherCookie=cookiePair(other.response);
  await f.close();

  const result = await execFileAsync(process.execPath, ['server/reset-account.mjs', '--username', 'owner', '--confirm'], {
    cwd: process.cwd(),
    env: { ...process.env, DATA_DIR: f.dir, GATHER_RESET_PASSWORD:'Recovered-password-123' },
  });
  assert.match(result.stdout, /Password reset/);
  const accountDb = new DatabaseSync(join(f.dir, 'account.sqlite'));
  try {
    assert.equal(accountDb.prepare("SELECT id FROM users WHERE username='owner'").get().id,ownerIdentity);
    assert.equal(accountDb.prepare('SELECT COUNT(*) AS count FROM sessions WHERE user_id=?').get(ownerIdentity).count,0);
    assert.equal(accountDb.prepare('SELECT COUNT(*) AS count FROM sessions').get().count,1);
  } finally { accountDb.close(); }
  await f.restart();
  assert.equal((await f.request('/api/workspace',undefined,otherCookie)).response.status,200);
  assert.equal((await f.request('/api/workspace',undefined,loggedIn.cookie)).response.status,401);
  const recovered=await f.login('Recovered-password-123');
  assert.equal(recovered.response.status,200);
  assert.deepEqual((await f.request('/api/export',undefined,recovered.cookie)).value,before);
});
