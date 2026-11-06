import { useEffect, useState } from 'react';
import { Check, Monitor, Moon, Sun } from 'lucide-react';

type Preferences = { mode: string; light: string; dark: string };
function readPreferences(): Preferences {
  try { return { mode: localStorage.getItem('gather-theme') || 'system', light: localStorage.getItem('gather-palette-light') || 'paper', dark: localStorage.getItem('gather-palette-dark') || 'midnight' }; }
  catch { return { mode: 'system', light: 'paper', dark: 'midnight' }; }
}
const palettes = [
  { id: 'paper', mode: 'light', name: 'Paper', description: 'Warm ivory & sage' },
  { id: 'cloud', mode: 'light', name: 'Cloud', description: 'Soft white & blue' },
  { id: 'midnight', mode: 'dark', name: 'Midnight', description: 'Deep navy & mist' },
  { id: 'forest', mode: 'dark', name: 'Forest', description: 'Charcoal & sage' },
];
export default function AppearancePicker() {
  const [preferences, setPreferences] = useState(readPreferences);
  useEffect(() => { const sync = () => setPreferences(readPreferences()); window.addEventListener('storage', sync); return () => window.removeEventListener('storage', sync); }, []);
  const save = (next: Preferences) => {
    setPreferences(next);
    try { localStorage.setItem('gather-theme', next.mode); localStorage.setItem('gather-palette-light', next.light); localStorage.setItem('gather-palette-dark', next.dark); } catch { /* Appearance still works when browser storage is unavailable. */ }
    window.dispatchEvent(new CustomEvent('gather-theme-change', { detail: next }));
  };
  return <div className="appearance-picker">
    <div className="segmented appearance-modes" aria-label="Appearance mode">{(['system', 'light', 'dark'] as const).map(mode => <button key={mode} type="button" className={preferences.mode === mode ? 'active' : ''} aria-pressed={preferences.mode === mode} onClick={() => save({ ...preferences, mode })}>{mode === 'system' ? <Monitor size={17} /> : mode === 'light' ? <Sun size={17} /> : <Moon size={17} />}{mode[0].toUpperCase() + mode.slice(1)}</button>)}</div>
    <p className="field-help appearance-help">{preferences.mode === 'system' ? 'Follows your device, using your chosen light and dark palettes.' : 'Choose a palette.'} Saved in this browser.</p>
    {['light', 'dark'].map(mode => <fieldset className="palette-group" key={mode}><legend>{mode === 'light' ? 'Light palettes' : 'Dark palettes'}</legend><div className="palette-grid">{palettes.filter(p => p.mode === mode).map(palette => {
      const selected = (mode === 'light' ? preferences.light : preferences.dark) === palette.id;
      return <button key={palette.id} type="button" className={'palette-card palette-' + palette.id + (selected ? ' chosen' : '')} aria-label={palette.name + ' ' + mode + ' palette'} aria-pressed={selected} onClick={() => save({ ...preferences, mode, [mode]: palette.id })}>
        <span className="palette-preview" aria-hidden="true"><span className="palette-mini-sidebar" /><span className="palette-mini-content"><span className="palette-mini-line" /><span className="palette-mini-card"><span /><span /><span /></span></span><span className="palette-check">{selected && <Check size={16} />}</span></span>
        <span className="palette-name">{palette.name}</span><span className="palette-description">{palette.description}</span>
      </button>;
    })}</div></fieldset>)}
  </div>;
}
