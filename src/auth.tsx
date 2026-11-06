import { passwordHint, validNewPassword } from './passwordPolicy';
import { Fragment, createContext, useCallback, useContext, useEffect, useRef, useState, type FormEvent, type ReactNode } from 'react';
import { ArrowLeft, ArrowRight, Check, Leaf, LockKeyhole, LogIn, Plus, Users } from 'lucide-react';
import { AUTH_EXPIRED_EVENT, changePassword as changePasswordRequest, getSession, login, logout, registerAccount, type AuthSession } from './api';
import { pendingInvitation } from './invitations';

const AUTH_SIGNAL_KEY = 'gather-auth-signal';
const AUTH_CHANNEL_NAME = 'gather-auth';
const AUTH_SOURCE = `${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}`;
type AuthSignal = 'logout' | 'changed';

function signalAuth(signal: AuthSignal) {
  try { localStorage.setItem(AUTH_SIGNAL_KEY, `${signal}:${Date.now()}`); } catch { /* Cross-tab sync is best effort. */ }
  try { if ('BroadcastChannel' in window) { const channel = new BroadcastChannel(AUTH_CHANNEL_NAME); channel.postMessage({ signal, source: AUTH_SOURCE }); channel.close(); } } catch { /* Older browsers use storage events. */ }
}

function AuthMark() {
  return <div className="auth-mark" aria-hidden="true"><Leaf size={28} strokeWidth={1.4} /></div>;
}

function AuthShell({ children, welcome = false }: { children: ReactNode; welcome?: boolean }) {
  return <main className={`auth-shell${welcome ? ' auth-welcome' : ''}`}><div className="auth-orbit auth-orbit-one" aria-hidden="true" /><div className="auth-orbit auth-orbit-two" aria-hidden="true" /><section className="auth-card">{children}</section></main>;
}

function AuthLoading() {
  return <AuthShell><AuthMark /><p className="auth-eyebrow">Gather</p><h1>Opening Gather…</h1><p className="auth-support">Checking your session.</p><div className="auth-loader" aria-label="Loading" /></AuthShell>;
}

function AuthUnavailable({ message, retry }: { message: string; retry: () => void }) {
  return <AuthShell><AuthMark /><p className="auth-eyebrow">Gather</p><h1>We couldn’t check your session</h1><p className="auth-support">{message}</p><button className="primary auth-submit" onClick={retry}>Try again</button></AuthShell>;
}

type AuthView = 'welcome' | 'signin' | 'register';

function WelcomeScreen({ message, choose }: { message: string; choose: (view: AuthView) => void }) {
  return <AuthShell welcome><div className="welcome-brand"><AuthMark /><span>Gather</span></div><p className="auth-eyebrow">Lists for everyday life</p><h1>Keep track of<br />what matters.</h1><p className="welcome-support">Organize everyday tasks, trip plans and ideas in one place.</p>
    <div className="welcome-notebook" aria-hidden="true"><div className="welcome-notebook-heading"><Leaf size={17} strokeWidth={1.5} /><span>A few things to keep track of</span></div><div className="welcome-list-row"><span className="welcome-check done"><Check size={13} /></span><span>Pick up the essentials</span></div><div className="welcome-list-row"><span className="welcome-check" /><span>Plan a weekend away</span></div><div className="welcome-list-row"><span className="welcome-idea"><Plus size={14} /></span><span>Save a good idea</span></div></div>
    {pendingInvitation() && <p className="auth-callout"><Users size={18} aria-hidden="true" /><span>You have a list invitation. Sign in or create an account to review it.</span></p>}
    {message && <div className="auth-error welcome-error" role="alert">{message}</div>}
    <div className="welcome-actions"><button className="primary auth-submit" onClick={() => choose('signin')}>Sign in<ArrowRight size={18} aria-hidden="true" /></button><button className="secondary auth-submit" onClick={() => choose('register')}>Create account</button></div><p className="auth-note welcome-privacy"><LockKeyhole size={15} aria-hidden="true" />Private to you. Shared when you choose.</p>
  </AuthShell>;
}

function LoginScreen({ onSuccess, creating, navigate, message = '' }: { onSuccess: (session: AuthSession) => void; creating: boolean; navigate: (view: AuthView) => void; message?: string }) {
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [confirm, setConfirm] = useState('');
  const [error, setError] = useState(message);
  const [busy, setBusy] = useState(false);
  const submit = async (event: FormEvent) => {
    event.preventDefault(); setError('');
    if (!username.trim() || !password) { setError('Enter your username and password.'); return; }
    if (creating && !validNewPassword(password)) { setError(passwordHint); return; }
    if (creating && password !== confirm) { setError('The passwords do not match.'); return; }
    setBusy(true);
    try { onSuccess(await (creating ? registerAccount : login)(username.trim(), password)); } catch (err) { setError((err as Error).message); } finally { setBusy(false); }
  };
  return <AuthShell><button className="text-button auth-back" disabled={busy} onClick={() => navigate('welcome')}><ArrowLeft size={16} aria-hidden="true" />Back to welcome</button><AuthMark /><p className="auth-eyebrow">{creating ? 'Create your Gather account' : 'Sign in to Gather'}</p><h1>{creating ? 'Create your account' : 'Welcome back'}</h1><p className="auth-support">{creating ? 'Keep your lists private. Invite people to the ones you share.' : 'Sign in to see your lists.'}</p>{pendingInvitation() && <p className="auth-callout"><Users size={18} aria-hidden="true" /><span>{creating ? 'Create an account to review your list invitation.' : 'Sign in to review your list invitation.'}</span></p>}<form className="auth-form" onSubmit={submit} noValidate>
    {error && <div className="auth-error" role="alert">{error}</div>}
    <label className="field">Username<input autoFocus autoComplete="username" autoCapitalize="none" spellCheck={false} value={username} onChange={event => setUsername(event.target.value)} maxLength={80} /></label>
    <label className="field">Password<input type="password" autoComplete={creating ? 'new-password' : 'current-password'} value={password} onChange={event => setPassword(event.target.value)} maxLength={128} />{creating && <span className="field-help">{passwordHint}</span>}</label>
    {creating && <label className="field">Confirm password<input type="password" autoComplete="new-password" value={confirm} onChange={event => setConfirm(event.target.value)} maxLength={128} /></label>}
    <button className="primary auth-submit" disabled={busy}>{busy ? creating ? 'Creating account…' : 'Signing in…' : <><LogIn size={17} />{creating ? 'Create account' : 'Sign in'}</>}</button>
  </form><p className="auth-switch">{creating ? 'Already have an account?' : 'New to Gather?'} <button className="text-button" disabled={busy} onClick={() => navigate(creating ? 'signin' : 'register')}>{creating ? 'Sign in' : 'Create an account'}</button></p><p className="auth-note"><LockKeyhole size={15} aria-hidden="true" />{creating ? 'Keep your password somewhere safe. Recovery is handled by the server administrator.' : 'You’ll stay signed in for up to 90 days on this browser.'}</p></AuthShell>;
}

function AuthenticationScreens({ onSuccess, message }: { onSuccess: (session: AuthSession) => void; message: string }) {
  const [view, setView] = useState<AuthView>('welcome');
  if (view === 'welcome') return <WelcomeScreen message={message} choose={setView} />;
  return <LoginScreen key={view} creating={view === 'register'} navigate={setView} onSuccess={onSuccess} message={message} />;
}

interface AuthContextValue {
  session: AuthSession;
  logout: () => Promise<void>;
  changePassword: (currentPassword: string, newPassword: string) => Promise<AuthSession>;
}
const AuthContext = createContext<AuthContextValue | null>(null);

export function useAuth(): AuthContextValue {
  const context = useContext(AuthContext);
  if (!context) throw new Error('useAuth must be used inside AuthGate');
  return context;
}

export default function AuthGate({ children }: { children: ReactNode }) {
  const [, setPendingToken] = useState(pendingInvitation);
  useEffect(() => { const capture = () => setPendingToken(pendingInvitation()); window.addEventListener('hashchange', capture); return () => window.removeEventListener('hashchange', capture); }, []);
  const [session, setSession] = useState<AuthSession | null>(null);
  const [checking, setChecking] = useState(true);
  const [error, setError] = useState('');
  const sessionRef = useRef<AuthSession | null>(null);
  const initialCheck = useRef(true);
  const generation = useRef(0);
  useEffect(() => { sessionRef.current = session; }, [session]);
  const refresh = useCallback(async () => {
    const requestGeneration = ++generation.current;
    try {
      const next = await getSession();
      if (generation.current !== requestGeneration) return;
      if (sessionRef.current?.authenticated && (sessionRef.current.userId !== next.userId || sessionRef.current.username !== next.username || !next.authenticated)) {
        const url = new URL(window.location.href); url.search = ''; window.history.replaceState(null, '', url.pathname + url.hash);
      }
      sessionRef.current = next; setSession(next); setError('');
    } catch (err) {
      if (generation.current === requestGeneration && initialCheck.current) { setSession(null); sessionRef.current = null; setError((err as Error).message); }
    } finally {
      if (generation.current === requestGeneration && initialCheck.current) { initialCheck.current = false; setChecking(false); }
    }
  }, []);
  useEffect(() => { void refresh(); }, [refresh]);
  useEffect(() => {
    const expire = () => { generation.current += 1; const url = new URL(window.location.href); url.search = ''; window.history.replaceState(null, '', url.pathname + url.hash); const signedOut = { authenticated: false, setupRequired: false }; sessionRef.current = signedOut; setSession(signedOut); setChecking(false); setError('Your session has ended. Sign in again to continue.'); };
    const changed = () => { generation.current += 1; setChecking(true); initialCheck.current = true; void refresh(); };
    const onFocus = () => { if (sessionRef.current?.authenticated) void refresh(); };
    const onStorage = (event: StorageEvent) => { if (event.key !== AUTH_SIGNAL_KEY) return; if (event.newValue?.startsWith('logout:')) expire(); else changed(); };
    const channel = 'BroadcastChannel' in window ? new BroadcastChannel(AUTH_CHANNEL_NAME) : null;
    const onChannel = (event: MessageEvent<{ signal?: AuthSignal; source?: string }>) => { if (event.data?.source === AUTH_SOURCE) return; if (event.data?.signal === 'logout') expire(); else if (event.data?.signal === 'changed') changed(); };
    window.addEventListener(AUTH_EXPIRED_EVENT, expire);
    window.addEventListener('focus', onFocus);
    window.addEventListener('storage', onStorage);
    channel?.addEventListener('message', onChannel);
    return () => { window.removeEventListener(AUTH_EXPIRED_EVENT, expire); window.removeEventListener('focus', onFocus); window.removeEventListener('storage', onStorage); channel?.removeEventListener('message', onChannel); channel?.close(); };
  }, [refresh]);
  const authenticated = session?.authenticated === true;
  const complete = (next: AuthSession) => { generation.current += 1; const url = new URL(window.location.href); url.search = ''; window.history.replaceState(null, '', url.pathname + url.hash); sessionRef.current = next; setSession(next); setError(''); signalAuth('changed'); };
  const signOut = useCallback(async () => {
    await logout();
    generation.current += 1;
    const url = new URL(window.location.href); url.search = ''; window.history.replaceState(null, '', url.pathname + url.hash);
    const signedOut = { authenticated: false, setupRequired: false };
    sessionRef.current = signedOut; setSession(signedOut); setError(''); signalAuth('logout');
  }, []);
  const updatePassword = useCallback(async (currentPassword: string, newPassword: string) => {
    const next = await changePasswordRequest(currentPassword, newPassword);
    if (!next.authenticated) { sessionRef.current = next; setSession(next); }
    signalAuth('changed');
    return next;
  }, []);
  if (checking) return <AuthLoading />;
  if (error && !session) return <AuthUnavailable message={error} retry={() => { generation.current += 1; setError(''); setChecking(true); initialCheck.current = true; void refresh(); }} />;
  if (authenticated) return <AuthContext.Provider value={{ session: session!, logout: signOut, changePassword: updatePassword }}><Fragment key={session!.userId || session!.username}>{children}</Fragment></AuthContext.Provider>;
  return <AuthenticationScreens onSuccess={complete} message={error} />;
}

