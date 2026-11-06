import { useEffect, useId, useState } from 'react';
import { Download, Share, X } from 'lucide-react';
import { dismissInstall, installGather, useInstall } from './install';

function IosSteps() {
  return <div className="install-steps"><p>In Safari, open the page menu, tap <Share size={15} aria-hidden="true" /> <strong>Share</strong>, then:</p><ol><li>Choose <strong>Add to Home Screen</strong>.</li><li>Turn on <strong>Open as Web App</strong> if shown.</li><li>Tap <strong>Add</strong>.</li></ol><p>Gather needs an internet connection to open and save your lists.</p></div>;
}

export function InstallBanner({ hidden }: { hidden: boolean }) {
  const install = useInstall();
  const [ready, setReady] = useState(false);
  const [instructions, setInstructions] = useState(false);
  const instructionsId = useId();
  useEffect(() => { const timer = window.setTimeout(() => setReady(true), 10000); return () => clearTimeout(timer); }, []);
  if (hidden || !ready || install.standalone || !install.promote || (!install.available && !install.ios && !install.pending)) return null;
  return <aside className="install-card" aria-label="Install Gather"><div className="install-card-copy"><strong>Install Gather</strong><p>Add it to your home screen for quick access.</p>{instructions && <div id={instructionsId}><IosSteps /></div>}</div><div className="install-card-actions">{install.ios ? <button className="secondary" aria-expanded={instructions} aria-controls={instructionsId} onClick={() => setInstructions(!instructions)}>How to install</button> : <button className="secondary" disabled={install.pending || !install.available} onClick={() => void installGather()}><Download size={17} />{install.pending ? 'Installing…' : 'Install Gather'}</button>}<button className="icon-button" aria-label="Dismiss install suggestion for 30 days" onClick={dismissInstall}><X size={18} /></button></div></aside>;
}

export function InstallSettings() {
  const install = useInstall();
  const [instructions, setInstructions] = useState(false);
  const instructionsId = useId();
  if (install.standalone) return null;
  // Settings stays useful after a dismissal or native prompt has been consumed.
  return <section className="install-settings"><h3>Install Gather</h3><p>Add Gather to your apps for quick access. Your lists need an internet connection.</p>{!install.secure ? <p>Open Gather over HTTPS to install it. Your current connection does not support installation.</p> : install.available || install.pending ? <button className="secondary" disabled={install.pending} onClick={() => void installGather()}><Download size={17} />{install.pending ? 'Installing…' : 'Install Gather'}</button> : install.ios ? <><button className="secondary" aria-expanded={instructions} aria-controls={instructionsId} onClick={() => setInstructions(!instructions)}>How to install</button>{instructions && <div id={instructionsId}><IosSteps /></div>}</> : <p>Look for <strong>Install Gather</strong>, <strong>Install app</strong> or <strong>Add to Home Screen</strong> in your browser’s menu. Availability depends on your browser and device.</p>}{install.message && <p role="status">{install.message}</p>}</section>;
}
