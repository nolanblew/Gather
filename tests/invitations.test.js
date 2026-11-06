import test, { afterEach } from 'node:test';
import assert from 'node:assert/strict';
import { clearInvitation, invitationUrl, pendingInvitation } from '../src/invitations.ts';

const originalWindow = globalThis.window;
const originalSessionStorage = globalThis.sessionStorage;
const token = 'a'.repeat(43);

function browser(href, storage = new Map()) {
  globalThis.sessionStorage = {
    getItem: key => storage.get(key) ?? null,
    setItem: (key, value) => storage.set(key, value),
    removeItem: key => storage.delete(key),
  };
  globalThis.window = {
    location: new URL(href),
    history: {
      state: null,
      replaceState(state, _title, next) { this.state = state; globalThis.window.location = new URL(next, globalThis.window.location); },
    },
  };
  clearInvitation();
  return storage;
}

afterEach(() => {
  clearInvitation();
  globalThis.window = originalWindow;
  globalThis.sessionStorage = originalSessionStorage;
});

test('an invitation fragment is removed from navigation and retained only for this tab', () => {
  const storage = browser('https://gather.example/?list=current#invite=' + token);
  assert.equal(pendingInvitation(), token);
  assert.equal(window.location.hash, '');
  assert.equal(window.location.search, '?list=current');
  assert.deepEqual([...storage], [['gather-pending-invitation', token]]);
  assert.equal(pendingInvitation(), token);
  clearInvitation();
  assert.equal(pendingInvitation(), '');
  assert.equal(storage.size, 0);
});

test('storage denial still permits invitation review during the current page session', () => {
  browser('https://gather.example/#invite=' + token);
  globalThis.sessionStorage = { getItem() { throw Error('denied'); }, setItem() { throw Error('denied'); }, removeItem() { throw Error('denied'); } };
  assert.equal(pendingInvitation(), token);
  assert.equal(window.location.hash, '');
  assert.equal(pendingInvitation(), token);
  clearInvitation();
  assert.equal(pendingInvitation(), '');
});

test('generated invitation links omit private library query and list navigation', () => {
  browser('https://gather.example/gather/?q=birthday+surprise&list=private#old');
  assert.equal(invitationUrl(token), 'https://gather.example/gather/#invite=' + token);
});

test('malformed or oversized fragment tokens are not retained as invitations', () => {
  const storage = browser('https://gather.example/#invite=short');
  assert.equal(pendingInvitation(), '');
  window.location.hash = '#invite=' + 'x'.repeat(257);
  assert.equal(pendingInvitation(), '');
  assert.equal(storage.size, 0);
});
