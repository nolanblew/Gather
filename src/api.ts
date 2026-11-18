import type { Payload, Workspace, SearchResponse, SearchStatus, ListSharing, InvitationPreview } from './types';

export const AUTH_EXPIRED_EVENT = 'gather-auth-expired';

export interface AuthSession {
  authenticated: boolean;
  userId?: string;
  username?: string;
  setupRequired?: boolean;
}

export class ApiError extends Error {
  readonly status: number;
  readonly code?: string;

  constructor(message: string, status: number, code?: string) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.code = code;
  }
}

async function readResponse(response: Response): Promise<unknown> {
  const contentType = response.headers.get('content-type') || '';
  if (!contentType.includes('application/json')) return null;
  try { return await response.json(); } catch { return null; }
}

function notifyAuthExpired(path: string, response: Response) {
  if (response.status === 401 && !path.startsWith('/api/auth/') && typeof window !== 'undefined') {
    window.dispatchEvent(new CustomEvent(AUTH_EXPIRED_EVENT));
  }
}

async function request<T>(path: string, body?: unknown, fallback = 'Unable to save changes. Please try again.', signal?: AbortSignal): Promise<T> {
  const response = await fetch(path, { cache: 'no-store', credentials: 'same-origin', signal, ...(body === undefined ? {} : { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) }) });
  const data = await readResponse(response) as { error?: string; code?: string } | null;
  notifyAuthExpired(path, response);
  if (!response.ok) throw new ApiError(data?.error || fallback, response.status, data?.code);
  if (!data || typeof data !== 'object') throw new ApiError('The server returned an invalid response. Please try again.', 502);
  return data as T;
}

function authSession(value: unknown, requireAuthenticated = false): AuthSession {
  const raw = value && typeof value === 'object' && 'session' in value && value.session && typeof value.session === 'object'
    ? value.session : value;
  const candidate = raw as Partial<AuthSession> | null;
  if (typeof candidate?.authenticated !== 'boolean' || (candidate.authenticated && (typeof candidate.userId !== 'string' || !candidate.userId || typeof candidate.username !== 'string')) || (requireAuthenticated && !candidate.authenticated)) {
    throw new ApiError('The server returned an invalid session. Please try again.', 502);
  }
  return {
    authenticated: candidate.authenticated,
    ...(typeof candidate?.userId === 'string' ? { userId: candidate.userId } : {}),
    ...(typeof candidate?.username === 'string' ? { username: candidate.username } : {}),
    ...(typeof candidate?.setupRequired === 'boolean' ? { setupRequired: candidate.setupRequired } : {}),
  };
}

export const getWorkspace = () => request<Workspace>('/api/workspace');
export const runAction = (type: string, payload: Payload) => request<Workspace>('/api/actions', { type, payload });
export const restoreWorkspace = (document: unknown) => request<Workspace>('/api/import', document);

export async function getSession(): Promise<AuthSession> {
  return authSession(await request<unknown>('/api/auth/session', undefined, 'Unable to verify your session. Please try again.'));
}
export async function login(username: string, password: string): Promise<AuthSession> {
  return authSession(await request<unknown>('/api/auth/login', { username, password }, 'Unable to sign in. Please check your details and try again.'), true);
}
export async function registerAccount(username: string, password: string): Promise<AuthSession> {
  return authSession(await request<unknown>('/api/auth/register', { username, password }, 'Unable to create your account. Please try again.'), true);
}
export const getSharing = (listId: string) => request<ListSharing>('/api/sharing?listId=' + encodeURIComponent(listId));
export const createInvitation = (listId: string) => request<ListSharing>('/api/sharing/invite', { listId });
export const revokeInvitation = (listId: string) => request<ListSharing>('/api/sharing/revoke', { listId });
export const removeMember = (listId: string, userId: string) => request<ListSharing>('/api/sharing/remove', { listId, userId });
export const leaveList = (listId: string) => request<Workspace>('/api/sharing/leave', { listId });
export const previewInvitation = (token: string) => request<InvitationPreview>('/api/invitations/preview', { token });
export const joinInvitation = (token: string) => request<Workspace>('/api/invitations/join', { token });
export async function logout(): Promise<AuthSession> {
  return authSession(await request<unknown>('/api/auth/logout', {}, 'Unable to sign out. Please try again.'), false);
}
export async function changePassword(currentPassword: string, newPassword: string): Promise<AuthSession> {
  return authSession(await request<unknown>('/api/auth/password', { currentPassword, newPassword }, 'Unable to change your password. Please try again.'), true);
}

export async function searchLists(query: string, scope: string, categoryId: string | null, signal: AbortSignal): Promise<SearchResponse> {
  return request<SearchResponse>('/api/search', { query, scope, categoryId, limit: 125 }, 'Smart search is unavailable. Showing text matches.', signal);
}
export async function getSearchStatus(): Promise<SearchStatus> {
  return request<SearchStatus>('/api/search/status', undefined, 'Search status is unavailable.');
}

// A local preflight keeps malformed backups away from the copy-import confirmation.
// The server repeats its complete validation before any write.
export function validateBackup(value: unknown): asserts value is Workspace {
  const document = value as Workspace;
  const fail = () => { throw new Error('This is not a valid Gather backup. The workspace has not changed.'); };
  if (!document || ![1, 2, 3].includes(document.version) || !Array.isArray(document.categories) || !Array.isArray(document.lists) || document.categories.length > 100 || document.lists.length > 1000) fail();
  const ids = new Set<string>();
  const colors = ['sage', 'peach', 'lavender', 'sky', 'sand', 'rose'];
  const text = (s: unknown, max: number, required = false) => typeof s === 'string' && s.length <= max && (!required || !!s.trim());
  const id = (s: unknown) => { if (!text(s, 100, true) || ids.has(s as string)) fail(); ids.add(s as string); };
  const position = (n: unknown) => Number.isSafeInteger(n) && (n as number) >= 0;
  const categoryIds = new Set<string>();
  for (const c of document.categories) { if (!c) fail(); id(c.id); categoryIds.add(c.id); if (!text(c.name, 80, true) || !colors.includes(c.color) || !position(c.position)) fail(); }
  let count = 0;
  for (const l of document.lists) {
    if (!l) fail(); id(l.id);
    if (document.version >= 2 && (!['checklist', 'notes'].includes(l.kind) || !['manual', 'daily', 'weekly'].includes(l.repeatCadence))) fail();
    if (!text(l.title, 200, true) || !text(l.description, 4000) || !text(l.icon, 40, true) || !colors.includes(l.color) || !position(l.position) || (l.categoryId !== null && !categoryIds.has(l.categoryId)) || ['favorite', 'archived', 'isTemplate', 'isSample'].some(k => typeof l[k as keyof GatherListLike] !== 'boolean')) fail();
    if (![l.createdAt, l.updatedAt].every(s => text(s, 40, true) && Number.isFinite(Date.parse(s))) || !Array.isArray(l.sections) || !Array.isArray(l.items) || l.sections.length > 100 || l.items.length > 5000 || (count += l.items.length) > 20000) fail();
    const sections = new Set<string>();
    for (const s of l.sections) { if (!s) fail(); id(s.id); sections.add(s.id); if (!text(s.title, 200, true) || !position(s.position)) fail(); }
    const placeIds = new Set<string>();
    const recipientIds = new Set<string>();
    if (document.version === 3) {
      if ((l.numbered !== undefined && typeof l.numbered !== 'boolean') || (l.places !== undefined && (!Array.isArray(l.places) || l.places.length > 100)) || (l.recipients !== undefined && (!Array.isArray(l.recipients) || l.recipients.length > 200))) fail();
      const label = (value: unknown, required: boolean) => typeof value === 'string' && text(value.trim().replace(/\s+/gu, ' '), 100, required);
      for (const place of l.places || []) {
        if (!place || !label(place.name, true) || !['city', 'store', 'home', 'work', 'online', 'other'].includes(place.kind === undefined ? 'other' : place.kind) || !label(place.region === undefined ? '' : place.region, false) || !text(place.countryCode === undefined ? '' : place.countryCode, 2) || !/^(?:[A-Z]{2})?$/.test(place.countryCode || '')) fail();
        id(place.id);
        placeIds.add(place.id);
      }
      for (const recipient of l.recipients || []) {
        if (!recipient || !label(recipient.name, true)) fail();
        id(recipient.id);
        recipientIds.add(recipient.id);
      }
    }
    for (const i of l.items) {
      if (!i) fail();
      id(i.id);
      if (!text(i.text, 500, true) || !text(i.quantity, 100) || !text(i.note, 4000) || typeof i.checked !== 'boolean' || !position(i.position) || (i.sectionId !== null && !sections.has(i.sectionId))) fail();
      if (document.version !== 3) continue;
      if (i.recipientId != null && !recipientIds.has(i.recipientId)) fail();
      if (i.availability !== undefined) {
        const value = i.availability;
        if (!value || !['unassigned', 'anywhere', 'selected'].includes(value.mode) || !Array.isArray(value.placeIds) || value.placeIds.length > 25 || new Set(value.placeIds).size !== value.placeIds.length || value.placeIds.some(id => !placeIds.has(id)) || (value.mode === 'selected') !== Boolean(value.placeIds.length)) fail();
      }
    }
  }
}
type GatherListLike = Workspace['lists'][number];
