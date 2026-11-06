export const INSTALL_PREFERENCE_KEY = 'gather-install-preference';
export const INSTALL_SNOOZE_MS = 30 * 24 * 60 * 60 * 1000;

export function isIosSafari(userAgent: string, platform: string, touchPoints: number) {
  const ios = /iPad|iPhone|iPod/.test(userAgent) || (platform === 'MacIntel' && touchPoints > 1);
  return ios && /Version\/.+Safari\//.test(userAgent) && !/CriOS|FxiOS|EdgiOS|OPiOS|DuckDuckGo/.test(userAgent);
}

export function isStandalone(displayStandalone: boolean, navigatorStandalone: boolean | undefined) {
  return displayStandalone || navigatorStandalone === true;
}

export function promotionAllowed(value: string | null, now = Date.now()) {
  if (!value) return true;
  try {
    const preference: unknown = JSON.parse(value);
    if (!preference || typeof preference !== 'object') return true;
    const { accepted, dismissedUntil } = preference as { accepted?: unknown; dismissedUntil?: unknown };
    return accepted !== true && !(typeof dismissedUntil === 'number' && Number.isFinite(dismissedUntil) && dismissedUntil > now);
  } catch { return true; }
}
