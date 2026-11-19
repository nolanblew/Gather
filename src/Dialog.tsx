import { useEffect, useId, useRef, type ReactNode } from 'react';
import { X } from 'lucide-react';

export default function Dialog({ title, children, close, busy, className }: { title: string; children: ReactNode; close: () => void; busy: boolean; className?: string }) {
  const ref = useRef<HTMLDialogElement>(null);
  const titleId = useId();
  useEffect(() => {
    const node = ref.current!;
    const prior = document.activeElement as HTMLElement | null;
    node.showModal();
    const field = node.querySelector<HTMLElement>('.dialog-fields input:not([type="radio"]):not([type="checkbox"]):not([type="hidden"]):enabled, .dialog-fields textarea:enabled, .dialog-fields select:enabled');
    const control = node.querySelector<HTMLElement>('.dialog-fields input:enabled, .dialog-fields button:enabled');
    (field || control)?.focus();
    return () => { node.close(); prior?.focus(); };
  }, []);
  return <dialog className={className} ref={ref} aria-labelledby={titleId} onCancel={event => { event.preventDefault(); if (!busy) close(); }} onClick={event => { if (event.target === ref.current && !busy) close(); }}>
    <div className="dialog-inner">
      <header className="dialog-heading"><h2 id={titleId}>{title}</h2><button className="icon-button" aria-label="Close dialog" disabled={busy} onClick={close}><X size={20} /></button></header>
      <fieldset className="dialog-fields" disabled={busy}>{children}</fieldset>
    </div>
  </dialog>;
}
