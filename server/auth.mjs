import { DatabaseSync } from 'node:sqlite';
import { createHash, randomBytes, randomUUID, timingSafeEqual, scrypt as nodeScrypt } from 'node:crypto';
import { promisify } from 'node:util';
import { mkdirSync } from 'node:fs';
import { join } from 'node:path';
import { InputError } from './store.mjs';

const scrypt = promisify(nodeScrypt);
const SESSION_COOKIE = 'gather_session';
const SESSION_SECONDS = 90 * 24 * 60 * 60;
const PASSWORD_MIN = 6;
const PASSWORD_MAX = 128;
const USERNAME_MAX = 80;
// N=2^14, r=8, p=5 is the OWASP memory-constrained equivalent: it keeps
// memory bounded while adding work, which matters on the 512 MiB deployment.
const SCRYPT_OPTIONS = { N: 16_384, r: 8, p: 5, maxmem: 32 * 1024 * 1024 };
const FAILED_WINDOW_MS = 15 * 60 * 1000;
const FAILED_LIMIT = 8;
const FAILED_MAP_LIMIT = 2048;

function inputObject(value) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new InputError('Expected an object.');
  return value;
}

function text(value, name, max, { required = true, trim = false } = {}) {
  if (typeof value !== 'string' || value.length > max || (required && !value.trim())) {
    throw new InputError(`${name} must be ${required ? 'non-empty text' : 'text'} up to ${max} characters.`);
  }
  return trim ? value.trim() : value;
}

function username(value) {
  return text(value, 'Username', USERNAME_MAX, { trim: true });
}

function password(value, name = 'Password', enforcePolicy = false) {
  if (typeof value !== 'string' || value.length < (enforcePolicy ? PASSWORD_MIN : 1) || value.length > PASSWORD_MAX) {
    throw new InputError(`${name} must be text from ${PASSWORD_MIN} to ${PASSWORD_MAX} characters.`);
  }
  if (enforcePolicy && [/\p{Lu}/u, /\p{Ll}/u, /\p{N}/u, /[^\p{L}\p{N}\s]/u].filter(pattern => pattern.test(value)).length < 2) {
    throw new InputError('Use at least 6 characters and any two of uppercase, lowercase, numbers, or symbols.');
  }
  return value;
}

function digestToken(token) {
  return createHash('sha256').update(token).digest('hex');
}

function cookieValue(req, secure) {
  const header = req.headers.cookie;
  if (typeof header !== 'string') return null;
  for (const part of header.split(';')) {
    const index = part.indexOf('=');
    if (index < 0) continue;
    const name = part.slice(0, index).trim();
    if (name === (secure ? '__Host-' + SESSION_COOKIE : SESSION_COOKIE)) return part.slice(index + 1).trim() || null;
  }
  return null;
}

async function makePasswordHash(passwordValue) {
  const salt = randomBytes(16);
  const derived = await scrypt(passwordValue, salt, 64, SCRYPT_OPTIONS);
  return `${salt.toString('base64')}:${Buffer.from(derived).toString('base64')}`;
}

async function verifyPassword(passwordValue, encoded) {
  if (typeof encoded !== 'string') return false;
  const [saltText, digestText] = encoded.split(':');
  if (!saltText || !digestText) return false;
  try {
    const salt = Buffer.from(saltText, 'base64');
    const expected = Buffer.from(digestText, 'base64');
    if (salt.length < 16 || expected.length !== 64) return false;
    const actual = Buffer.from(await scrypt(passwordValue, salt, expected.length, SCRYPT_OPTIONS));
    return actual.length === expected.length && timingSafeEqual(actual, expected);
  } catch {
    return false;
  }
}

function cookieOptions(secure) {
  return `Path=/; HttpOnly; SameSite=Lax; Max-Age=${SESSION_SECONDS}${secure ? '; Secure' : ''}`;
}

function setCookie(token, secure) {
  return `${secure ? '__Host-' : ''}${SESSION_COOKIE}=${token}; ${cookieOptions(secure)}`;
}

function clearCookie(secure) {
  return `${secure ? '__Host-' : ''}${SESSION_COOKIE}=; ${cookieOptions(secure).replace(`Max-Age=${SESSION_SECONDS}`, 'Max-Age=0')}`;
}

function authKey(req) {
  // Deliberately use the socket peer. Forwarded headers are caller-controlled.
  return req?.socket?.remoteAddress || 'unknown';
}

export function createAuth({ dataDir, env = process.env } = {}) {
  if (!dataDir) throw new TypeError('dataDir is required.');
  mkdirSync(dataDir, { recursive: true });
  const db = new DatabaseSync(join(dataDir, 'account.sqlite'));
  db.exec(`PRAGMA journal_mode=WAL; PRAGMA synchronous=FULL; PRAGMA busy_timeout=5000;
    CREATE TABLE IF NOT EXISTS users (id TEXT PRIMARY KEY, username TEXT NOT NULL, username_key TEXT NOT NULL UNIQUE, password_hash TEXT NOT NULL, created_at INTEGER NOT NULL);
    CREATE TABLE IF NOT EXISTS sessions (token_hash TEXT PRIMARY KEY, username TEXT NOT NULL, user_id TEXT, created_at INTEGER NOT NULL, expires_at INTEGER NOT NULL);
    CREATE INDEX IF NOT EXISTS sessions_expires_at ON sessions(expires_at);`);
  if (!db.prepare('PRAGMA table_info(sessions)').all().some(c => c.name === 'user_id')) db.exec('ALTER TABLE sessions ADD COLUMN user_id TEXT');
  // Existing browser sessions remain cookie-only. A native token is never
  // accepted as a cookie, and an HttpOnly cookie cannot be exchanged for one.
  if (!db.prepare('PRAGMA table_info(sessions)').all().some(c => c.name === 'transport')) db.exec("ALTER TABLE sessions ADD COLUMN transport TEXT NOT NULL DEFAULT 'cookie'");
  // Registration cannot claim the retired singleton owner's data.
  let legacyOwnerId = null;
  const legacy = db.prepare("SELECT name FROM sqlite_master WHERE type='table' AND name='account'").get();
  if (legacy) {
    const old = db.prepare('SELECT username,password_hash,created_at FROM account WHERE id=1').get();
    if (old) {
      const key = old.username.normalize('NFKC').toLowerCase();
      db.exec('BEGIN IMMEDIATE');
      try {
        let user = db.prepare('SELECT id FROM users WHERE username_key=?').get(key);
        if (!user) { const userId = randomUUID(); db.prepare('INSERT INTO users VALUES(?,?,?,?,?)').run(userId,old.username,key,old.password_hash,old.created_at); user = { id:userId }; }
        legacyOwnerId = user.id;
        db.prepare('UPDATE sessions SET user_id=? WHERE user_id IS NULL AND username=?').run(user.id,old.username);
        db.exec('COMMIT');
      } catch (error) { db.exec('ROLLBACK'); db.close(); throw error; }
    }
  }
  const secure = String(env.GATHER_COOKIE_SECURE || '').toLowerCase() === 'true';
  const failed = new Map(), registrations = new Map();
  let hashBusy = false;
  const getUser = db.prepare('SELECT * FROM users WHERE username_key=?');
  const getUserId = db.prepare('SELECT id,username FROM users WHERE id=?');
  const session = db.prepare('SELECT s.user_id,s.expires_at,s.transport,u.username FROM sessions s JOIN users u ON u.id=s.user_id WHERE s.token_hash=?');
  const removeSession = db.prepare('DELETE FROM sessions WHERE token_hash=?');
  const insertSession = db.prepare('INSERT INTO sessions(token_hash,username,user_id,created_at,expires_at,transport) VALUES(?,?,?,?,?,?)');
  const keyFor = name => name.normalize('NFKC').toLowerCase();
  const response = user => ({authenticated:true,userId:user.id,username:user.username,setupRequired:false});
  const issue = (user, transport = 'cookie') => {
    const now=Date.now(),token=randomBytes(32).toString('base64url');
    db.prepare('DELETE FROM sessions WHERE expires_at<=?').run(now);
    const expiresAt=now+SESSION_SECONDS*1000;
    insertSession.run(digestToken(token),user.username,user.id,now,expiresAt,transport);
    return transport === 'bearer'
      ? {...response(user),accessToken:token,tokenType:'Bearer',expiresAt:new Date(expiresAt).toISOString()}
      : {...response(user),setCookie:setCookie(token,secure)};
  };
  function allow(map,key,limit) {
    const now=Date.now();
    for(const [entry,value] of map) if(now-value.startedAt>=FAILED_WINDOW_MS) map.delete(entry);
    return (map.get(key)?.count || 0)<limit;
  }
  function record(map,key) {
    if(!map.has(key)) { if(map.size>=FAILED_MAP_LIMIT) map.delete(map.keys().next().value); map.set(key,{startedAt:Date.now(),count:0}); }
    map.get(key).count++;
  }
  async function passwordWork(callback) {
    if(hashBusy) throw new InputError('Authentication is temporarily busy. Try again shortly.',503);
    hashBusy=true;
    try { return await callback(); } finally { hashBusy=false; }
  }
  function current(req, transport = 'any') {
    const authorization=req.headers.authorization;
    const bearer=transport==='bearer' || (transport==='any' && authorization!==undefined);
    // A malformed or revoked Authorization header never falls back to cookies.
    const token=bearer ? (typeof authorization==='string' ? /^Bearer ([A-Za-z0-9_-]{43})$/i.exec(authorization)?.[1] : null) : cookieValue(req,secure);
    if(!token || token.length>128) return null;
    const tokenHash=digestToken(token),row=session.get(tokenHash);
    if(!row || row.transport!==(bearer ? 'bearer' : 'cookie')) return null;
    if(row.expires_at<=Date.now()) { removeSession.run(tokenHash); return null; }
    return {id:row.user_id,username:row.username,tokenHash};
  }
  async function register(input,req,transport='cookie') {
    inputObject(input);
    const name=username(input.username),pass=password(input.password,'Password',true),key=authKey(req);
    if(!allow(registrations,key,8)) throw new InputError('Too many account creation attempts. Try again later.',429);
    record(registrations,key);
    return passwordWork(async()=> {
      if(getUser.get(keyFor(name))) throw new InputError('That username is unavailable. Choose another.',409);
      const encoded=await makePasswordHash(pass),user={id:randomUUID(),username:name};
      db.exec('BEGIN IMMEDIATE');
      try {
        if(getUser.get(keyFor(name))) throw new InputError('That username is unavailable. Choose another.',409);
        db.prepare('INSERT INTO users VALUES(?,?,?,?,?)').run(user.id,name,keyFor(name),encoded,Date.now());
        const result=issue(user,transport); db.exec('COMMIT'); return result;
      } catch(error) { db.exec('ROLLBACK'); throw error; }
    });
  }
  async function login(input,req,transport='cookie') {
    inputObject(input);
    const name=username(input.username),pass=password(input.password),key=authKey(req);
    if(!allow(failed,key,FAILED_LIMIT)) throw new InputError('Too many failed login attempts. Try again later.',429);
    return passwordWork(async()=> {
      const user=getUser.get(keyFor(name));
      const valid=user ? await verifyPassword(pass,user.password_hash) : (await makePasswordHash(pass),false);
      if(!valid || getUserId.get(user.id)?.username!==user.username || getUser.get(keyFor(name))?.password_hash!==user.password_hash) {
        record(failed,key); throw new InputError('Invalid username or password.',401);
      }
      failed.delete(key); return issue(user,transport);
    });
  }
  async function changePassword(input,req,transport='cookie') {
    inputObject(input);
    const currentPassword=password(input.currentPassword,'Current password'),newPassword=password(input.newPassword,'New password',true);
    const user=current(req,transport);
    if(!user) throw new InputError('Authentication required.',401);
    const attemptKey=`password:${user.id}:${authKey(req)}`;
    if(!allow(failed,attemptKey,FAILED_LIMIT)) throw new InputError('Too many failed password attempts. Try again later.',429);
    return passwordWork(async()=> {
      const row=getUser.get(keyFor(user.username));
      if(!current(req,transport) || !row || !(await verifyPassword(currentPassword,row.password_hash))) {record(failed,attemptKey);throw new InputError('Current password is incorrect.',401);}
      const encoded=await makePasswordHash(newPassword);
      if(!current(req,transport) || getUser.get(keyFor(user.username))?.password_hash!==row.password_hash) throw new InputError('Your session has ended. Sign in again.',401);
      db.exec('BEGIN IMMEDIATE');
      try {
        db.prepare('UPDATE users SET password_hash=? WHERE id=?').run(encoded,user.id);
        db.prepare('DELETE FROM sessions WHERE user_id=?').run(user.id);
        const result=issue(user,transport); db.exec('COMMIT'); failed.delete(attemptKey); return result;
      } catch(error) { db.exec('ROLLBACK'); throw error; }
    });
  }
  return {
    legacyOwnerId,
    session:(req,transport='cookie')=> { const user=current(req,transport); return user ? response(user) : {authenticated:false,setupRequired:false}; },
    current,
    username:userId=>getUserId.get(userId)?.username || 'Gather user',
    register,login,
    logout:(req,transport='cookie')=> { const user=current(req,transport); if(user) removeSession.run(user.tokenHash); return transport==='cookie' ? {setCookie:clearCookie(secure)} : {authenticated:false,setupRequired:false}; },
    password:changePassword,
    close:()=>db.close(),
  };
}

export { SESSION_COOKIE, SESSION_SECONDS, PASSWORD_MIN };

// Operator-only recovery: retain identity, owned content and membership, and
// revoke sessions solely for the named account. Never exposed as a web route.
export async function resetUserPassword({dataDir,username: name,password: pass,env=process.env}) {
  name=username(name);pass=password(pass,'New password',true);
  const auth=createAuth({dataDir,env});auth.close();
  const db=new DatabaseSync(join(dataDir,'account.sqlite'));
  try {
    db.exec('PRAGMA busy_timeout=5000; PRAGMA synchronous=FULL;');
    const user=db.prepare('SELECT id FROM users WHERE username_key=?').get(name.normalize('NFKC').toLowerCase());
    if(!user) throw new InputError('Account not found.',404);
    const encoded=await makePasswordHash(pass);
    db.exec('BEGIN IMMEDIATE');
    try {db.prepare('UPDATE users SET password_hash=? WHERE id=?').run(encoded,user.id);db.prepare('DELETE FROM sessions WHERE user_id=?').run(user.id);db.exec('COMMIT');}
    catch(error) {db.exec('ROLLBACK');throw error;}
    return {userId:user.id};
  } finally {db.close();}
}

