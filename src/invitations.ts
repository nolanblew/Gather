const INVITATION_KEY = 'gather-pending-invitation';
let pendingToken = '';

// A fragment never reaches the server in page requests. Remove it before any
// navigation and retain it only in this tab while the person signs in.
export function pendingInvitation(): string {
  const token = new URLSearchParams(window.location.hash.slice(1)).get('invite');
  if (token && /^[A-Za-z0-9_-]{32,256}$/.test(token)) {
    pendingToken = token;
    try { sessionStorage.setItem(INVITATION_KEY, token); } catch { /* In-memory fallback. */ }
    const url = new URL(window.location.href); url.hash = '';
    window.history.replaceState(window.history.state, '', url.pathname + url.search);
  }
  if (!pendingToken) {
    try { pendingToken = sessionStorage.getItem(INVITATION_KEY) || ''; } catch { /* In-memory fallback. */ }
  }
  return pendingToken;
}

export function clearInvitation() {
  pendingToken = '';
  try { sessionStorage.removeItem(INVITATION_KEY); } catch { /* In-memory fallback. */ }
}

export function invitationUrl(token: string) {
  return window.location.origin + window.location.pathname + '#invite=' + encodeURIComponent(token);
}
