import { useSyncExternalStore } from 'react';
import { INSTALL_PREFERENCE_KEY, INSTALL_SNOOZE_MS, isIosSafari, isStandalone, promotionAllowed } from './installPolicy';

interface InstallPromptEvent extends Event {
  prompt(): Promise<void>;
  userChoice: Promise<{ outcome: 'accepted' | 'dismissed' }>;
}
type InstallState = { available: boolean; ios: boolean; standalone: boolean; secure: boolean; promote: boolean; pending: boolean; message: string };
let promptEvent: InstallPromptEvent | null = null;
let state: InstallState = { available: false, ios: false, standalone: false, secure: false, promote: false, pending: false, message: '' };
const listeners = new Set<() => void>();
const emit = (next: Partial<InstallState>) => { state = { ...state, ...next }; listeners.forEach(listener => listener()); };
let initialized = false;
function readPreference() { try { return localStorage.getItem(INSTALL_PREFERENCE_KEY); } catch { return null; } }
function savePreference(value: object) { try { localStorage.setItem(INSTALL_PREFERENCE_KEY, JSON.stringify(value)); } catch { /* Session suppression still works when storage is unavailable. */ } }
function currentStandalone() { return isStandalone(window.matchMedia('(display-mode: standalone)').matches, (navigator as Navigator & { standalone?: boolean }).standalone); }

// Run before AuthGate mounts, so a prompt offered while signing in is retained.
export function initializeInstall() {
  if (initialized) return;
  initialized = true;
  const secure = window.isSecureContext;
  emit({ secure, standalone: currentStandalone(), ios: secure && isIosSafari(navigator.userAgent, navigator.platform, navigator.maxTouchPoints), promote: promotionAllowed(readPreference()) });
  window.addEventListener('beforeinstallprompt', event => {
    event.preventDefault();
    promptEvent = event as InstallPromptEvent;
    emit({ available: secure, message: '' });
  });
  window.addEventListener('appinstalled', () => {
    promptEvent = null;
    savePreference({ accepted: true });
    emit({ available: false, promote: false, pending: false, message: '' });
  });
  window.matchMedia('(display-mode: standalone)').addEventListener('change', () => emit({ standalone: currentStandalone() }));
  window.addEventListener('storage', event => { if (event.key === INSTALL_PREFERENCE_KEY) emit({ promote: promotionAllowed(readPreference()) }); });
}

export const useInstall = () => useSyncExternalStore(listener => { listeners.add(listener); return () => listeners.delete(listener); }, () => state);
export function dismissInstall() {
  savePreference({ dismissedUntil: Date.now() + INSTALL_SNOOZE_MS });
  emit({ promote: false });
}
export async function installGather() {
  if (!promptEvent || state.pending || state.standalone) return;
  const event = promptEvent;
  promptEvent = null;
  emit({ pending: true, available: false, message: '' });
  try {
    // prompt() must stay directly in this user-click call chain.
    await event.prompt();
    const choice = await event.userChoice;
    if (choice.outcome === 'accepted') { savePreference({ accepted: true }); emit({ promote: false }); }
    else dismissInstall();
  } catch {
    dismissInstall();
    emit({ message: 'Installation did not open. You can try your browser’s install menu.' });
  } finally { emit({ pending: false }); }
}

export function registerServiceWorker() {
  if (!('serviceWorker' in navigator) || !window.isSecureContext) return;
  // No skipWaiting or controllerchange reload: an update cannot interrupt a draft.
  void navigator.serviceWorker.register('/sw.js', { scope: '/', updateViaCache: 'none' }).catch(error => console.warn('Gather offline page is unavailable.', error));
}
